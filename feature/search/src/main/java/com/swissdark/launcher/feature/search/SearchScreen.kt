package com.swissdark.launcher.feature.search

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Art
import com.swissdark.launcher.core.design.Motion
import com.swissdark.launcher.core.design.enter
import com.swissdark.launcher.core.design.rememberAppear
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.Chip
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Footer
import com.swissdark.launcher.core.design.Frame
import com.swissdark.launcher.core.design.Header
import com.swissdark.launcher.core.design.AppTile
import com.swissdark.launcher.core.design.NavChip
import com.swissdark.launcher.core.design.TDraw
import com.swissdark.launcher.core.design.Initial
import com.swissdark.launcher.core.design.KeyHint
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.PosterCard
import com.swissdark.launcher.core.design.RuleRow
import com.swissdark.launcher.core.design.ServiceDot
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TabText
import com.swissdark.launcher.core.design.bottomRule
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.FocusScreenState
import com.swissdark.launcher.core.focus.FocusStyle
import com.swissdark.launcher.core.focus.focusItem
import com.swissdark.launcher.core.focus.focusScroll
import com.swissdark.launcher.core.focus.focusViewport
import com.swissdark.launcher.core.focus.rememberFocusScreen
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.Kind
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.PreparedWallpaper
import com.swissdark.launcher.domain.SearchEngine
import com.swissdark.launcher.domain.SearchHit
import kotlinx.coroutines.flow.catch

@Immutable
class SearchActions(
    val launchApp: (AppEntry) -> Unit,
    val openTitle: (SearchHit.Title, String) -> Unit,     // hit, "from" text for the title header
    val listen: (onPartial: (String) -> Unit, onFinal: (String) -> Unit, onEnd: () -> Unit) -> Unit,
    val stopListening: () -> Unit,
    val back: () -> Unit,
)

private enum class Filter(val label: String) { MINE("My services"), ALL("Everything"), FREE("Free"), KIDS("Kids-safe") }

private val genres = listOf("Thriller", "Comedy", "Drama", "Crime", "Documentary", "Kids")

private fun List<Availability>?.mine(): Availability? =
    this?.firstOrNull { it.inMyPlan && it.kind == Kind.STREAM } ?: this?.firstOrNull { it.kind == Kind.FREE || it.kind == Kind.ADS }

private fun SearchHit.Title.serviceLine(): Pair<String, Boolean> {
    val a = availability ?: return "Checking where to watch…" to false
    val m = a.mine()
    if (m != null) return (when (m.kind) { Kind.FREE, Kind.ADS -> "Free"; else -> "Stream" } + " · " + m.providerName) to true
    val r = a.firstOrNull { it.kind == Kind.RENT } ?: a.firstOrNull { it.kind == Kind.BUY }
    if (r != null) return ((if (r.kind == Kind.RENT) "Rent" else "Buy") + " · " + r.providerName) to true
    return "Not on your services" to false
}

