package com.swissdark.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.ContinueSource
import com.swissdark.launcher.platform.tv.BannerArt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A full-screen backdrop for a Continue watching card: HARDWARE bitmap, already darkened. */
class PreparedBackdrop(val key: String, val bitmap: Bitmap, val kind: Kind) {
    enum class Kind { BACKGROUND, POSTER, WASH }
}

/**
 * Focus backdrops for Home: when a Continue watching card is focused, Home crossfades from the
 * wallpaper to that title's art.
 *
 * Art is resolved once per card via OUR providers (TVmaze + OMDb, or TMDB when it is the active
 * source) and the URL is remembered in SharedPreferences, so later sessions only decode. Only
 * confident matches are used (normalised exact names, or a unique episode-name hit); otherwise
 * the card's own image becomes a blurred, darkened colour wash — never a stretched logo.
 *
 * Decoding is off main on one thread; results are HARDWARE bitmaps; at most 3 are kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackdropArt(
    private val context: Context,
    private val sources: TitleSources,
    private val http: OkHttpClient,
    private val banners: BannerArt,
    private val dim: () -> Float,
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val prefs = context.getSharedPreferences("backdrops2", Context.MODE_PRIVATE)  // v2: misses from before web lookup are retried
    private val cache = LruCache<String, PreparedBackdrop>(4)
    private val lock = Mutex()

    /** Prepared backdrop for [item], or null when nothing usable exists. Never throws. */
    suspend fun artFor(item: ContinueItem): PreparedBackdrop? {
        val d = dimLevel()
        val ck = cacheKey(item, d)
        cache.get(ck)?.let { return it }
        return lock.withLock {
            cache.get(ck)?.let { return@withLock it }
            val p = try {
                prepare(item, ck, d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "backdrop for ${item.key} failed", e)
                null
            }
            if (p != null) cache.put(ck, p)
            p
        }
    }

    /** Convenience for UI code that only needs the bitmap. */
    suspend fun bitmapFor(item: ContinueItem): Bitmap? = artFor(item)?.bitmap

    fun trim() = cache.evictAll()

    /**
     * The picture of the episode itself (TVmaze's episode still) for a card whose app publishes only
     * the episode name (Crunchyroll). Finds the show the same way the backdrop does. Never throws.
     */
    suspend fun showArtUrl(item: ContinueItem): String? = try {
        resolveUrl(item)?.first
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun episodeArtUrl(item: ContinueItem): String? = try {
        resolveUrl(item)                                   // finds and remembers the show
        val id = prefs.getInt("s:" + item.key, -1).takeIf { it > 0 }
        val show = id?.let { sources.tvmaze.show(it) }
        BackdropMatch.episodeImage(show, item.name, item.season, item.episode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** IMDb's public suggestions (keyless), the last art fallback. Exact matches only. Never throws. */
    suspend fun imdbArtUrl(item: ContinueItem): String? = try {
        ImdbSuggest(http).artUrl(item.name)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun dimLevel(): Float = maxOf(dim(), MIN_DIM)

    private fun cacheKey(item: ContinueItem, d: Float) = item.key + "|" + BackdropMatch.norm(item.name) + "|" + item.artKey + "|" + d

    private suspend fun prepare(item: ContinueItem, ck: String, d: Float): PreparedBackdrop? {
        val resolved = resolveUrl(item)
        if (resolved != null) {
            val (url, kind) = resolved
            val bytes = runCatching { fetchBytes(url) }.getOrNull()
            val bmp = bytes?.let { withContext(io) { render(ImageDecoder.createSource(ByteBuffer.wrap(it)), kind, d) } }
            if (bmp != null) return PreparedBackdrop(ck, bmp, kind)
        }
        // Fallback: the card's own image. Sharp when it is a real 16:9 picture (a YouTube
        // thumbnail, a still), a colour wash only for small or logo art.
        val cardBytes = item.artKey.takeIf { it.startsWith("http") }?.let { runCatching { fetchBytes(it) }.getOrNull() }
        withContext(io) { sharpFromCard(item, cardBytes, d) }?.let { return PreparedBackdrop(ck, it, PreparedBackdrop.Kind.BACKGROUND) }
        val wash = withContext(io) { washFromCard(item, cardBytes, d) } ?: return null
        return PreparedBackdrop(ck, wash, PreparedBackdrop.Kind.WASH)
    }

    // ---------- resolution (network, remembered) ----------

    private suspend fun resolveUrl(item: ContinueItem): Pair<String, PreparedBackdrop.Kind>? {
        if (!BackdropMatch.lookupWorthy(item)) return null
        val pk = "u:" + item.key + "|" + BackdropMatch.norm(item.name)
        prefs.getString(pk, null)?.let { v ->
            val (stamp, rest) = v.split('\u001F', limit = 2).let { (it[0].toLongOrNull() ?: 0L) to it.getOrElse(1) { "" } }
            if (rest.isNotEmpty()) {
                val kind = runCatching { PreparedBackdrop.Kind.valueOf(rest.substringBefore('|')) }.getOrNull()
                if (kind != null) return rest.substringAfter('|') to kind
            } else if (System.currentTimeMillis() - stamp < MISS_TTL_MS) return null
        }
        val found = try {
            lookup(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "lookup ${item.name} failed", e)
            return null           // network trouble: do not remember a miss
        }
        prefs.edit().putString(pk, System.currentTimeMillis().toString() + "\u001F" + (found?.let { "${it.second.name}|${it.first}" } ?: "")).apply()
        return found
    }

    private suspend fun lookup(item: ContinueItem): Pair<String, PreparedBackdrop.Kind>? {
        // 1. Already matched to a title (WatchedStore matched it): its details carry the art.
        item.title?.let { ref ->
            val det = runCatching { sources.details(ref) }.getOrNull()
            det?.backdropUrl?.let { return it to PreparedBackdrop.Kind.BACKGROUND }
            det?.posterUrl?.let { return it to PreparedBackdrop.Kind.POSTER }
        }
        val name = item.name.trim()
        val pkg = item.service.packageName
        val episodeOnly = pkg == CRUNCHYROLL
        // 2. By series / title name: exact normalised match on TVmaze, then OMDb.
        if (!episodeOnly) {
            val hits = sources.tvmaze.searchShows(name).map { it.second }
            BackdropMatch.pickShow(name, hits, pkg)?.let { s ->
                val id = s.int("id")
                val full = id?.let { sources.tvmaze.show(it) } ?: s
                rememberShow(item, id)
                BackdropMatch.imageFor(full)?.let { return it }
            }
            if (sources.omdb.hasKey) {
                val o = runCatching { sources.omdb.byTitle(name, null) }.getOrNull()
                if (o != null && BackdropMatch.norm(o.str("Title").orEmpty()) == BackdropMatch.norm(name)) {
                    OmdbParse.poster(o)?.let { return it to PreparedBackdrop.Kind.POSTER }
                }
            }
        }
        // 3. Episode name only (Crunchyroll, or a series name that matched nothing): find the show
        // among candidates we can derive and accept it only when exactly one has that episode.
        val ep = if (episodeOnly) name else item.subtitle ?: return null
        val candidates = LinkedHashSet<Int>()
        prefs.getInt("s:" + item.key, -1).takeIf { it > 0 }?.let { candidates += it }
        if (sources.omdb.hasKey) {
            val res = runCatching { sources.omdb.searchEpisodes(ep) }.getOrNull()
            for (imdbEp in BackdropMatch.omdbEpisodeIds(res, ep).take(4)) {
                val series = runCatching { sources.omdb.byId(imdbEp, posters = false) }.getOrNull()?.str("seriesID") ?: continue
                runCatching { sources.tvmaze.lookupImdb(series) }.getOrNull()?.int("id")?.let { candidates += it }
            }
        }
        var shows = candidates.take(5).mapNotNull { runCatching { sources.tvmaze.show(it) }.getOrNull() }
        var show = BackdropMatch.pickByEpisode(shows, ep, item.season, item.episode)
        if (show == null) {
            // Nothing we know has that episode: ask the web which show it belongs to ("Day of
            // Atonement" → "Black Clover Season 2 Episode 4"), then let TVmaze's episode list confirm.
            val names = runCatching { webShowNames(ep, item.serviceName) }.getOrDefault(emptyList())
            val ids = LinkedHashSet<Int>()
            for (n in names.take(3)) {
                runCatching { sources.tvmaze.searchShows(n) }.getOrNull()?.take(2)?.forEach { (_, o) ->
                    (o["show"] as? JsonObject ?: o).int("id")?.let { if (it !in candidates) ids += it }
                }
            }
            shows = ids.take(5).mapNotNull { runCatching { sources.tvmaze.show(it) }.getOrNull() }
            show = BackdropMatch.pickByEpisode(shows, ep, item.season, item.episode)
        }
        if (show == null) return null
        rememberShow(item, show.int("id"))
        return BackdropMatch.imageFor(show)
    }

    /** Show names that web results pair with this episode title, most frequent first. */
    private suspend fun webShowNames(episode: String, service: String): List<String> {
        val q = java.net.URLEncoder.encode("\"$episode\" $service episode", "UTF-8")
        val req = Request.Builder().url("https://html.duckduckgo.com/html/?q=$q")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) SwissHome").build()
        val html = String(fetchBytes(req), Charsets.UTF_8)
        return BackdropMatch.showNamesFromResults(BackdropMatch.resultTitles(html), episode)
    }

    private fun rememberShow(item: ContinueItem, id: Int?) {
        if (id != null) prefs.edit().putInt("s:" + item.key, id).apply()
    }

    // ---------- decoding ----------

    /** Cover-crop to 16:9, darken, copy to HARDWARE. Posters (portrait) become a soft wash. */
    private fun render(src: ImageDecoder.Source, kind: PreparedBackdrop.Kind, d: Float): Bitmap? {
        val sw = decodeCover(src, if (kind == PreparedBackdrop.Kind.BACKGROUND) FULL_W else SMALL_W, if (kind == PreparedBackdrop.Kind.BACKGROUND) FULL_H else SMALL_H) ?: return null
        return when (kind) {
            PreparedBackdrop.Kind.BACKGROUND -> finish(sw, d)
            else -> wash(sw, d, if (kind == PreparedBackdrop.Kind.POSTER) 3 else 6)
        }
    }

    /** The card's own image as a sharp backdrop, when it is at least 480 px wide and about 16:9. */
    private fun sharpFromCard(item: ContinueItem, httpBytes: ByteArray?, d: Float): Bitmap? {
        val a = item.artKey
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val path = when {
            a.startsWith("file:") -> Uri.parse(a).path
            a.startsWith("/") -> a
            else -> null
        }
        when {
            httpBytes != null -> android.graphics.BitmapFactory.decodeByteArray(httpBytes, 0, httpBytes.size, opts)
            path != null -> android.graphics.BitmapFactory.decodeFile(path, opts)
            else -> return null
        }
        val w = opts.outWidth; val h = opts.outHeight
        if (!BackdropMatch.sharpWorthy(w, h)) return null
        val src = if (httpBytes != null) ImageDecoder.createSource(ByteBuffer.wrap(httpBytes)) else ImageDecoder.createSource(File(path!!))
        val big = w >= 1280
        val sw = decodeCover(src, if (big) FULL_W else SMALL_W, if (big) FULL_H else SMALL_H) ?: return null
        return finish(sw, d)
    }

    private fun washFromCard(item: ContinueItem, httpBytes: ByteArray?, d: Float): Bitmap? {
        val a = item.artKey
        val src: Bitmap? = when {
            a.startsWith("banner:") -> banners.banner(a.removePrefix("banner:"), 320, 180)?.let { b ->
                // Banners are HARDWARE: read back once, small.
                if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            }?.let { scaleCover(it, SMALL_W, SMALL_H) }
            a.startsWith("http") -> httpBytes?.let { decodeCover(ImageDecoder.createSource(ByteBuffer.wrap(it)), SMALL_W, SMALL_H) }
            a.startsWith("content:") || a.startsWith("android.resource:") ->
                decodeCover(ImageDecoder.createSource(context.contentResolver, Uri.parse(a)), SMALL_W, SMALL_H)
            a.startsWith("file:") -> decodeCover(ImageDecoder.createSource(File(Uri.parse(a).path ?: return null)), SMALL_W, SMALL_H)
            a.startsWith("/") -> decodeCover(ImageDecoder.createSource(File(a)), SMALL_W, SMALL_H)
            else -> null
        }
        return src?.let { wash(it, d, 6) }
    }

    private fun decodeCover(src: ImageDecoder.Source, w: Int, h: Int): Bitmap? = try {
        val b = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = true
            val s = maxOf(w.toFloat() / info.size.width, h.toFloat() / info.size.height)
            dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(w), (info.size.height * s).toInt().coerceAtLeast(h))
        }
        scaleCover(b, w, h)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "decode failed", e)
        null
    }

    /** Centre-crop/scale [b] to exactly w × h, mutable ARGB_8888. */
    private fun scaleCover(b: Bitmap, w: Int, h: Int): Bitmap {
        if (b.width == w && b.height == h && b.isMutable && b.config == Bitmap.Config.ARGB_8888) return b
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val s = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val cw = (w / s).toInt().coerceIn(1, b.width); val ch = (h / s).toInt().coerceIn(1, b.height)
        val l = (b.width - cw) / 2; val t = (b.height - ch) / 2
        Canvas(out).drawBitmap(b, Rect(l, t, l + cw, t + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        b.recycle()
        return out
    }

    /** Low-res blur → upscale → darken: a colour wash. */
    private fun wash(b: Bitmap, d: Float, radius: Int): Bitmap? {
        val tiny = scaleCover(b, WASH_W, WASH_H)
        val px = IntArray(WASH_W * WASH_H)
        tiny.getPixels(px, 0, WASH_W, 0, 0, WASH_W, WASH_H)
        repeat(3) { BackdropMatch.boxBlur(px, WASH_W, WASH_H, radius) }
        tiny.setPixels(px, 0, WASH_W, 0, 0, WASH_W, WASH_H)
        val up = Bitmap.createScaledBitmap(tiny, SMALL_W, SMALL_H, true)
        if (up !== tiny) tiny.recycle()
        val m = if (up.isMutable) up else up.copy(Bitmap.Config.ARGB_8888, true).also { up.recycle() }
        // Blurring greys colours out: give the saturation back, and darken less than a photo
        // (FocusBackdrop's scrims keep the text readable): a wash, not mud.
        val sat = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        Canvas(sat).drawBitmap(m, 0f, 0f, Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(WASH_SATURATION) })
        })
        m.recycle()
        return finish(sat, d * WASH_DIM)
    }

    private fun finish(sw: Bitmap, d: Float): Bitmap {
        val c = Canvas(sw)
        c.drawColor(android.graphics.Color.argb((d * 255).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_OVER)
        // The readability shades live in the picture itself (they fade with it), so the TV's GPU
        // draws no extra full-screen gradients while the art cross-fades (it is a 4K panel).
        val w = sw.width.toFloat(); val h = sw.height.toFloat()
        fun black(a: Float) = android.graphics.Color.argb((a * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f, intArrayOf(black(0.72f), black(0.27f), black(0.28f), black(0.55f)),
                floatArrayOf(0f, 0.55f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        })
        c.drawRect(0f, h * 0.45f, w, h, Paint().apply {
            shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, black(0f), black(0.78f), android.graphics.Shader.TileMode.CLAMP)
        })
        val hw = sw.copy(Bitmap.Config.HARDWARE, false)
        if (hw != null) sw.recycle()
        return hw ?: sw
    }

    /** OkHttp (with its disk cache) on its own threads; bytes decoded by ImageDecoder, not Coil. */
    private suspend fun fetchBytes(url: String): ByteArray = fetchBytes(Request.Builder().url(url).build())

    private suspend fun fetchBytes(request: Request): ByteArray {
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            it.body?.bytes() ?: throw IOException("empty")
                        }
                    } catch (e: IOException) {
                        cont.resumeWithException(e); return
                    }
                    cont.resume(r)
                }
            })
        }
    }

    companion object {
        private const val TAG = "BackdropArt"
        const val CRUNCHYROLL = WatchedStore.CRUNCHYROLL
        private const val FULL_W = 1920
        private const val FULL_H = 1080
        private const val SMALL_W = 960
        private const val SMALL_H = 540
        private const val WASH_W = 192
        private const val WASH_H = 108
        private const val MIN_DIM = 0.62f
        private const val WASH_DIM = 0.7f
        private const val WASH_SATURATION = 1.35f
        private const val MISS_TTL_MS = 24 * 60 * 60_000L
    }
}

