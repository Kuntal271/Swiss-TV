package com.swissdark.launcher.platform.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.swissdark.launcher.domain.LaunchResult
import com.swissdark.launcher.domain.PictureMode
import com.swissdark.launcher.domain.QuickSettingsController
import com.swissdark.launcher.domain.SoundMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Picture and sound modes are TCL-private: a third-party app cannot set them. Each control
 * opens the closest TCL settings page that exists on this firmware, else Android TV settings.
 */
class TclSettingsAdapter(private val context: Context, private val launch: LaunchAdapter) : QuickSettingsController {
    private val pm = context.packageManager
    private val _picture = MutableStateFlow<PictureMode?>(null)
    private val _sound = MutableStateFlow<SoundMode?>(null)
    override val pictureMode: StateFlow<PictureMode?> = _picture
    override val soundMode: StateFlow<SoundMode?> = _sound

    private fun firstResolvable(candidates: List<Intent>): Intent? =
        candidates.firstOrNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }

    private fun open(candidates: List<Intent>): LaunchResult {
        val i = firstResolvable(candidates) ?: Intent(Settings.ACTION_SETTINGS)
        return if (launch.start(i)) LaunchResult.OpenedAppHome else LaunchResult.Failed("no settings page")
    }

    val pictureCandidates: List<Intent>
        get() = TclRoutes.picture.map { it.toIntent() }

    val soundCandidates: List<Intent>
        get() = TclRoutes.sound.map { it.toIntent() }

    override fun setPicture(mode: PictureMode): LaunchResult {
        _picture.value = mode
        return open(pictureCandidates)
    }

    override fun setSound(mode: SoundMode): LaunchResult {
        _sound.value = mode
        return open(soundCandidates)
    }

    override fun setSleep(minutes: Int): LaunchResult = open(TclRoutes.sleep.map { it.toIntent() })

    override fun pictureOff(): LaunchResult = open(TclRoutes.pictureOff.map { it.toIntent() })
}

/** Intent routes into TCL's settings, best first. Verified on the TV with `adb shell dumpsys package`. */
object TclRoutes {
    data class Route(val action: String? = null, val pkg: String? = null, val cls: String? = null) {
        fun toIntent(): Intent {
            val i = if (action != null) Intent(action) else Intent(Intent.ACTION_MAIN)
            if (pkg != null && cls != null) i.setClassName(pkg, cls) else if (pkg != null) i.setPackage(pkg)
            return i
        }
    }

    // Probed on the G09_4K_GB firmware: TCL's picture/sound pages live inside com.android.tv.settings.
    var picture = listOf(
        Route("android.settings.DISPLAY_SETTINGS", "com.android.tv.settings", "com.tcl.settings.PictureActivity"),
        Route("android.settings.DISPLAY_SETTINGS"),
    )
    var sound = listOf(
        Route("com.android.tv.action.VIEW_SOUND_SETTINGS", "com.android.tv.settings", "com.tcl.settings.SoundActivity"),
        Route("com.android.tv.action.VIEW_SOUND_SETTINGS"),
        Route("android.settings.SOUND_SETTINGS"),
    )
    var sleep = listOf(
        Route("com.android.tv.settings.action.ENERGY_SAVING"),
        Route("android.settings.DISPLAY_SETTINGS"),
    )
    var pictureOff = listOf(
        Route("com.android.tv.action.VIEW_SOUND_SETTINGS"),
        Route("android.settings.SOUND_SETTINGS"),
    )
}
