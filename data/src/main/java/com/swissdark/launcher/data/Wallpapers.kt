package com.swissdark.launcher.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.os.FileObserver
import android.util.LruCache
import com.swissdark.launcher.core.perf.Sections
import com.swissdark.launcher.core.perf.traced
import com.swissdark.launcher.domain.PreparedWallpaper
import com.swissdark.launcher.domain.TimeSlot
import com.swissdark.launcher.domain.Wallpaper
import com.swissdark.launcher.domain.WallpaperScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Four slots. Five minutes before a boundary the next wallpaper is decoded off the main thread
 * to 1920 × 1080, darkened once (a colour filter at decode, not a second full-screen layer) and
 * copied into a GPU-resident HARDWARE bitmap. At the boundary Home cross-fades in 600 ms.
 *
 * Files come from <external files>/wallpapers (pushed by setup.sh) and /photos (your own).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperSchedulerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : WallpaperScheduler {
    private val decodeThread = Dispatchers.IO.limitedParallelism(1)
    private val dirs = listOfNotNull(context.getExternalFilesDir("wallpapers"), context.getExternalFilesDir("photos"))
    private val rawFile = File(context.filesDir, "wallpaper.current.v2.raw")
    private val thumbs = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val _catalog = MutableStateFlow(scan())
    override val catalog: StateFlow<List<Wallpaper>> = _catalog
    private val _current = MutableStateFlow(readRaw() ?: PreparedWallpaper(slotFor(LocalTime.now()), null, null, 0f))
    override val current: StateFlow<PreparedWallpaper> = _current
    private var next: PreparedWallpaper? = null
    private var loop: Job? = null
    private var pending: Job? = null

    // setup.sh pushes files while the app may already be running: rescan when a folder changes.
    private val watchers = dirs.map { d ->
        object : FileObserver(d, CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                pending?.cancel()
                pending = scope.launch { delay(1_000); refresh() }
            }
        }
    }

    override fun slotFor(time: LocalTime): TimeSlot = TimeSlot.at(time)

    fun start() {
        watchers.forEach { it.startWatching() }
        _catalog.value = scan()
        scope.launch(decodeThread) {
            val want = wanted(slotFor(LocalTime.now()))
            val cur = _current.value
            if (cur.bitmap == null || cur.wallpaper?.id != want.first?.id || cur.dim != want.second || cur.slot != slotFor(LocalTime.now())) {
                prepare(slotFor(LocalTime.now()))?.let { publish(it, writeRaw = true) }
            }
        }
        loop?.cancel()
        loop = scope.launch { schedule() }
        scheduleAlarm()
    }

    /** Settings changed (wallpaper or dim for a slot): redo the current one now. */
    fun refresh() {
        _catalog.value = scan()
        scope.launch(decodeThread) {
            prepare(slotFor(LocalTime.now()))?.let { publish(it, writeRaw = true) }
            next = null
        }
    }

    /** Called by the 5-minutes-early alarm, or by the in-process loop. */
    fun prepareNext() {
        scope.launch(decodeThread) {
            val nb = nextBoundary(LocalDateTime.now())
            next = prepare(slotFor(nb.toLocalTime()))
        }
    }

    private suspend fun schedule() {
        while (true) {
            val now = LocalDateTime.now()
            val boundary = nextBoundary(now)
            val untilPrep = millisBetween(now, boundary) - 5 * 60_000L
            if (untilPrep > 0) delay(untilPrep)
            val upcoming = withContext(decodeThread) { next ?: prepare(slotFor(boundary.toLocalTime())) }
            val wait = millisBetween(LocalDateTime.now(), boundary)
            if (wait > 0) delay(wait)
            upcoming?.let { publish(it, writeRaw = true) }
            next = null
            scheduleAlarm()
        }
    }

    private fun publish(p: PreparedWallpaper, writeRaw: Boolean) {
        // The old bitmap is dropped here and freed once the 600 ms cross-fade lets go of it.
        _current.value = p
        if (writeRaw && p.bitmap != null) scope.launch(decodeThread) { writeRaw(p) }
    }

    private fun wanted(slot: TimeSlot): Pair<Wallpaper?, Float> {
        val s = settings.settings.value
        val id = s.wallpaperBySlot[slot.name]
        val cat = _catalog.value
        val wp = cat.firstOrNull { it.id == id } ?: cat.firstOrNull()
        return wp to (s.dimBySlot[slot.name] ?: 0.45f)
    }

    private fun prepare(slot: TimeSlot): PreparedWallpaper? = traced(Sections.WALLPAPER_DECODE) {
        val (wp, dim) = wanted(slot)
        if (wp == null) return@traced PreparedWallpaper(slot, null, null, dim)
        val sw = decode(File(wp.file), 1920, 1080, dim) ?: return@traced null
        val hw = sw.copy(Bitmap.Config.HARDWARE, false)
        if (hw != null) sw.recycle()
        PreparedWallpaper(slot, wp, hw ?: sw, dim)
    }

    private fun decode(file: File, w: Int, h: Int, dim: Float): Bitmap? = try {
        val src = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = dim > 0f
            // Cover-crop target: scale so both sides fill 1920 × 1080.
            val s = maxOf(w.toFloat() / info.size.width, h.toFloat() / info.size.height)
            dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }.let { b ->
            val cropped = if (b.width != w || b.height != h) {
                val x = ((b.width - w) / 2).coerceAtLeast(0); val y = ((b.height - h) / 2).coerceAtLeast(0)
                Bitmap.createBitmap(b, x, y, minOf(w, b.width), minOf(h, b.height)).also { if (it !== b) b.recycle() }
            } else b
            val out = if (cropped.isMutable) cropped else cropped.copy(Bitmap.Config.ARGB_8888, true).also { cropped.recycle() }
            if (dim > 0f) {
                Canvas(out).drawColor(android.graphics.Color.argb((dim * 255).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_OVER)
            }
            out
        }
    } catch (e: Exception) {
        android.util.Log.w("Wallpapers", "decode ${file.name} failed", e)
        null
    }

    // ---- L1/L2 for the first frame: raw ARGB pixels, memory-mapped ----

    private fun writeRaw(p: PreparedWallpaper) {
        val b = p.bitmap ?: return
        val sw = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val header = "${p.slot.name}|${p.wallpaper?.id}|${p.dim}|${sw.width}|${sw.height}".toByteArray()
        val tmp = File(rawFile.parentFile, rawFile.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.writeInt(header.size); raf.write(header)
            val buf = ByteBuffer.allocate(sw.byteCount)
            sw.copyPixelsToBuffer(buf); buf.flip()
            raf.channel.write(buf)
        }
        if (sw !== b) sw.recycle()
        tmp.renameTo(rawFile)
    }

    private fun readRaw(): PreparedWallpaper? {
        if (!rawFile.exists()) return null
        return try {
            RandomAccessFile(rawFile, "r").use { raf ->
                val hl = raf.readInt()
                val hb = ByteArray(hl); raf.readFully(hb)
                val parts = String(hb).split('|')
                val slot = TimeSlot.valueOf(parts[0])
                if (slot != slotFor(LocalTime.now())) return null
                val w = parts[3].toInt(); val h = parts[4].toInt()
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, (4 + hl).toLong(), raf.length() - 4 - hl)
                val sw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                sw.copyPixelsFromBuffer(map)
                val hw = sw.copy(Bitmap.Config.HARDWARE, false)
                if (hw != null) sw.recycle()
                val wp = _catalog.value.firstOrNull { it.id == parts[1] }
                PreparedWallpaper(slot, wp, hw ?: sw, parts[2].toFloat())
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- catalog, thumbnails ----

    private fun scan(): List<Wallpaper> = dirs.flatMap { d ->
        d.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }.map {
            val id = it.nameWithoutExtension
            val is4k = !id.startsWith("photo") && d.name == "wallpapers"
            Wallpaper(id, nameOf(id), it.absolutePath, is4k)
        }
    }

    override fun loadThumbnail(wallpaper: Wallpaper, width: Int, height: Int): Bitmap? {
        val key = "${wallpaper.id}@${width}x$height"
        thumbs.get(key)?.let { return it }
        val b = decode(File(wallpaper.file), width, height, 0f) ?: return null
        val hw = b.copy(Bitmap.Config.HARDWARE, false) ?: b
        if (hw !== b) b.recycle()
        thumbs.put(key, hw)
        return hw
    }

    override fun loadUndimmed(): Bitmap? {
        val (wp, _) = wanted(slotFor(LocalTime.now()))
        val f = wp?.file ?: return null
        val b = decode(File(f), 1920, 1080, 0f) ?: return null
        return b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
    }

    fun photosDir(): File? = context.getExternalFilesDir("photos")

    // ---- alarm: wakes a killed process 5 min early ----

    private fun scheduleAlarm() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val boundary = nextBoundary(LocalDateTime.now())
        val at = boundary.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 5 * 60_000L
        val pi = PendingIntent.getBroadcast(
            context, 7, Intent(ACTION_PREPARE).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Inexact is fine: a 2-minute window still lands before the boundary.
        am.setWindow(AlarmManager.RTC, at, 2 * 60_000L, pi)
    }

    companion object {
        const val ACTION_PREPARE = "com.swissdark.launcher.PREPARE_WALLPAPER"
        private val boundaries = intArrayOf(6, 12, 18, 23)

        fun nextBoundary(now: LocalDateTime): LocalDateTime {
            for (h in boundaries) {
                val b = now.toLocalDate().atTime(h, 0)
                if (b.isAfter(now)) return b
            }
            return now.toLocalDate().plusDays(1).atTime(6, 0)
        }

        private fun millisBetween(a: LocalDateTime, b: LocalDateTime) = java.time.Duration.between(a, b).toMillis()

        fun nameOf(id: String): String = id.replace('_', ' ').replace('-', ' ').trim()
            .replaceFirstChar { it.uppercase() }
            .replace("Milky way", "Milky Way")
            .replace("Alpine milky way", "Alpine Milky Way")

    }
}
