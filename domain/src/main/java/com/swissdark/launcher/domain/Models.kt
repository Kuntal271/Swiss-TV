package com.swissdark.launcher.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import java.time.LocalTime

/** Four wallpaper slots: morning 06–12, afternoon 12–18, evening 18–23, night 23–06. */
@Serializable
enum class TimeSlot(val label: String, val startHour: Int, val endHour: Int) {
    MORNING("Morning", 6, 12),
    AFTERNOON("Afternoon", 12, 18),
    EVENING("Evening", 18, 23),
    NIGHT("Night", 23, 6);

    val range: String get() = "%02d–%02d".format(startHour, endHour)

    companion object {
        fun at(time: LocalTime): TimeSlot = when (time.hour) {
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> NIGHT
        }
    }
}

/** Package + activity of a launchable app, e.g. "com.netflix.ninja/.MainActivity". */
@Serializable
@JvmInline
value class AppId(val component: String) {
    val packageName: String get() = component.substringBefore('/')
}

@Immutable
@Serializable
data class AppEntry(
    val id: AppId,
    val label: String,
    val icon: String,                   // Tabler glyph id, see :core:design Glyphs
    val pinned: Boolean = false,
)

@Serializable
enum class MediaType { MOVIE, TV }

@Immutable
@Serializable
data class TitleRef(val tmdbId: Int, val type: MediaType, val name: String)

@Serializable
enum class ContinueSource { WATCH_NEXT, HISTORY, APP, WATCHED }

@Immutable
@Serializable
data class ContinueItem(
    val key: String,
    val title: TitleRef?,
    val service: AppId,
    val serviceName: String,
    val name: String,
    val meta: String,
    val progress: Float?,               // null for new titles
    val artKey: String,                 // image url, or "banner:<package>"
    val source: ContinueSource,
    val intentUri: String? = null,      // deep link from Watch Next, if any
    val season: Int? = null,
    val episode: Int? = null,
    val minutesLeft: Int? = null,
    val subtitle: String? = null,       // WATCHED: episode name (or the YouTube channel)
)

@Immutable
@Serializable
data class HeroInfo(val label: String, val title: String, val meta: String, val progress: Float?)

@Immutable
@Serializable
data class HomeSnapshot(
    val version: Long,
    val slot: TimeSlot,
    val upNext: List<ContinueItem>,     // max 3
    val apps: List<AppEntry>,           // max 6, habit-ordered
    val hero: HeroInfo?,
    val habitHour: Int,
) {
    companion object {
        val EMPTY = HomeSnapshot(0, TimeSlot.EVENING, emptyList(), emptyList(), null, 21)
    }
}

@Serializable
enum class Kind { STREAM, FREE, ADS, RENT, BUY }

@Immutable
@Serializable
data class Availability(
    val providerId: Int,
    val providerName: String,
    val kind: Kind,
    val inMyPlan: Boolean,
    val priceInr: Int?,
    val packageName: String?,
    val brandColor: Long,
)

@Immutable
@Serializable
data class Person(val id: Int, val name: String, val role: String, val department: String, val knownFor: Int = 0)

@Immutable
@Serializable
data class Episode(val season: Int, val number: Int, val name: String, val runtimeMin: Int?, val stillUrl: String?)

@Immutable
@Serializable
data class TitleDetails(
    val ref: TitleRef,
    val year: String?,
    val runtimeMin: Int?,
    val seasons: Int?,
    val ageRating: String?,
    val synopsis: String,
    val genres: List<String>,
    val language: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val cast: List<Person>,
    val trailerKey: String?,
    val availability: List<Availability>,
    val nextEpisodes: List<Episode> = emptyList(),
    val kidsSafe: Boolean = false,
    val score: String? = null,                         // e.g. "IMDb 9.0"
    val source: String = "TMDB",                       // credit line: where titles and availability came from
)

