package com.swissdark.launcher.feature.screensaver

import android.graphics.Bitmap
import android.service.dreams.DreamService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.swissdark.launcher.core.design.DesignCanvas
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule

/** Clock over the wallpaper. Moves a few pixels each minute so nothing burns in. */
@Composable
fun ScreensaverContent(bitmap: Bitmap?, caption: String) {
    val now by rememberMinute()
    val shift = (now.minute % 8) * 6
    Box(Modifier.fillMaxSize().background(Swiss.Ground)) {
        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 88.dp).offset(x = shift.dp, y = (-shift).dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, androidx.compose.ui.Alignment.Bottom),
        ) {
            T(now.format(Fmt.clock), SwissType.Saver)
            Row(Modifier.fillMaxWidth().topRule(2.dp, Swiss.Ink).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Label(now.format(Fmt.longDate), color = Swiss.Ink)
                Label(caption)
                Box(Modifier.weight(1f))
                Label("Any button wakes")
            }
        }
    }
}

/** DreamService hosting Compose: it supplies the lifecycle and saved-state owners Compose needs. */
abstract class ComposeDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    abstract fun wallpaper(): Bitmap?
    abstract fun caption(): String

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val bmp = wallpaper()
        val cap = caption()
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ComposeDreamService)
            setViewTreeSavedStateRegistryOwner(this@ComposeDreamService)
            setContent { DesignCanvas { ScreensaverContent(bmp, cap) } }
        }
        window.decorView.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

}
