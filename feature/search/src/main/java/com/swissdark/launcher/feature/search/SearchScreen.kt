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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.swissdark.launcher.core.design.edgeFade
import com.swissdark.launcher.core.design.focusScaled
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Search state shared with MainActivity's key dispatch.
 * - [editing]: the user pressed OK on the field and is typing with the on-screen keyboard; only
 *   then do navigation keys go to the IME first.
 * - [keyHook]: Search's own D-pad routes (field ↔ columns) ahead of the geometric focus graph.
 * - The saved screen state lets Back from a title page return to the same query and item.
 */
object SearchInput {
    internal val editingState = mutableStateOf(false)
    var editing: Boolean
        get() = editingState.value
        set(v) { editingState.value = v }

    /**
     * A navigation key came back unhandled from the IME while [editing]: the keyboard is no
     * longer up (closed with Back, or never shown). Navigation goes to the screen again.
     */
    fun imeClosed() {
        editing = false
        onImeClosed?.invoke()
    }
    internal var onImeClosed: (() -> Unit)? = null
    var keyHook: ((KeyEvent) -> Boolean)? = null
    internal var saved: Saved? = null

    /** Forget the saved query and position (Home pressed, or Search closed). */
    fun reset() { saved = null }

    internal class Saved(
        val query: String,
        val browse: String?,
        val browsePerson: Int,
        val filter: Int,
        val focused: Int,
        val remote: List<SearchHit>,
        val browseHits: List<SearchHit>,
        val withAvail: Map<String, SearchHit.Title>,
    )
}

private enum class Filter(val label: String) { MINE("My services"), ALL("Everything"), FREE("Free"), KIDS("Kids-safe") }

private val genres = listOf("Thriller", "Comedy", "Drama", "Crime", "Documentary", "Kids")

private fun List<Availability>?.mine(): Availability? =
    this?.firstOrNull { it.inMyPlan && it.kind == Kind.STREAM } ?: this?.firstOrNull { it.kind == Kind.FREE || it.kind == Kind.ADS }

/** A short, human where-to-watch line ("On Prime Video"); empty when not known. */
private fun SearchHit.Title.serviceLine(tmdbMode: Boolean = true): Pair<String, Boolean> {
    val a = availability ?: return "" to false
    val m = a.mine()
    if (m != null) return (when (m.kind) { Kind.FREE, Kind.ADS -> "Free on "; else -> "On " } + m.providerName) to true
    val r = a.firstOrNull { it.kind == Kind.RENT } ?: a.firstOrNull { it.kind == Kind.BUY }
    if (r != null) return ((if (r.kind == Kind.RENT) "Rent on " else "Buy on ") + r.providerName) to false
    if (!tmdbMode) return (a.firstOrNull()?.let { "On " + it.providerName } ?: "") to false
    return "Not on your services" to false
}

private fun SearchHit.Title.kindLabel() = if (ref.type == MediaType.MOVIE) "Film" else "Series"

private val DPAD = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

private const val NO_SPEECH = "Didn’t catch that — try again"

