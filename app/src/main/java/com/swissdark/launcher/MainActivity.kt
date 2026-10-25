package com.swissdark.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import com.swissdark.launcher.core.design.DesignCanvas
import com.swissdark.launcher.core.design.LocalAppArt
import com.swissdark.launcher.core.focus.KeyRouter
import com.swissdark.launcher.core.perf.Jank
import com.swissdark.launcher.domain.RefreshReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as LauncherApp).graph
    val routes = mutableStateListOf<Route>(Route.Home)
    private var started = false
    private var pendingVoice: ((String) -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val voiceIntent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) pendingVoice?.invoke(text)
        pendingVoice = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back is handled by each screen through the key router; anything that reaches the system
        // back path pops one route and never finishes Home (a launcher has nowhere to go back to).
        onBackPressedDispatcher.addCallback(this) { pop() }
        setContent {
            DesignCanvas {
                CompositionLocalProvider(LocalAppArt provides graph.art, com.swissdark.launcher.core.design.LocalAppGlyph provides com.swissdark.launcher.data.AppGlyphs::of) {
                    AppRoot(graph, routes, this)
                }
            }
        }
        // Only after the first frame: refreshers, receivers, image cache warm-up.
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (started) return
                started = true
                decor.post {
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    graph.startBackground()
                    BootReceiver.reenableFallback(this@MainActivity)
                    BootReceiver.reenableMediaListener(this@MainActivity)
                    Jank.install(this@MainActivity)
                    reportFullyDrawn()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we are in front: back to Home, focus on the first card.
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            if (routes.size > 1) {
                routes.clear(); routes.add(Route.Home)
            }
        }
    }

    /**
     * Home's snapshot is rebuilt while Home is hidden (another app in front), so it is already
     * current when Home comes back and Up next / app tiles don't reshuffle in front of the user.
     * No rebuild on resume: watch-next, package and settings changes still publish on their own.
     */
    private var hiddenRefresh: Job? = null

    override fun onStart() {
        super.onStart()
        hiddenRefresh?.cancel(); hiddenRefresh = null
    }

    override fun onStop() {
        super.onStop()
        if (!started || isChangingConfigurations) return
        hiddenRefresh?.cancel()
        hiddenRefresh = graph.scope.launch {
            while (isActive) {
                graph.home.refresh(RefreshReason.RESUMED)
                delay(HIDDEN_REFRESH_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (started) graph.status.checkHome()
    }

    // dispatchKeyEvent is public Activity API; androidx.core's ComponentActivity re-declares it
    // with @RestrictTo for its own KeyEventDispatcher, which lint misreports as a restricted call.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyRouter.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }


    fun push(r: Route) {
        if (routes.lastOrNull() != r) routes.add(r)
    }

    fun pop() {
        if (routes.size > 1) routes.removeAt(routes.lastIndex)
    }

    /** SpeechRecognizer in-app; without the mic permission, the system voice UI instead. */
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onEnd: () -> Unit) {
        val voice = graph.voice
        if (voice.available() && voice.hasPermission()) {
            voice.listen(onPartial, { onFinal(it) }, { onEnd() })
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        pendingVoice = { onFinal(it); onEnd() }
        try {
            voiceIntent.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
            )
        } catch (_: Exception) {
            pendingVoice = null
            onEnd()
        }
    }

    fun stopListening() = graph.voice.stop()

    private companion object {
        const val HIDDEN_REFRESH_MS = 30_000L
    }

    fun openPlayStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
