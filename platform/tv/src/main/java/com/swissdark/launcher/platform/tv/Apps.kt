package com.swissdark.launcher.platform.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.swissdark.launcher.domain.AppArt

/** One launchable activity on the TV. */
data class InstalledApp(val component: String, val packageName: String, val label: String, val leanback: Boolean)

/** PackageManager.queryIntentActivities + package broadcasts. */
class PackagesAdapter(private val context: Context) {
    private val pm = context.packageManager

    fun launchable(): List<InstalledApp> {
        val out = LinkedHashMap<String, InstalledApp>()
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(leanback, 0)) {
            val ai = ri.activityInfo
            if (ai.packageName == context.packageName) continue
            out[ai.packageName] = InstalledApp(
                ComponentName(ai.packageName, ai.name).flattenToShortString(),
                ai.packageName, ri.loadLabel(pm).toString().trim(), true,
            )
        }
        // Phone-style apps that only have a LAUNCHER entry (e.g. sideloaded) still count.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val ai = ri.activityInfo
            if (ai.packageName == context.packageName || out.containsKey(ai.packageName)) continue
            out[ai.packageName] = InstalledApp(
                ComponentName(ai.packageName, ai.name).flattenToShortString(),
                ai.packageName, ri.loadLabel(pm).toString().trim(), false,
            )
        }
        return out.values.toList()
    }

    fun launchIntent(component: String): Intent? {
        val cn = ComponentName.unflattenFromString(component) ?: return null
        return Intent(Intent.ACTION_MAIN)
            .setComponent(cn)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun isInstalled(pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun watch(onChange: () -> Unit): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onChange()
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        return r
    }
}

/** App banners (320 × 180 on Android TV), scaled once to the size they are drawn at. */
class BannerArt(private val context: Context) : AppArt {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val noBanner: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun banner(packageName: String, width: Int, height: Int): Bitmap? {
        val key = "$packageName@${width}x$height"
        cache.get(key)?.let { return it }
        if (packageName in noBanner) return null
        val pm = context.packageManager
        // Only a real TV banner. No fallback to the launcher icon: for apps without one Android
        // hands back the default robot, stretched to 16:9. Null lets the UI draw its glyph tile.
        val d: Drawable = try {
            val app = pm.getApplicationInfo(packageName, 0)
            val cn = pm.getLeanbackLaunchIntentForPackage(packageName)?.component
            val activity = cn?.let { runCatching { pm.getActivityInfo(it, 0) }.getOrNull() }
            when {
                cn != null && activity != null && activity.banner != 0 -> pm.getActivityBanner(cn)
                app.banner != 0 -> pm.getApplicationBanner(app)
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: run { noBanner += packageName; return null }
        val sw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(sw)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = maxOf(width.toFloat() / iw, height.toFloat() / ih)
        val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
        val l = (width - dw) / 2; val t = (height - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(c)
        val hw = sw.copy(Bitmap.Config.HARDWARE, false) ?: sw
        if (hw !== sw) sw.recycle()
        cache.put(key, hw)
        return hw
    }

    fun trim() = cache.evictAll()

    /** An app update can add a banner: forget the misses when packages change. */
    fun forgetMisses() = noBanner.clear()
}

/** Is this launcher the Home app? The answer a TCL or Google update can silently change. */
class HomeRoleAdapter(private val context: Context) {
    fun currentHome(): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = context.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        return ri.activityInfo?.packageName
    }

    /** True when Home resolves to us, or when the accessibility fallback is on to catch Home presses. */
    fun isHome(): Boolean = currentHome() == context.packageName || fallbackEnabled()

    private fun fallbackEnabled(): Boolean {
        val on = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return on.split(':').any { it.startsWith(context.packageName + "/") }
    }
}
