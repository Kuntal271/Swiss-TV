package com.swissdark.launcher.platform.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import com.swissdark.launcher.domain.Providers
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/** An installed icon pack (ADW / Nova style). */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * ADW / Nova-style icon packs: apps that answer the theme intents and map components to
 * drawables in an appfilter.xml (res/xml or assets).
 */
object IconPacks {
    private val intents = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

    fun installed(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, IconPackInfo>()
        for (action in intents) {
            val ris = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            for (ri in ris) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg !in out) out[pkg] = IconPackInfo(pkg, ri.loadLabel(pm).toString().trim())
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    private val loaded = ConcurrentHashMap<String, IconPack>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The pack, its appfilter parsed once. Null when it is not installed or has no appfilter. */
    fun load(context: Context, pkg: String): IconPack? {
        if (pkg.isEmpty() || pkg in failed) return null
        loaded[pkg]?.let { return it }
        val pack = runCatching {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val filter = readAppFilter(res, pkg)
            if (filter.isEmpty) null else IconPack(pkg, res, filter)
        }.getOrNull()
        if (pack == null) failed += pkg else loaded[pkg] = pack
        return pack
    }

    /** Packages changed: a pack may have been installed, updated or removed. */
    fun forget() {
        loaded.clear(); failed.clear()
    }

    private fun readAppFilter(res: Resources, pkg: String): AppFilter {
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("appfilter", "xml", pkg)
        if (id != 0) {
            val p = res.getXml(id)
            try { return AppFilter.parse(p) } finally { p.close() }
        }
        return runCatching {
            res.assets.open("appfilter.xml").use { input ->
                val p = android.util.Xml.newPullParser()
                p.setInput(input, null)
                AppFilter.parse(p)
            }
        }.getOrDefault(AppFilter.EMPTY)
    }
}

class IconPack internal constructor(val packageName: String, private val res: Resources, private val filter: AppFilter) {
    /** The pack's icon for [component] ("pkg/.Cls" or "pkg/pkg.Cls"), at high density. */
    fun icon(component: String): Drawable? {
        val name = filter.drawableFor(component) ?: return null
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 }
            ?: return null
        return runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
    }
}

/**
 * appfilter.xml: `<item component="ComponentInfo{pkg/cls}" drawable="name"/>`. Looked up by the
 * exact component first, then by package (TV activities often differ from the phone ones packs
 * are drawn for).
 */
class AppFilter private constructor(private val byComponent: Map<String, String>, private val byPackage: Map<String, String>) {
    val isEmpty: Boolean get() = byComponent.isEmpty()
    val size: Int get() = byComponent.size

    fun drawableFor(component: String): String? {
        val (pkg, cls) = split(component) ?: return null
        return byComponent["$pkg/$cls"] ?: byPackage[pkg]
    }

    companion object {
        val EMPTY = AppFilter(emptyMap(), emptyMap())

        /** "ComponentInfo{pkg/cls}" or "pkg/cls" (short ".Cls" form allowed) → pkg to the full class. */
        fun split(component: String): Pair<String, String>? {
            val c = component.trim().removePrefix("ComponentInfo{").removeSuffix("}")
            val slash = c.indexOf('/')
            if (slash <= 0 || slash == c.length - 1) return null
            val pkg = c.substring(0, slash).trim()
            var cls = c.substring(slash + 1).trim()
            if (cls.startsWith(".")) cls = pkg + cls
            if (pkg.isEmpty() || cls.isEmpty()) return null
            return pkg to cls
        }

        fun parse(p: XmlPullParser): AppFilter {
            val comps = HashMap<String, String>()
            val pkgs = HashMap<String, String>()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "item") {
                    val comp = p.getAttributeValue(null, "component")
                    val drawable = p.getAttributeValue(null, "drawable")
                    if (comp != null && !drawable.isNullOrBlank()) {
                        split(comp)?.let { (pkg, cls) ->
                            comps.putIfAbsent("$pkg/$cls", drawable)
                            pkgs.putIfAbsent(pkg, drawable)
                        }
                    }
                }
                ev = p.next()
            }
            return AppFilter(comps, pkgs)
        }
    }
}

/**
 * Draws tile art when an app has no TV banner: its launcher icon (or icon pack icon) large and
 * centred on the app's colour. Adaptive icons keep their own background layer; other icons get
 * their brand colour (Providers) or the average colour of the icon.
 */
internal object IconTileRenderer {
    /** [iconPack] "" = the app's own launcher icon; otherwise that pack's icon only. */
    fun render(context: Context, component: String, iconPack: String, width: Int, height: Int): Bitmap? {
        val pm = context.packageManager
        val cn = ComponentName.unflattenFromString(component) ?: return null
        // With a pack: only the pack's icon (null if it has none, so the banner can be used).
        val d = if (iconPack.isNotEmpty()) IconPacks.load(context, iconPack)?.icon(component) ?: return null
        else appIcon(pm, cn) ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // 108-unit layers with a 72-unit safe zone: the logo spans about 80 % of the height.
            val bg = d.background
            if (bg is ColorDrawable || bg == null) c.drawColor(fill(bg?.let { colorOf(it) } ?: Color.DKGRAY))
            else drawCentered(c, bg, width.toFloat().coerceAtLeast(height.toFloat()))
            d.foreground?.let { drawCentered(c, it, height * 1.2f) }
        } else {
            val brand = Providers.byPackage(cn.packageName)?.color?.toInt()
            c.drawColor(fill(brand ?: colorOf(d)))
            drawCentered(c, d, height * 0.72f)
        }
        return bmp
    }

    private fun appIcon(pm: PackageManager, cn: ComponentName): Drawable? = runCatching {
        val ai = pm.getActivityInfo(cn, 0)
        val res = ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon.takeIf { it != 0 } ?: return null
        val r = pm.getResourcesForApplication(ai.applicationInfo)
        r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    private fun drawCentered(c: Canvas, d: Drawable, side: Float) {
        val s = side.toInt()
        val l = (c.width - s) / 2; val t = (c.height - s) / 2
        d.setBounds(l, t, l + s, t + s)
        d.draw(c)
    }

    /** Opaque, never pure white: a tile must read as a tile on the dark page. */
    private fun fill(color: Int): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val k = if ((r * 299 + g * 587 + b * 114) / 1000 > 235) 0.9f else 1f
        return Color.rgb((r * k).toInt(), (g * k).toInt(), (b * k).toInt())
    }

    /** Alpha-weighted average colour of a drawable, rendered at 24 × 24. */
    private fun colorOf(d: Drawable): Int {
        if (d is ColorDrawable) return d.color
        val n = 24
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(b))
        val px = IntArray(n * n)
        b.getPixels(px, 0, n, 0, 0, n, n)
        b.recycle()
        var r = 0L; var g = 0L; var bl = 0L; var w = 0L
        for (p in px) {
            val a = p ushr 24
            if (a < 128) continue
            r += Color.red(p) * a; g += Color.green(p) * a; bl += Color.blue(p) * a; w += a
        }
        if (w == 0L) return Color.DKGRAY
        return Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
    }
}
