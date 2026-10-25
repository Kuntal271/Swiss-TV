package com.swissdark.launcher.feature.title

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Art
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Footer
import com.swissdark.launcher.core.design.Frame
import com.swissdark.launcher.core.design.Header
import com.swissdark.launcher.core.design.Initial
import com.swissdark.launcher.core.design.KeyHint
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.OutlineButton
import com.swissdark.launcher.core.design.PosterCard
import com.swissdark.launcher.core.design.PrimaryButton
import com.swissdark.launcher.core.design.ProgressBar
import com.swissdark.launcher.core.design.RuleRow
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TDraw
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.rememberFocusScreen
import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.Kind
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import com.swissdark.launcher.domain.durationText

private fun backHandler(onBack: () -> Unit): (KeyEvent) -> Boolean = { e ->
    if (e.keyCode == KeyEvent.KEYCODE_BACK) {
        if (e.action == KeyEvent.ACTION_UP) onBack()
        true
    } else false
}

@Immutable
class UpNextActions(val resume: () -> Unit, val remove: () -> Unit, val openEpisode: (Int) -> Unit, val back: () -> Unit)

/** Up next → title detail: resume, remove, next episodes. */
@Composable
fun UpNextTitleScreen(
    item: ContinueItem,
    position: Int,
    total: Int,
    details: TitleDetails?,
    deviceName: String,
    actions: UpNextActions,
    active: Boolean,
) {
    val eps = details?.nextEpisodes.orEmpty()
        .filter { item.season == null || item.episode == null || it.season > item.season!! || (it.season == item.season && it.number > item.episode!!) }
        .take(3)
    val focus = rememberFocusScreen(2 + eps.size, active = active)
    focus.onClick = { i ->
        when (i) {
            0 -> actions.resume()
            1 -> actions.remove()
            else -> actions.openEpisode(i - 2)
        }
    }
    focus.onOtherKey = backHandler(actions.back)
    val now by rememberMinute()

    Box(Modifier.fillMaxSize().background(Swiss.Ground)) {
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                Header(deviceName.uppercase(), now.format(Fmt.clock), "Up next · %02d of %02d".format(position + 1, total), item.serviceName)
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    Column(Modifier.width(Swiss.span(5)).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            val label = when {
                                item.season != null && item.episode != null -> "Season ${item.season} · Episode ${item.episode}"
                                item.title?.type == MediaType.MOVIE -> "Film"
                                item.title?.type == MediaType.TV -> "Series"
                                item.source == com.swissdark.launcher.domain.ContinueSource.WATCHED -> "Continue watching"
                                else -> "Last used"
                            }
                            Label(label, color = Swiss.Ink)
                            T(item.name, SwissType.Title84, maxLines = 3)
                            T(details?.synopsis?.takeIf { it.isNotBlank() } ?: item.meta, SwissType.Body26, maxLines = 3)
                            val p = item.progress
                            if (p != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProgressBar(p, Swiss.Ink)
                                val left = item.minutesLeft
                                if (left != null) {
                                    val total2 = (left / (1f - p)).toInt()
                                    T("${total2 - left} of $total2 min · ${durationText(left)} left", SwissType.Meta)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                PrimaryButton(focus, 0, (if (item.progress != null) "Resume in " else "Open in ") + item.serviceName)
                                OutlineButton(focus, 1, "Remove")
                            }
                        }
                        if (eps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Label("Next episodes")
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                for ((k, e) in eps.withIndex()) {
                                    PosterCard(
                                        focus, 2 + k, e.stillUrl ?: details?.backdropUrl, 130.dp,
                                        "E${e.number} · ${e.name}",
                                        (if (k == 0) "Next · " else "") + (e.runtimeMin?.let { "$it min" } ?: ""),
                                        Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - eps.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                    val backdrop = details?.backdropUrl
                    if (backdrop == null && item.artKey.startsWith("banner:")) {
                        // A 320 × 180 app banner blown up to 1060 × 760 looks broken: show it at banner
                        // proportions on a panel instead.
                        Box(
                            Modifier.weight(1f).fillMaxHeight().background(Swiss.Panel).border(2.dp, Swiss.Rule),
                            contentAlignment = Alignment.Center,
                        ) {
                            Art(item.artKey, 640, 360, Modifier.size(640.dp, 360.dp))
                        }
                    } else Box(Modifier.weight(1f).fillMaxHeight()) {
                        Art(backdrop ?: item.artKey, 1060, 760, Modifier.fillMaxSize())
                    }
                }
                Footer(
                    "If ${item.serviceName} can’t open the ${if (item.title?.type == MediaType.TV || item.season != null) "episode" else "title"} directly, the app opens instead",
                    listOf(KeyHint("OK", if (item.progress != null) "Resume" else "Open"), KeyHint("Back", "Home")),
                )
            }
        }
    }
}

@Immutable
class TitleActions(
    val watch: (Availability) -> Unit,
    val trailer: (String) -> Unit,
    val save: () -> Unit,
    val back: () -> Unit,
)

/** Title page: what it is, who is in it, and where to watch it in India. */
@Composable
fun TitleDetailsScreen(
    ref: TitleRef,
    details: TitleDetails?,
    failed: Boolean,
    from: String,
    saved: Boolean,
    deviceName: String,
    actions: TitleActions,
    active: Boolean,
) {
    val avail = details?.availability.orEmpty()
    val stream = avail.filter { it.kind == Kind.STREAM || it.kind == Kind.FREE || it.kind == Kind.ADS }
    val rentBuy = avail.filter { it.kind == Kind.RENT || it.kind == Kind.BUY }
    val rows = stream + rentBuy
    val best = stream.firstOrNull { it.inMyPlan && it.kind == Kind.STREAM } ?: stream.firstOrNull { it.kind != Kind.STREAM } ?: rows.firstOrNull()
    val buttons = buildList {
        if (best != null) add("watch")
        if (details?.trailerKey != null) add("trailer")
        add("save")
    }
    val focus = rememberFocusScreen(buttons.size + rows.size, active = active)
    focus.onClick = { i ->
        if (i < buttons.size) when (buttons[i]) {
            "watch" -> best?.let(actions.watch)
            "trailer" -> details?.trailerKey?.let(actions.trailer)
            "save" -> actions.save()
        } else rows.getOrNull(i - buttons.size)?.let(actions.watch)
    }
    focus.onOtherKey = backHandler(actions.back)
    val now by rememberMinute()
    val kind = if (ref.type == MediaType.MOVIE) "Film" else "Series"

    Box(Modifier.fillMaxSize().background(Swiss.Ground)) {
        if (details?.backdropUrl != null) {
            Art(details.backdropUrl, 1920, 1080, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
        }
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                Header(deviceName.uppercase(), now.format(Fmt.clock), from, "Title")
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    Column(Modifier.width(Swiss.span(6)).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            val bits = listOfNotNull(
                                kind, details?.year,
                                details?.runtimeMin?.takeIf { ref.type == MediaType.MOVIE }?.let { durationText(it) },
                                details?.seasons?.takeIf { ref.type == MediaType.TV }?.let { if (it == 1) "1 season" else "$it seasons" },
                                details?.ageRating?.takeIf { it.isNotBlank() },
                            )
                            Label(bits.joinToString(" · "), color = Swiss.Ink)
                            T(details?.ref?.name ?: ref.name, SwissType.Title92, maxLines = 3)
                            val syn = when {
                                details != null -> buildString {
                                    append(details.synopsis.ifBlank { "No synopsis on TMDB yet." })
                                    if (details.genres.isNotEmpty()) append(" Genres: ").append(details.genres.joinToString(", ")).append('.')
                                    if (details.language != null) append(" Original language: ").append(details.language).append('.')
                                }
                                failed -> "Couldn’t load this title. Check the network, or add a TMDB key with setup.sh."
                                else -> "Loading…"
                            }
                            T(syn, SwissType.Body26, Modifier.widthIn(max = 820.dp), maxLines = 5)
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                for ((k, b) in buttons.withIndex()) when (b) {
                                    "watch" -> PrimaryButton(focus, k, (if (best?.kind == Kind.RENT || best?.kind == Kind.BUY) "Open " else "Watch on ") + (best?.providerName ?: ""))
                                    "trailer" -> OutlineButton(focus, k, "Trailer")
                                    "save" -> OutlineButton(focus, k, if (saved) "Saved" else "Save for later")
                                }
                            }
                        }
                        val cast = details?.cast.orEmpty()
                        if (cast.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Label("Cast & crew")
                            Row(Modifier.widthIn(max = 820.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                for (p in cast) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Initial(p.name, Modifier.fillMaxWidth().height(96.dp), SwissType.Row30)
                                    T(p.name, SwissType.Tile, maxLines = 1)
                                    T(p.role, SwissType.Meta, maxLines = 1)
                                }
                                repeat(4 - cast.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Box(Modifier.weight(1f))
                    Column(
                        Modifier.width(Swiss.span(5)).fillMaxHeight().background(Swiss.Panel).border(2.dp, Swiss.Rule).padding(horizontal = 36.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Label("Where to watch in India", color = Swiss.Ink)
                        if (rows.isEmpty()) {
                            T(if (details == null) "…" else "Not streaming, renting or selling in India right now.", SwissType.Body, Modifier.padding(top = 14.dp))
                        }
                        if (stream.isNotEmpty()) Label("Stream", Modifier.padding(top = 14.dp), style = SwissType.Label)
                        for ((k, a) in stream.withIndex()) WatchRow(focus, buttons.size + k, a, last = k == stream.lastIndex && rentBuy.isEmpty())
                        if (rentBuy.isNotEmpty()) Label("Rent or buy", Modifier.padding(top = 18.dp), style = SwissType.Label)
                        for ((k, a) in rentBuy.withIndex()) WatchRow(focus, buttons.size + stream.size + k, a, last = k == rentBuy.lastIndex)
                        Box(Modifier.weight(1f))
                        T("Updated daily. Prices are set by each service and may differ in the app.", SwissType.Meta)
                    }
                }
                Footer("Titles and artwork: TMDB · Where to watch: JustWatch", listOf(KeyHint("OK", "Open"), KeyHint("Back", "Results")))
            }
        }
    }
}

@Composable
private fun WatchRow(focus: com.swissdark.launcher.core.focus.FocusScreenState, index: Int, a: Availability, last: Boolean) {
    val (detail, action, strong) = when (a.kind) {
        Kind.STREAM -> if (a.inMyPlan) Triple("In your plan", "Play", true) else Triple("Needs a ${a.providerName} plan", "—", false)
        Kind.FREE -> Triple("Free", "Play", true)
        Kind.ADS -> Triple("Free with ads", "Play", true)
        Kind.RENT -> Triple("Rent ${a.priceInr?.let { "₹$it" } ?: "· price in app"} · 48 h to finish", "Rent", true)
        Kind.BUY -> Triple("Buy ${a.priceInr?.let { "₹$it" } ?: "· price in app"} · keep forever", "Buy", false)
    }
    RuleRow(focus, index, bottomRule = last, vertical = 16.dp) { c ->
        Box(Modifier.size(88.dp, 50.dp).background(Color(a.brandColor)).border(1.dp, Color(0xFF333338)))
        Box(Modifier.width(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TDraw(a.providerName, SwissType.Row32, c.ink, maxLines = 1)
            TDraw(detail, SwissType.Meta, if (strong) c.ink else c.muted, maxLines = 1)
        }
        TDraw(action, SwissType.Card, if (strong) c.ink else c.muted, maxLines = 1)
    }
}