/**
 * Search: apps and history answer on every keystroke, online titles once typing pauses.
 * Online titles come from TMDB, or from OMDb + TVmaze when there is no working TMDB key.
 */
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
    // Back from a title page: the same query, results and focused item.
    val saved = remember { SearchInput.saved.also { SearchInput.saved = null } }
    var query by remember { mutableStateOf(saved?.query ?: "") }
    var browse by remember { mutableStateOf(saved?.browse) }          // genre or person label
    var browseHits by remember { mutableStateOf(saved?.browseHits ?: emptyList()) }
    var remote by remember { mutableStateOf(saved?.remote ?: emptyList()) }
    var withAvail by remember { mutableStateOf(saved?.withAvail ?: emptyMap()) }
    // Without TMDB, where-to-watch is only known for some series: start on Everything.
    val source = engine.titleSource
    val tmdbMode = source == "TMDB"
    val credit = credits(source)
    var filter by remember { mutableStateOf(saved?.let { Filter.entries[it.filter] } ?: if (tmdbMode) Filter.MINE else Filter.ALL) }
    var daily by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<SearchHit.Title>>(emptyList()) }
    var listening by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var wantFirst by remember { mutableStateOf(false) }     // focus the first result once it arrives
    val browsePerson = remember { IntArray(1).also { it[0] = saved?.browsePerson ?: 0 } }
    val skipFetch = remember { BooleanArray(2) { saved != null } }   // restored lists need no refetch
    val recent by remember(engine) { engine.recent() }.collectAsState(emptyList())
    val now by rememberMinute()
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Wave 1: same frame as the keystroke.
    val local = remember(query) { if (query.isBlank()) emptyList() else engine.local(query) }
    // Wave 2: debounced inside remote(); a new keystroke cancels the older request.
    LaunchedEffect(query, browse) {
        if (skipFetch[0]) { skipFetch[0] = false; return@LaunchedEffect }
        error = null
        if (browse != null || query.isBlank()) { remote = emptyList(); return@LaunchedEffect }
        engine.remote(query).catch { error = "Online search unavailable"; emit(emptyList()) }.collect { remote = it }
    }
    LaunchedEffect(daily, hasTmdb) {
        trending = try { engine.trending(daily) } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(notice) {
        if (notice != null) { delay(3000); notice = null }
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
    val typingTitles = (if (tmdbMode) allTitles.filter { it.availability.mine() != null } else allTitles.sortedBy { it.availability.mine() == null }).take(3)
    val recentShown = recent.take(4)
    val trendShown = trending.take(5).map { resolved(it) }

    // Index layout: 0 = field, 1 = mic, then per mode.
    val count = 2 + when (mode) {
        0 -> recentShown.size + genres.size + 2 + trendShown.size
        1 -> appHits.size + typingTitles.size
        else -> 4 + filtered.size + people.size
    }
    val focus = rememberFocusScreen(count, initial = saved?.focused?.takeIf { it < count } ?: 0, active = active)
    val fromLabel = browse ?: query

    // ---- The field: Compose focus only while D-pad focus is on it; the keyboard only after OK ----
    var editing by SearchInput.editingState
    val fieldOn = remember(focus) { focus.isFocused(0) }
    val onField = active && fieldOn.value
    val view = LocalView.current
    fun hideIme() {
        keyboard?.hide()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }
    LaunchedEffect(onField) {
        if (onField) {
            try { fieldFocus.requestFocus() } catch (_: IllegalStateException) {}
            // Focus (and Compose's input session, which asks for the keyboard) lands a frame or
            // two later: keep the keyboard closed until OK.
            repeat(3) {
                withFrameNanos { }
                if (!editing) hideIme()
            }
            delay(120)
            if (!editing) hideIme()
        } else {
            editing = false
            focusManager.clearFocus(force = true)
            hideIme()
        }
    }
    // The TV's keyboard (TCL) comes back for a moment after a hide sent from inside its own
    // Enter/Search key, and Compose re-shows it while the field still holds focus: after a
    // search, drop the field's focus and keep hiding over the next few frames.
    var imeSettle by remember { mutableStateOf(0) }
    LaunchedEffect(imeSettle) {
        if (imeSettle == 0) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (!editing) hideIme()
        }
        delay(150)
        if (!editing) hideIme()
        delay(350)
        if (!editing) hideIme()
    }
    fun submitted() {
        editing = false
        focusManager.clearFocus(force = true)
        hideIme()
        imeSettle++
        wantFirst = true
    }
    DisposableEffect(Unit) {
        SearchInput.onImeClosed = { hideIme(); imeSettle++ }
        onDispose {
            SearchInput.onImeClosed = null
            SearchInput.editing = false
            focusManager.clearFocus(force = true)
            hideIme()
        }
    }

    // After a genre, person, recent search or Search key: the first result, once it is there;
    // until then the current filter, never whatever index the old layout left behind.
    LaunchedEffect(wantFirst, mode, filtered.size, appHits.size, typingTitles.size) {
        if (!wantFirst) return@LaunchedEffect
        when {
            mode == 2 && filtered.isNotEmpty() -> { focus.focus(6, animate = true); wantFirst = false }
            mode == 2 -> focus.focus(2 + filter.ordinal)
            mode == 1 && appHits.size + typingTitles.size > 0 -> { focus.focus(2, animate = true); wantFirst = false }
        }
    }

    fun clear() {
        query = ""; browse = null; browseHits = emptyList(); remote = emptyList(); wantFirst = false; focus.focus(0)
    }

    val voice = remember { BooleanArray(2) }   // [heard anything, cancelled by the user]
    fun startVoice() {
        listening = true; notice = null; voice[0] = false; voice[1] = false
        actions.listen(
            { if (it.isNotBlank()) { voice[0] = true; query = it; browse = null } },
            { if (it.isNotBlank()) { voice[0] = true; query = it; browse = null; engine.remember(it, "Voice"); wantFirst = true } },
            // Silence: say so briefly instead of quietly dropping back.
            { listening = false; if (!voice[0] && !voice[1]) notice = NO_SPEECH },
        )
    }
    fun stopVoice() { voice[1] = true; actions.stopListening(); listening = false }

    fun open(t: SearchHit.Title, from: String) {
        SearchInput.saved = SearchInput.Saved(query, browse, browsePerson[0], filter.ordinal, focus.focused, remote, browseHits, withAvail)
        actions.openTitle(t, from)
    }

    focus.onClick = click@{ i ->
        if (i == 0) {
            editing = true
            try { fieldFocus.requestFocus() } catch (_: IllegalStateException) {}
            keyboard?.show(); return@click
        }
        if (i == 1) {
            if (listening) stopVoice() else startVoice()
            return@click
        }
        var k = i - 2
        when (mode) {
            0 -> {
                if (k < recentShown.size) { query = recentShown[k].query; wantFirst = true; return@click }
                k -= recentShown.size
                if (k < genres.size) {
                    val g = genres[k]
                    browse = g; browseHits = emptyList(); filter = Filter.ALL; wantFirst = true
                    return@click
                }
                k -= genres.size
                if (k < 2) { daily = k == 1; return@click }
                k -= 2
                trendShown.getOrNull(k)?.let { open(it, (if (tmdbMode) "Trending · " else "New episodes · ") + if (daily) "today" else "this week") }
            }
            1 -> {
                if (k < appHits.size) { val a = appHits[k]; engine.remember(a.label, "App"); actions.launchApp(a); return@click }
                k -= appHits.size
                typingTitles.getOrNull(k)?.let { engine.remember(it.ref.name, it.kindLabel()); open(it, "From search “$query”") }
            }
            else -> {
                if (k < 4) { filter = Filter.entries[k]; return@click }
                k -= 4
                if (k < filtered.size) {
                    val t = filtered[k]
                    if (browse == null) engine.remember(t.ref.name, t.kindLabel())
                    open(t, t.kindLabel() + " · from " + (if (browse != null) browse!! else "search “$query”"))
                    return@click
                }
                k -= filtered.size
                people.getOrNull(k)?.let { p ->
                    engine.remember(p.person.name, "Person")
                    browse = p.person.name; browseHits = emptyList(); filter = Filter.ALL; wantFirst = true
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
                    if (listening) stopVoice()
                    else if (query.isNotEmpty() || browse != null) clear() else { SearchInput.reset(); actions.back() }
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

    // Explicit routes where the geometric graph guesses wrong (the full-width field above two columns).
    val recentCount = recentShown.size
    val trendCount = trendShown.size
    val titleCount = filtered.size
    val appCount = appHits.size
    val curFilter = filter
    val route: (Int, Int) -> Int = route@{ cur, key ->
        val down = key == KeyEvent.KEYCODE_DPAD_DOWN
        val up = key == KeyEvent.KEYCODE_DPAD_UP
        val right = key == KeyEvent.KEYCODE_DPAD_RIGHT
        when (mode) {
            0 -> {
                val genre0 = 2 + recentCount
                val tabs = genre0 + genres.size
                val poster0 = tabs + 2
                when {
                    cur == 0 && down -> 2
                    cur == 1 && down -> if (trendCount > 0) tabs else 2
                    recentCount > 0 && cur == 2 + recentCount - 1 && down -> genre0
                    cur in 2 until genre0 && right && trendCount > 0 -> tabs
                    (cur == tabs || cur == tabs + 1) && down && trendCount > 0 -> poster0
                    (cur == tabs || cur == tabs + 1) && up -> 1
                    cur >= poster0 && up -> if (daily) tabs + 1 else tabs
                    cur == 2 && up -> 0
                    else -> -1
                }
            }
            1 -> when {
                cur == 0 && down -> 2
                cur in 2 until 2 + appCount && up && cur < 4 -> 0
                else -> -1
            }
            else -> when {
                cur == 0 && down -> 2 + curFilter.ordinal
                cur in 2 until 6 && down -> if (titleCount > 0) 6 else -1
                cur in 6 until 6 + minOf(6, titleCount) && up -> 2 + curFilter.ordinal
                cur in 2 until 6 && up -> 0
                else -> -1
            }
        }
    }
    val routeNow by rememberUpdatedState(route)
    DisposableEffect(active) {
        if (active) SearchInput.keyHook = hook@{ e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@hook false
            if (e.keyCode in DPAD) wantFirst = false
            val to = routeNow(focus.focused, e.keyCode)
            if (to in 0 until focus.count) { focus.focus(to, animate = true); true } else false
        }
        onDispose { if (active) SearchInput.keyHook = null }
    }

    // Genre / person browsing: TMDB discover or credits.
    LaunchedEffect(browse) {
        if (skipFetch[1]) { skipFetch[1] = false; return@LaunchedEffect }
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
                    notice != null -> "Didn’t catch that"
                    mode == 0 -> "Nothing typed yet"
                    mode == 1 -> "${appHits.size} apps · ${typingTitles.size} titles"
                    else -> "${filtered.size} results" + if (filter == Filter.MINE) " on your services" else ""
                }
                Header(deviceName.uppercase(), now.format(Fmt.clock), headerC, "Search")

                // The field: muted rule at rest; yellow rule and a faint fill while focused.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(if (mode == 1) "Say or type" else "Search films, series, people and apps")
                    Row(
                        Modifier.fillMaxWidth().drawBehind {
                            val on = fieldOn.value
                            if (on) drawRect(FieldFill)
                            val w = (if (on) 4.dp else 2.dp).toPx()
                            drawRect(if (on) Swiss.Focus else Swiss.Rule, Offset(0f, size.height - w), Size(size.width, w))
                        }.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val style = if (mode == 1) SwissType.Query120 else SwissType.Query96
                        Box(Modifier.weight(1f).focusItem(focus, 0, FocusStyle.SELF)) {
                            BasicTextField(
                                value = browse ?: query,
                                onValueChange = { if (onField) { query = it; browse = null } },
                                // Only focusable while D-pad focus is on it: otherwise clearing focus (or
                                // the window regaining it) hands Compose focus straight back to the field,
                                // which reopens its input session and the keyboard.
                                modifier = Modifier.fillMaxWidth().focusProperties { canFocus = onField }.focusRequester(fieldFocus),
                                singleLine = true,
                                textStyle = style,
                                cursorBrush = SolidColor(Swiss.Focus),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
                                // Some TV keyboards send Done/Go or a bare Enter instead of Search.
                                keyboardActions = KeyboardActions(onSearch = { submitted() }, onDone = { submitted() }, onGo = { submitted() }),
                            )
                            if (query.isEmpty() && browse == null) {
                                T(if (listening) "Listening…" else notice ?: "Say or type…", style, color = Swiss.Muted, maxLines = 1)
                            }
                        }
                        MicLabel(focus, listening)
                    }
                }

                // Start → typing → results: the body fades in on each mode change.
                val bodyIn = rememberAppear(mode, Motion.MEDIUM, from = 0.25f)
                Box(Modifier.fillMaxWidth().enter({ bodyIn.value }, dy = 0f)) {
                    when (mode) {
                        0 -> StartBody(focus, recentShown, trendShown, daily, tmdbMode)
                        1 -> TypingBody(focus, appHits, typingTitles, remote.isEmpty() && hasTmdb, tmdbMode)
                        else -> ResultsBody(focus, filter, filtered, people, myServiceNames, browse != null && browseHits.isEmpty(), error, tmdbMode)
                    }
                }
                Box(Modifier.weight(1f))
                when (mode) {
                    0 -> Footer(
                        credit,
                        listOf(KeyHint("Hold OK", "Remove recent"), KeyHint("Back", "Home")),
                    )
                    1 -> Footer("No ads, no services you don’t use", listOf(KeyHint("Mic", "Search by voice"), KeyHint("Back", "Clear")))
                    else -> Footer(
                        credit,
                        listOf(KeyHint("OK", "Open"), KeyHint("Back", "Clear")),
                    )
                }
            }
        }
    }
}

