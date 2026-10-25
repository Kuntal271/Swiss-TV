package com.swissdark.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import com.swissdark.launcher.data.DataGraph
import com.swissdark.launcher.domain.TimeSlot
import com.swissdark.launcher.feature.screensaver.ComposeDreamService
import java.time.LocalTime

private val Context.graph: DataGraph get() = (applicationContext as LauncherApp).graph

/** Screensaver: clock over the current slot's wallpaper, undimmed. */
class SwissDream : ComposeDreamService() {
    override fun wallpaper(): Bitmap? = graph.wallpapers.loadUndimmed()
    override fun caption(): String {
        val wp = graph.wallpapers.current.value.wallpaper
        return (wp?.name ?: TimeSlot.at(LocalTime.now()).label) + if (wp?.is4k == true) " · 4K" else ""
    }
}

/** Five minutes before a slot boundary: decode the next wallpaper even if the process was killed. */
class WallpaperAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.graph.wallpapers.prepareNext()
    }
}

/** After boot or an update: is this still the Home app? Home shows a one-line warning if not. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An app update unbinds accessibility services; re-enable ours if setup.sh had turned it on.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            reenableFallback(context)
            reenableMediaListener(context)
        }
        context.graph.status.checkHome()
        context.graph.watchPower()
        // The TV was off (or lost power): start with an empty Continue watching.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            context.graph.clearContinue("boot") { pending.finish() }
        }
    }

    companion object {
        /** An update drops our notification-listener grant (media sessions for Up next); put it back. */
        fun reenableMediaListener(context: Context) {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val me = android.content.ComponentName(context, MediaListenerService::class.java).flattenToString()
            val cr = context.contentResolver
            val key = "enabled_notification_listeners"
            val cur = android.provider.Settings.Secure.getString(cr, key).orEmpty()
            if (me !in cur.split(':')) {
                android.provider.Settings.Secure.putString(cr, key, listOf(cur, me).filter { it.isNotEmpty() }.joinToString(":"))
            }
        }

        fun reenableFallback(context: Context) {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val prefs = context.getSharedPreferences("fallback", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("wanted", false)) return
            // Only needed while another Home outranks us. When we hold Home natively the service
            // just costs every app on the TV accessibility work per frame, so leave it off.
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val home = context.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (home == context.packageName) {
                prefs.edit().putBoolean("wanted", false).apply()
                return
            }
            val me = "${context.packageName}/${HomeFallbackService::class.java.name}"
            val cr = context.contentResolver
            val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (me !in cur.split(':')) {
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, listOf(cur, me).filter { it.isNotEmpty() }.joinToString(":"))
            }
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}

/** `adb shell am broadcast -a com.swissdark.launcher.CONFIG --es tmdb_key … --es omdb_key … --es services netflix,prime` */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("tmdb_key")
        val omdb = intent.getStringExtra("omdb_key")
        val services = intent.getStringExtra("services")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        context.graph.settings.update { s ->
            var n = s
            if (key != null) n = n.copy(tmdbKey = key.trim())
            if (omdb != null) n = n.copy(omdbKey = omdb.trim())
            if (services != null) n = n.copy(myServices = services)
            n
        }
        resultData = "ok"
    }
}

/**
 * Test hook for "clear Continue watching when the TV turns off", without turning the TV off:
 * `adb shell am broadcast -a com.swissdark.launcher.DEBUG_SCREEN_OFF -p com.swissdark.launcher [--ez force true]`.
 * Same path as SCREEN_OFF; honours the setting unless force.
 */
class DebugScreenOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.graph.clearContinue("debug", force = intent.getBooleanExtra("force", false)) { pending.finish() }
    }
}

/**
 * Fallback only. If a TCL or Google update hands Home back to Google TV, this sees Google TV's
 * home window appear and brings this launcher to the front. Enabled by setup.sh only when
 * `cmd package set-home-activity` did not stick.
 */
class HomeFallbackService : AccessibilityService() {
    override fun onServiceConnected() {
        // Remembered so BootReceiver can turn the service back on after an app update.
        getSharedPreferences("fallback", MODE_PRIVATE).edit().putBoolean("wanted", true).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != GOOGLE_TV) return
        val cls = event.className?.toString() ?: return
        if (!cls.endsWith(".home.HomeActivity")) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .addCategory(Intent.CATEGORY_HOME),
        )
    }

    override fun onInterrupt() {}

    companion object {
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
    }
}

/**
 * Never reads notifications. Being an enabled notification listener is what lets this launcher
 * see other apps' media sessions (MediaSessionManager.getActiveSessions), which is how Up next
 * learns what was watched. setup.sh: `cmd notification allow_listener …/.MediaListenerService`.
 */
class MediaListenerService : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        graph.watched.start()
        graph.watchPower()
    }
}