/** Pure matching logic (unit-tested). */
object BackdropMatch {
    private val YEAR = Regex("""\s*\((19|20)\d\d\)\s*$""")

    fun norm(s: String): String = YEAR.replace(s, "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** App-only cards (Netflix, Prime… with no title) have nothing to look up. */
    fun lookupWorthy(item: ContinueItem): Boolean {
        if (item.source == ContinueSource.APP) return false
        // Untitled app playback, unless it was named after a title opened just before ("Panchayat · Prime Video").
        if (item.key.endsWith("|@app") && item.title == null) return false
        if (item.title == null && item.intentUri == null && item.source != ContinueSource.WATCHED) return false
        if (item.service.packageName == YOUTUBE) return false    // videos, not shows
        return norm(item.name).length >= 2
    }

    /**
     * The show whose name equals [query] (normalised). Several with the same name: prefer the one
     * on [pkg]'s service (Crunchyroll → Anime), then TVmaze weight. None → null.
     */
    fun pickShow(query: String, shows: List<JsonObject>, pkg: String?): JsonObject? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val exact = shows.filter { norm(it.str("name").orEmpty()) == q }
        if (exact.size <= 1) return exact.firstOrNull()
        val onService = exact.filter { onService(it, pkg) }
        val pool = onService.ifEmpty { exact }
        return pool.maxByOrNull { it.int("weight") ?: 0 }
    }