/** Search: apps and history answer on every keystroke, TMDB once typing pauses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    engine: SearchEngine,
    myServiceNames: List<String>,
    hasTmdb: Boolean,
    deviceName: String,
    wallpaper: PreparedWallpaper,
    actions: SearchActions,
    active: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var browse by remember { mutableStateOf<String?>(null) }          // genre or person label
    var browseHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var remote by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var withAvail by remember { mutableStateOf<Map<String, SearchHit.Title>>(emptyMap()) }
    var filter by remember { mutableStateOf(Filter.MINE) }
    var daily by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<SearchHit.Title>>(emptyList()) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val browsePerson = remember { IntArray(1) }
    val recent by remember(engine) { engine.recent() }.collectAsState(emptyList())
    val now by rememberMinute()
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Wave 1: same frame as the keystroke.
    val local = remember(query) { if (query.isBlank()) emptyList() else engine.local(query) }
    // Wave 2: debounced inside remote(); a new keystroke cancels the older request.
    LaunchedEffect(query, browse) {
        error = null
        if (browse != null || query.isBlank()) { remote = emptyList(); return@LaunchedEffect }
        engine.remote(query).catch { error = "Online search unavailable"; emit(emptyList()) }.collect { remote = it }
    }
    LaunchedEffect(daily, hasTmdb) {
        trending = try { engine.trending(daily) } catch (_: Exception) { emptyList() }
    }
    // Availability after the list is drawn: posters never wait for it.
    val titlesNow = (if (browse != null) browseHits else remote).filterIsInstance<SearchHit.Title>().take(18) +
        (if (query.isBlank() && browse == null) trending.take(5) else emptyList())
    LaunchedEffect(titlesNow.map { it.key }) {
        val missing = titlesNow.filter { it.key !in withAvail }
        if (missing.isNotEmpty()) try {
            val got = engine.availability(missing)
            withAvail = withAvail + got.associateBy { it.key }
        } catch (_: Exception) {}
    }
    fun resolved(t: SearchHit.Title) = withAvail[t.key] ?: t

    val appHits = local.filterIsInstance<SearchHit.App>().map { it.app }.take(4)
    val mode = when {
        browse != null -> 2
        query.isBlank() -> 0
        appHits.isNotEmpty() -> 1
        else -> 2
    }
    val allTitles = (if (browse != null) browseHits else remote + local).filterIsInstance<SearchHit.Title>().distinctBy { it.key }.map { resolved(it) }
    val people = (if (browse != null) emptyList() else remote).filterIsInstance<SearchHit.PersonHit>().take(3)
    val filtered = when (filter) {
        Filter.MINE -> allTitles.filter { it.availability.mine() != null }
        Filter.ALL -> allTitles
        Filter.FREE -> allTitles.filter { a -> a.availability?.any { it.kind == Kind.FREE || it.kind == Kind.ADS } == true }
        Filter.KIDS -> allTitles.filter { it.kidsSafe }
    }.take(12)
    val typingTitles = allTitles.filter { it.availability.mine() != null }.take(3)
    val recentShown = recent.take(4)
    val trendShown = trending.take(5).map { resolved(it) }

    // Index layout: 0 = field, 1 = mic, then per mode.
    val count = 2 + when (mode) {
        0 -> recentShown.size + genres.size + 2 + trendShown.size
        1 -> appHits.size + typingTitles.size
        else -> 4 + filtered.size + people.size
    }
    val focus = rememberFocusScreen(count, initial = 0, active = active)
    val fromLabel = browse ?: query

    fun clear() {
        query = ""; browse = null; browseHits = emptyList(); remote = emptyList(); focus.focus(0)
    }

    fun startVoice() {
        listening = true
        actions.listen({ query = it; browse = null }, { query = it; browse = null; engine.remember(it, "Voice") }, { listening = false })
    }

    focus.onClick = click@{ i ->
        if (i == 0) {
            fieldFocus.requestFocus(); keyboard?.show(); return@click
        }
        if (i == 1) {
            if (listening) { actions.stopListening(); listening = false } else startVoice()
            return@click
        }
        var k = i - 2
        when (mode) {
            0 -> {
                if (k < recentShown.size) { query = recentShown[k].query; return@click }
                k -= recentShown.size
                if (k < genres.size) {
                    val g = genres[k]
                    browse = g; browseHits = emptyList(); filter = Filter.ALL
                    return@click
                }
                k -= genres.size
                if (k < 2) { daily = k == 1; return@click }
                k -= 2
                trendShown.getOrNull(k)?.let { actions.openTitle(it, "Trending · ${if (daily) "today" else "this week"}") }
            }
            1 -> {
                if (k < appHits.size) { engine.remember(query, "App"); actions.launchApp(appHits[k]); return@click }
                k -= appHits.size
                typingTitles.getOrNull(k)?.let { engine.remember(query, if (it.ref.type == MediaType.MOVIE) "Film" else "Series"); actions.openTitle(it, "From search “$query”") }
            }
            else -> {
                if (k < 4) { filter = Filter.entries[k]; return@click }
                k -= 4
                if (k < filtered.size) {
                    val t = filtered[k]
                    if (browse == null) engine.remember(query, if (t.ref.type == MediaType.MOVIE) "Film" else "Series")
                    actions.openTitle(t, (if (t.ref.type == MediaType.MOVIE) "Film" else "Series") + " · from " + (if (browse != null) browse!! else "search “$query”"))
                    return@click
                }
                k -= filtered.size
                people.getOrNull(k)?.let { p ->
                    engine.remember(p.person.name, "Person")
                    browse = p.person.name; browseHits = emptyList(); filter = Filter.ALL
                    browsePerson[0] = p.person.id
                }
            }
        }
    }
    focus.onLongClick = { i ->
        val k = i - 2
        if (mode == 0 && k in recentShown.indices) engine.forget(recentShown[k].query)
        if (i == 1) startVoice()
    }
    focus.onOtherKey = { e ->
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) {
                    if (listening) { actions.stopListening(); listening = false }
                    else if (query.isNotEmpty() || browse != null) clear() else actions.back()
                }
                true
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (e.action == KeyEvent.ACTION_UP) startVoice()
                true
            }
            else -> false
        }
    }

    // Genre / person browsing: TMDB discover or credits.
    LaunchedEffect(browse) {
        val b = browse ?: return@LaunchedEffect
        browseHits = try {
            if (b in genres) engine.byGenre(b) else engine.byPerson(browsePerson[0])
        } catch (_: Exception) {
            error = "Couldn’t load $b"; emptyList()
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Backdrop(wallpaper, totalDim = 0.8f)
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                val headerC = when {
                    listening -> "Listening · mic on remote"
                    mode == 0 -> "Nothing typed yet"
                    mode == 1 -> "${appHits.size} apps · ${typingTitles.size} titles"
                    else -> "${filtered.size} results" + if (filter == Filter.MINE) " on your services" else ""
                }
                Header(deviceName.uppercase(), now.format(Fmt.clock), headerC, "Search")

                // The field
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(if (mode == 1) "Say or type" else "Search films, series, people and apps")
                    Row(
                        Modifier.fillMaxWidth().bottomRule(4.dp, Swiss.Focus).padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val style = if (mode == 1) SwissType.Query120 else SwissType.Query96
                        Box(Modifier.weight(1f).focusItem(focus, 0, FocusStyle.SELF)) {
                            BasicTextField(
                                value = browse ?: query,
                                onValueChange = { query = it; browse = null },
                                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                                singleLine = true,
                                textStyle = style,
                                cursorBrush = SolidColor(Swiss.Focus),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }),
                            )
                            if (query.isEmpty() && browse == null) T("Say or type…", style, color = Swiss.Muted, maxLines = 1)
                        }
                        MicLabel(focus, listening)
                    }
                }

                // Start → typing → results: the body fades in on each mode change.
                val bodyIn = rememberAppear(mode, Motion.MEDIUM, from = 0.25f)
                Box(Modifier.fillMaxWidth().enter({ bodyIn.value }, dy = 0f)) {
                    when (mode) {
                        0 -> StartBody(focus, recentShown, trendShown, daily, hasTmdb)
                        1 -> TypingBody(focus, appHits, typingTitles, remote.isEmpty() && hasTmdb, hasTmdb)
                        else -> ResultsBody(focus, filter, filtered, people, myServiceNames, browse != null && browseHits.isEmpty(), error)
                    }
                }
                Box(Modifier.weight(1f))
                when (mode) {
                    0 -> Footer(
                        if (hasTmdb) "Titles and artwork: TMDB · Where to watch: JustWatch" else "",
                        listOf(KeyHint("Hold OK", "Remove recent"), KeyHint("Back", "Home")),
                    )
                    1 -> Footer("No ads, no services you don’t use", listOf(KeyHint("Hold mic", "Google Assistant"), KeyHint("Back", "Clear")))
                    else -> Footer(
                        "Titles and artwork: TMDB · Where to watch: JustWatch",
                        listOf(KeyHint("OK", "Open"), KeyHint("Back", "Clear")),
                    )
                }
            }
        }
    }
}

@Composable
private fun MicLabel(focus: FocusScreenState, listening: Boolean) {
    NavChip(focus, 1, "mic", if (listening) "Mic on" else "Mic")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartBody(focus: FocusScreenState, recent: List<SearchHit.Recent>, trending: List<SearchHit.Title>, daily: Boolean, hasTmdb: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
        Column(Modifier.width(Swiss.span(4)), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            // Sections without data are left out.
            if (recent.isNotEmpty()) Column {
                Label("Recent", Modifier.padding(bottom = 12.dp))
                for ((k, r) in recent.withIndex()) {
                    RuleRow(focus, 2 + k, bottomRule = k == recent.lastIndex, vertical = 16.dp) { c ->
                        TDraw(r.query, SwissType.Row32, c.ink, Modifier.weight(1f), maxLines = 1)
                        TDraw(r.kind, SwissType.Meta, c.muted, maxLines = 1)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("Browse by genre")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((k, g) in genres.withIndex()) Chip(focus, 2 + recent.size + k, g)
                }
            }
        }
        val base = 2 + recent.size + genres.size
        // Trending appears once it has loaded; without TMDB it is simply not there.
        val shown = rememberAppear(trending.isNotEmpty(), Motion.MEDIUM)
        if (hasTmdb && trending.isNotEmpty()) Column(Modifier.weight(1f).enter({ shown.value }, dy = 0f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Label("Trending worldwide", color = Swiss.Ink)
                TabText(focus, base, "This week", !daily, SwissType.Tile)
                TabText(focus, base + 1, "Today", daily, SwissType.Tile)
                Box(Modifier.weight(1f))
                ServiceDot("On your services", true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for ((k, t) in trending.withIndex()) {
                    val (line, mine) = t.serviceLine()
                    PosterCard(
                        focus, base + 2 + k, t.posterUrl, 290.dp, t.ref.name, t.meta, Modifier.weight(1f),
                        titleStyle = SwissType.Card, metaStyle = SwissType.Meta, gap = 10.dp, decodeW = 228, decodeH = 342,
                    ) { ServiceDot(if (mine) line.substringAfter(" · ") else line, mine) }
                }
                repeat(5 - trending.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TypingBody(focus: FocusScreenState, apps: List<AppEntry>, titles: List<SearchHit.Title>, loading: Boolean, hasTmdb: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
        Column(Modifier.width(Swiss.span(4)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Apps · ${apps.size}")
            for (r in 0 until (apps.size + 1) / 2) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (c in 0 until 2) {
                    val k = r * 2 + c
                    val a = apps.getOrNull(k)
                    if (a == null) Box(Modifier.weight(1f)) else AppTile(focus, 2 + k, a.id.component, a.icon, a.label, Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.width(Swiss.span(1)))
        if (hasTmdb || titles.isNotEmpty()) Column(Modifier.weight(1f)) {
            Label("Titles · only on services you pay for", Modifier.padding(bottom = 14.dp))
            if (titles.isEmpty()) T(if (loading) "Looking…" else "Nothing on your services yet.", SwissType.Body)
            val arrived = rememberAppear(titles.isNotEmpty(), Motion.MEDIUM)
            Column(Modifier.enter({ arrived.value }, dy = 0f)) {
            for ((k, t) in titles.withIndex()) {
                RuleRow(focus, 2 + apps.size + k, vertical = 16.dp) { c ->
                    Art(t.backdropUrl ?: t.posterUrl, 448, 252, Modifier.size(224.dp, 126.dp))
                    Box(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TDraw(t.ref.name, SwissType.Row36, c.ink, maxLines = 1)
                        TDraw((t.availability.mine()?.providerName ?: "") + " · " + if (t.ref.type == MediaType.MOVIE) "Film" else "Series", SwissType.Meta, c.muted, maxLines = 1)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ResultsBody(
    focus: FocusScreenState,
    filter: Filter,
    titles: List<SearchHit.Title>,
    people: List<SearchHit.PersonHit>,
    myServices: List<String>,
    loading: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            for ((k, f) in Filter.entries.withIndex()) TabText(focus, 2 + k, f.label, f == filter, SwissType.Row32)
            Box(Modifier.weight(1f))
            T("Your services: " + myServices.joinToString(", "), SwissType.Meta, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(470.dp), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
            Box(Modifier.width(Swiss.span(9)).fillMaxHeight().clipToBounds().focusViewport(focus)) {
                if (titles.isEmpty()) T(error ?: if (loading) "Loading…" else "No titles here. Try Everything.", SwissType.Body)
                // Results fade in as they arrive, and again when a filter changes the set.
                val setKey = remember(titles) { titles.fold(17) { h, t -> h * 31 + t.key.hashCode() } }
                val arrived = rememberAppear(setKey to filter, Motion.MEDIUM)
                Column(Modifier.enter({ arrived.value }, dy = 0f).focusScroll(focus), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    for (r in 0 until (titles.size + 5) / 6) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (c in 0 until 6) {
                            val k = r * 6 + c
                            val t = titles.getOrNull(k)
                            if (t == null) Box(Modifier.weight(1f)) else {
                                val (line, mine) = t.serviceLine()
                                PosterCard(
                                    focus, 6 + k, t.posterUrl, 300.dp, t.ref.name, t.meta, Modifier.weight(1f),
                                    titleStyle = SwissType.Card, metaStyle = SwissType.Meta, gap = 10.dp,
                                    decodeW = 228, decodeH = 342, scrolling = true,
                                ) { ServiceDot(line, mine) }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                if (people.isNotEmpty()) Label("People", Modifier.padding(bottom = 12.dp))
                for ((k, p) in people.withIndex()) {
                    RuleRow(focus, 6 + titles.size + k, vertical = 14.dp) { c ->
                        Initial(p.person.name, Modifier.size(72.dp))
                        Box(Modifier.width(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TDraw(p.person.name, SwissType.Row28, c.ink, maxLines = 1)
                            TDraw(p.person.role + if (p.person.knownFor > 0) " · known for ${p.person.knownFor}" else "", SwissType.Meta, c.muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