@Immutable
sealed interface SearchHit {
    val key: String

    @Immutable data class App(val app: AppEntry) : SearchHit { override val key get() = "a:" + app.id.component }

    @Immutable data class Title(
        val ref: TitleRef,
        val posterUrl: String?,
        val backdropUrl: String?,
        val meta: String,
        val availability: List<Availability>?,   // null = not fetched yet
        val kidsSafe: Boolean,
        val popularity: Double = 0.0,
    ) : SearchHit { override val key get() = "t:" + ref.type + ref.tmdbId }

    @Immutable data class PersonHit(val person: Person) : SearchHit { override val key get() = "p:" + person.id }

    @Immutable data class Recent(val query: String, val kind: String) : SearchHit { override val key get() = "r:$query" }
}

@Immutable
data class TvInput(
    val id: String,
    val label: String,
    val detail: String,
    val connected: Boolean,
    val isTuner: Boolean,
)

@Immutable
@Serializable
data class Wallpaper(val id: String, val name: String, val file: String, val is4k: Boolean = true)

@Immutable
@Serializable
data class LauncherSettings(
    val pinned: List<String> = emptyList(),            // package names, in order
    val hidden: List<String> = DefaultHidden,          // package names
    val customOrder: List<String> = emptyList(),       // All apps order (packages) once the user moves something
    val myServices: List<String> = listOf("netflix", "prime", "jiohotstar"),
    val wallpaperBySlot: Map<String, String> = mapOf(
        "MORNING" to "tatra_dawn",
        "AFTERNOON" to "storm_hills",
        "EVENING" to "shanghai_night",
        "NIGHT" to "alpine_milky_way",
    ),
    val dimBySlot: Map<String, Float> = mapOf(
        "MORNING" to 0.5f, "AFTERNOON" to 0.4f, "EVENING" to 0.45f, "NIGHT" to 0.3f,
    ),
    val showUpNext: Boolean = true,
    val removedUpNext: List<String> = emptyList(),
    val tmdbKey: String = "",
    val lastInput: String? = null,
    val sleepMinutes: Int = 0,
    val searchIntentApps: Map<String, Boolean> = emptyMap(),
    val showLatencyOverlay: Boolean = false,
    // Appended last: protobuf field numbers follow declaration order, so old settings.pb still decodes.
    val omdbKey: String = "",                          // OMDb key: titles without a working TMDB key
    val iconPack: String = "",                         // icon pack package; "" = the apps' own logos
)

/** TCL utilities that clutter the app list. Hidden, not disabled; unhide in Launcher settings › Apps. */
val DefaultHidden = listOf(
    "com.tcl.partnercustomizer", "com.tcl.suspension", "com.tcl.waterfall.overseas", "com.tcl.guard",
    "com.tcl.smartalexa", "com.tcl.dashboard", "com.tcl.exhibit", "com.tcl.ocean.instructions",
)

val EmptyApps: ImmutableList<AppEntry> = persistentListOf()

fun durationText(min: Int): String = if (min >= 60) "${min / 60}h %02dm".format(min % 60) else "$min min"

/** Hero text for an Up next item: shown for whichever card has focus. */
fun ContinueItem.hero(): HeroInfo {
    val label = when (source) {
        ContinueSource.WATCH_NEXT -> "Continue · $serviceName"
        ContinueSource.HISTORY -> "Back to · $serviceName"
        ContinueSource.APP -> "Last used"
        ContinueSource.WATCHED -> "Continue · $serviceName"
    }
    val left = minutesLeft
    val meta = when {
        season != null && episode != null -> "Season $season · Episode $episode" + (left?.let { " · ${durationText(it)} left" } ?: "")
        left != null -> "${durationText(left)} left"
        else -> this.meta
    }.let { m -> if (source == ContinueSource.WATCHED && subtitle != null) "$subtitle · $m" else m }
    return HeroInfo(label, name, meta, progress)
}