    private fun onService(show: JsonObject, pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg == BackdropArt.CRUNCHYROLL && "Anime" in TvMazeParse.genres(show)) return true
        return TvMazeParse.availability(show).any { it.packageName == pkg }
    }

    /**
     * Of [shows] (with embedded episodes), the single one that has an episode named [episodeName]
     * (normalised; and with that number / season when known). Zero or several → null.
     */
    fun pickByEpisode(shows: List<JsonObject>, episodeName: String, season: Int?, episode: Int?): JsonObject? {
        val q = norm(episodeName)
        if (q.length < 4) return null
        val hits = shows.distinctBy { it.int("id") }.filter { s ->
            s.obj("_embedded")?.arr("episodes").orEmpty().mapNotNull { it as? JsonObject }.any { e ->
                norm(e.str("name").orEmpty()) == q &&
                    (episode == null || e.int("number") == episode) &&
                    (season == null || e.int("season") == season)
            }
        }
        return hits.singleOrNull()
    }

    /** The still of the episode called [name] (and numbered like it, when known) in [show]'s episode list. */
    fun episodeImage(show: JsonObject?, name: String, season: Int?, episode: Int?): String? {
        val q = norm(name)
        val eps = show?.obj("_embedded")?.arr("episodes").orEmpty().mapNotNull { it as? JsonObject }
        val e = eps.firstOrNull {
            norm(it.str("name").orEmpty()) == q && (episode == null || it.int("number") == episode) && (season == null || it.int("season") == season)
        } ?: return null
        val img = e.obj("image")
        return img?.str("original") ?: img?.str("medium")
    }

    /** Background image (main first), else the show's poster. */
    fun imageFor(show: JsonObject): Pair<String, PreparedBackdrop.Kind>? {
        TvMazeParse.backdrop(show)?.let { return it to PreparedBackdrop.Kind.BACKGROUND }
        val img = show.obj("image")
        return (img?.str("original") ?: img?.str("medium"))?.let { it to PreparedBackdrop.Kind.POSTER }
    }

    /** imdb ids of OMDb episode search hits whose title equals [episodeName]. */
    private val RESULT = Regex("""class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val TAGS = Regex("<[^>]+>")
    private val SHOW_PATTERNS = listOf(
        Regex("""^(.+?)\s+Season\s+\d+""", RegexOption.IGNORE_CASE),                 // "Black Clover Season 2, Episode 4 …"
        Regex("""[|–—-]\s*(.+?)\s+Season\s+\d+""", RegexOption.IGNORE_CASE),        // "… | Black Clover Season 2 Episode 4"
        Regex("""[–—-]\s*(.+?)\s+\d+x\d+\s*\|\s*TVmaze""", RegexOption.IGNORE_CASE), // "Day of Atonement - Black Clover 2x04 | TVmaze"
        Regex("""^(.+?)\s+(?:Episode|Ep\.?)\s+\d+""", RegexOption.IGNORE_CASE),       // "Black Clover Episode 174"
    )

    fun resultTitles(html: String): List<String> = RESULT.findAll(html).map {
        android.text.Html.fromHtml(TAGS.replace(it.groupValues[1], ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }.toList()

    /** Candidate show names from search-result titles, most frequent first; the episode itself excluded. */
    fun showNamesFromResults(titles: List<String>, episode: String): List<String> {
        val ne = norm(episode)
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in titles) for (p in SHOW_PATTERNS) {
            val raw = p.find(t)?.groupValues?.get(1)?.trim()?.trim('"', '\'', '“', '”', ',', ':') ?: continue
            // Strip a leading episode name ("Nacht's Day of Atonement | Black Clover" style handled by patterns).
            val name = raw.substringAfterLast(" | ").substringAfterLast(" - ").trim()
            val n = norm(name)
            if (n.isEmpty() || n == ne || ne.contains(n) || n.length > 60) continue
            val c = counts[n]
            counts[n] = (c?.first ?: name) to ((c?.second ?: 0) + 1)
        }
        return counts.values.sortedByDescending { it.second }.map { it.first }
    }

    fun omdbEpisodeIds(res: JsonObject?, episodeName: String): List<String> {
        val q = norm(episodeName)
        return res?.arr("Search").orEmpty().mapNotNull { it as? JsonObject }
            .filter { norm(it.str("Title").orEmpty()) == q }
            .mapNotNull { it.str("imdbID") }
    }

    /** A picture worth showing sharp full-screen: ≥ 480 px wide and roughly 16:9 (not a logo, not a poster). */
    fun sharpWorthy(w: Int, h: Int): Boolean = w >= 480 && h > 0 && (w.toFloat() / h) in 1.55f..1.95f

    /** One horizontal + vertical box-blur pass, in place, ARGB. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, 1, w)
        pass(tmp, px, h, w, r, w, 1)
    }

    /** Blur [n] lines of length [len]: element (line, i) is at line*lineStep + i*step. */
    private fun pass(src: IntArray, dst: IntArray, len: Int, n: Int, r: Int, step: Int, lineStep: Int) {
        val div = 2 * r + 1
        for (line in 0 until n) {
            val base = line * lineStep
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = src[base + k.coerceIn(0, len - 1) * step]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[base + (i - r).coerceIn(0, len - 1) * step]
                val inn = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private const val YOUTUBE = WatchedStore.YOUTUBE
}
