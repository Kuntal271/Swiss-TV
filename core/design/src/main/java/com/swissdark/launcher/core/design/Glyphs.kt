package com.swissdark.launcher.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Tabler Icons (MIT), outline, 24 grid, drawn with a 2.25 stroke so they hold up at 3 m. */
object Glyphs {
    /** Default on-screen size of a UI glyph. */
    val SIZE = 34.dp
    private const val STROKE = 2.25f

    private val paths: Map<String, Array<String>> = mapOf(
        "netflix" to arrayOf("M4 6a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2l0 -12", "M8 4l0 16", "M16 4l0 16", "M4 8l4 0", "M4 16l4 0", "M4 12l16 0", "M16 8l4 0", "M16 16l4 0"),
        "youtube" to arrayOf("M7 4v16l13 -8l-13 -8"),
        "prime" to arrayOf("M3 9a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2l0 -9", "M16 3l-4 4l-4 -4"),
        "jiohotstar" to arrayOf("M12 17.75l-6.172 3.245l1.179 -6.873l-5 -4.867l6.9 -1l3.086 -6.253l3.086 6.253l6.9 1l-5 4.867l1.179 6.873l-6.158 -3.245"),
        "spotify" to arrayOf("M3 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0", "M13 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0", "M9 17v-13h10v13", "M9 8h10"),
        "settings" to arrayOf("M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065", "M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0"),
        "youtube_kids" to arrayOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M9 10l.01 0", "M15 10l.01 0", "M9.5 15a3.5 3.5 0 0 0 5 0", "M12 3a2 2 0 0 0 0 4"),
        "youtube_music" to arrayOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M7 12a5 5 0 0 1 5 -5", "M12 17a5 5 0 0 0 5 -5"),
        "appletv" to arrayOf("M3 9a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2l0 -9", "M16 3l-4 4l-4 -4", "M15 7v13", "M18 15v.01", "M18 12v.01"),
        "files" to arrayOf("M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2"),
        "playstore" to arrayOf("M6.331 8h11.339a2 2 0 0 1 1.977 2.304l-1.255 8.152a3 3 0 0 1 -2.966 2.544h-6.852a3 3 0 0 1 -2.965 -2.544l-1.255 -8.152a2 2 0 0 1 1.977 -2.304", "M9 11v-5a3 3 0 0 1 6 0v5"),
        "kodi" to arrayOf("M4 4h6v6h-6l0 -6", "M14 4h6v6h-6l0 -6", "M4 14h6v6h-6l0 -6", "M14 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
        "plex" to arrayOf("M3 7a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v2a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-2", "M3 15a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v2a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3l0 -2", "M7 8l0 .01", "M7 16l0 .01"),
        "sonyliv" to arrayOf("M18.364 19.364a9 9 0 1 0 -12.728 0", "M15.536 16.536a5 5 0 1 0 -7.072 0", "M11 13a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
        "vlc" to arrayOf("M21 17.998v-.5l-8.13 -14.99a1 1 0 0 0 -1.74 0l-8.13 14.989v.5c0 1.659 4.03 3.003 9 3.003s9 -1.344 9 -3.002"),
        "zee5" to arrayOf("M15 10l4.553 -2.276a1 1 0 0 1 1.447 .894v6.764a1 1 0 0 1 -1.447 .894l-4.553 -2.276v-4", "M3 8a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2l0 -8"),
        "world" to arrayOf("M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0", "M3.6 9h16.8", "M3.6 15h16.8", "M11.5 3a17 17 0 0 0 0 18", "M12.5 3a17 17 0 0 1 0 18"),
        "gamepad" to arrayOf("M2 8a2 2 0 0 1 2 -2h16a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2z", "M6 12h4m-2 -2v4", "M15 11l0 .01", "M18 13l0 .01"),
        "apps" to arrayOf("M4 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M4 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 7l6 0", "M17 4l0 6"),
        "play_circle" to arrayOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M10 9l5 3l-5 3z"),
        "send" to arrayOf("M10 14l11 -11", "M21 3l-6.5 18a.55 .55 0 0 1 -1 0l-3.5 -7l-7 -3.5a.55 .55 0 0 1 0 -1l18 -6.5"),
        "cast" to arrayOf("M3 19l.01 0", "M7 19a4 4 0 0 0 -4 -4", "M11 19a8 8 0 0 0 -8 -8", "M15 19h3a3 3 0 0 0 3 -3v-8a3 3 0 0 0 -3 -3h-12a3 3 0 0 0 -2.8 2"),
        "antenna" to arrayOf("M20 4v8", "M16 4.5v7", "M12 5v16", "M8 5.5v5", "M4 6v4", "M20 8h-16"),
        "photo" to arrayOf("M15 8h.01", "M3 6a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12z", "M3 16l5 -5c.928 -.893 2.072 -.893 3 0l5 5", "M14 14l1 -1c.928 -.893 2.072 -.893 3 0l3 3"),
        "shield" to arrayOf("M12 3a12 12 0 0 0 8.5 3a12 12 0 0 1 -8.5 15a12 12 0 0 1 -8.5 -15a12 12 0 0 0 8.5 -3"),
        "info" to arrayOf("M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0", "M12 9h.01", "M11 12h1v4h1"),
        "mic" to arrayOf("M9 5a3 3 0 0 1 3 -3a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3a3 3 0 0 1 -3 -3z", "M5 10a7 7 0 0 0 14 0", "M8 21l8 0", "M12 17l0 4"),
        "dashboard" to arrayOf("M5 4h4a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1", "M5 16h4a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1", "M15 12h4a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1", "M15 4h4a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1"),
        "search" to arrayOf("M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M21 21l-6 -6"),
        "grid" to arrayOf("M4 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M4 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z"),
        "monitor" to arrayOf("M3 5a1 1 0 0 1 1 -1h16a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-10z", "M7 20h10", "M9 16v4", "M15 16v4"),
        "adjustments" to arrayOf("M12 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M4 6l8 0", "M16 6l4 0", "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M4 12l2 0", "M10 12l10 0", "M15 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M4 18l11 0", "M19 18l1 0"),
        "wifi" to arrayOf("M12 18l.01 0", "M9.172 15.172a4 4 0 0 1 5.656 0", "M6.343 12.343a8 8 0 0 1 11.314 0", "M3.515 9.515c4.686 -4.687 12.284 -4.687 17 0"),
        "wifi_off" to arrayOf("M12 18l.01 0", "M9.172 15.172a4 4 0 0 1 5.656 0", "M6.343 12.343a7.963 7.963 0 0 1 3.864 -2.14m4.163 .155a7.965 7.965 0 0 1 3.287 2", "M3.515 9.515a12 12 0 0 1 3.544 -2.455m3.101 -.92a12 12 0 0 1 10.325 3.374", "M3 3l18 18"),
        "ethernet" to arrayOf("M9 15l6 -6", "M11 6l.463 -.536a5 5 0 0 1 7.071 7.072l-.534 .464", "M13 18l-.397 .534a5.068 5.068 0 0 1 -7.127 0a4.972 4.972 0 0 1 0 -7.071l.524 -.463"),
    )

    private val cache = HashMap<String, ImageVector>()

    fun has(id: String) = id in paths

    fun get(id: String): ImageVector = cache.getOrPut(id) {
        val p = paths[id] ?: paths.getValue("apps")
        ImageVector.Builder(id, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in p) addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}