private val FieldFill = androidx.compose.ui.graphics.Color(0x0FEDEDED)

/** Credit line for the footer: who supplied titles and where-to-watch. */
private fun credits(source: String): String =
    if (source == "TMDB") "Titles and artwork: TMDB · Where to watch: JustWatch" else ""

@Composable
private fun MicLabel(focus: FocusScreenState, listening: Boolean) {
    NavChip(focus, 1, "mic", if (listening) "Mic on" else "Mic")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartBody(focus: FocusScreenState, recent: List<SearchHit.Recent>, trending: List<SearchHit.Title>, daily: Boolean, tmdbMode: Boolean) {
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
        // Trending (or, without TMDB, what is airing) appears once it has loaded; never an empty section.
        val shown = rememberAppear(trending.isNotEmpty(), Motion.MEDIUM)
        if (trending.isNotEmpty()) Column(Modifier.weight(1f).enter({ shown.value }, dy = 0f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(if (tmdbMode) "Trending worldwide" else "New episodes", color = Swiss.Ink)
                TabText(focus, base, "This week", !daily, SwissType.Tile)
                TabText(focus, base + 1, "Today", daily, SwissType.Tile)
                Box(Modifier.weight(1f))
                ServiceDot("On your services", true)
            }
            // End padding keeps the last card's focus ring and scale inside the right margin.
            Row(Modifier.padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for ((k, t) in trending.withIndex()) {
                    SearchPoster(focus, base + 2 + k, t, 290.dp, tmdbMode, Modifier.weight(1f))
                }
                repeat(5 - trending.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TypingBody(focus: FocusScreenState, apps: List<AppEntry>, titles: List<SearchHit.Title>, loading: Boolean, tmdbMode: Boolean) {
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
        if (titles.isNotEmpty() || loading) Column(Modifier.weight(1f)) {
            Label(if (tmdbMode) "Titles · only on services you pay for" else "Titles · yours first", Modifier.padding(bottom = 14.dp))
            if (titles.isEmpty()) T("Looking…", SwissType.Body)
            val arrived = rememberAppear(titles.isNotEmpty(), Motion.MEDIUM)
            Column(Modifier.enter({ arrived.value }, dy = 0f)) {
            for ((k, t) in titles.withIndex()) {
                RuleRow(focus, 2 + apps.size + k, vertical = 16.dp) { c ->
                    Art(t.backdropUrl ?: t.posterUrl, 448, 252, Modifier.size(224.dp, 126.dp))
                    Box(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TDraw(t.ref.name, SwissType.Row36, c.ink, maxLines = 1)
                        TDraw(listOfNotNull(t.availability.mine()?.providerName, t.meta).joinToString(" · "), SwissType.Meta, c.muted, maxLines = 1)
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
    tmdbMode: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            for ((k, f) in Filter.entries.withIndex()) TabText(focus, 2 + k, f.label, f == filter, SwissType.Row32)
            Box(Modifier.weight(1f))
            T("Your services: " + myServices.joinToString(", "), SwissType.Meta, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(470.dp), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
            Box(
                Modifier.width(Swiss.span(9)).fillMaxHeight().clipToBounds().focusViewport(focus)
                    // Rows scrolled under the filters fade out instead of being cut mid-line.
                    .edgeFade(
                        topAmount = { focus.scrollY.floatValue / 48f },
                        bottomAmount = { (focus.contentBottom - focus.scrollY.floatValue - focus.viewportBottom) / 48f },
                    ),
            ) {
                if (titles.isEmpty()) T(error ?: if (loading) "Loading…" else "No titles here. Try Everything.", SwissType.Body)
                // Results fade in as they arrive, and again when a filter changes the set.
                val setKey = remember(titles) { titles.fold(17) { h, t -> h * 31 + t.key.hashCode() } }
                val arrived = rememberAppear(setKey to filter, Motion.MEDIUM)
                Column(Modifier.enter({ arrived.value }, dy = 0f).focusScroll(focus).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    for (r in 0 until (titles.size + 5) / 6) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (c in 0 until 6) {
                            val k = r * 6 + c
                            val t = titles.getOrNull(k)
                            if (t == null) Box(Modifier.weight(1f))
                            else SearchPoster(focus, 6 + k, t, 288.dp, tmdbMode, Modifier.weight(1f), scrolling = true)
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

/**
 * A poster with its title in a fixed two-line box, so the meta lines of a row stay aligned
 * whether a title takes one line or two.
 */
@Composable
private fun SearchPoster(
    focus: FocusScreenState,
    index: Int,
    t: SearchHit.Title,
    imageHeight: androidx.compose.ui.unit.Dp,
    tmdbMode: Boolean,
    modifier: Modifier,
    scrolling: Boolean = false,
) {
    val focused = remember(focus, index) { focus.isFocused(index) }
    val color = remember(focused) { androidx.compose.ui.graphics.ColorProducer { if (focused.value) Swiss.Focus else Swiss.Ink } }
    val reduce = com.swissdark.launcher.core.design.LocalReduceMotion.current
    Column(modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().focusItem(focus, index, FocusStyle.RING, scrolling, scale = !reduce).focusScaled(focus, index)) {
            Art(t.posterUrl, 228, 342, Modifier.fillMaxWidth().height(imageHeight))
        }
        Box(Modifier.fillMaxWidth().height(TitleBox)) { TDraw(t.ref.name, SwissType.Card, color, maxLines = 2) }
        T(t.meta, SwissType.Meta, maxLines = 1)
        val (line, mine) = t.serviceLine(tmdbMode)
        if (line.isNotEmpty()) ServiceDot(line, mine)
    }
}

private val TitleBox = 66.dp
