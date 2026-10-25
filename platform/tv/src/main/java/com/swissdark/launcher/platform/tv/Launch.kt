package com.swissdark.launcher.platform.tv

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Every Intent the launcher fires lives here. */
class LaunchAdapter(private val context: Context, private val packages: PackagesAdapter) {
    private val pm = context.packageManager

    fun start(i: Intent): Boolean = try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun launchComponent(component: String): Boolean = packages.launchIntent(component)?.let { start(it) } ?: false

    fun launchPackage(pkg: String): Boolean {
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return start(i)
    }

    fun openUri(uri: String, pkg: String?): Boolean {
        val i = try {
            if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } catch (_: Exception) {
            return false
        }
        if (pkg != null) i.setPackage(pkg)
        return start(i)
    }

    /** The routes a streaming app might take a title search through, best first. */
    fun searchIntents(pkg: String, query: String): List<Intent> {
        val q = Uri.encode(query)
        val out = ArrayList<Intent>()
        when (pkg) {
            "com.google.android.youtube.tv" ->
                out += Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")).setPackage(pkg)
            "com.netflix.ninja" ->
                out += Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/search?q=$q")).setPackage(pkg)
        }
        out += Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        out += Intent("android.search.action.GLOBAL_SEARCH").setPackage(pkg).putExtra(SearchManager.QUERY, query)
        return out
    }

    /** Checked once per app on the TV and stored, so we never try a route that cannot resolve. */
    fun acceptsSearch(pkg: String): Boolean =
        searchIntents(pkg, "test").any { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }

    fun search(pkg: String, query: String): Boolean {
        for (i in searchIntents(pkg, query)) {
            if (pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null && start(i)) return true
        }
        return false
    }

    fun youtube(videoKey: String): Boolean =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoKey")).setPackage("com.google.android.youtube.tv")) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoKey")))

    fun systemSettings(): Boolean = start(Intent(Settings.ACTION_SETTINGS))
}
