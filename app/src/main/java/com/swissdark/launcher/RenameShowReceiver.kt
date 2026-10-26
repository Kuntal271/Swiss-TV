package com.swissdark.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Names the series for a Continue watching row the catalogs could not resolve:
 * `adb shell am broadcast -a com.swissdark.launcher.RENAME_SHOW -p com.swissdark.launcher
 *  --es key "in.startv.hotstar|latest" --es show "VisionQuest"`.
 * DUMP is held by the shell, not by apps.
 */
class RenameShowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val show = intent.getStringExtra("show")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pending = goAsync()
        val graph = (context.applicationContext as LauncherApp).graph
        graph.scope.launch {
            try {
                graph.watched.renameShow(key, show)
            } finally {
                pending.finish()
            }
        }
    }
}
