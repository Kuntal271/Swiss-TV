package com.swissdark.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * "When I turn off the TV, Continue watching should be cleared."
 *
 * Deletes everything that feeds the row (watched + app-playback rows and their thumbnails,
 * titles opened from this launcher), hides Watch Next rows engaged before now, and rebuilds the
 * Home snapshot so the row is empty (Home hides it) when the TV wakes.
 */
class ContinueCleaner(
    private val enabled: () -> Boolean,
    private val ops: Ops,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    interface Ops {
        /** Watched + app-playback rows and cached thumbnails; returns rows/files removed. */
        suspend fun deleteWatched(): Int
        /** Launcher history (titles opened from Search / title pages). */
        suspend fun deleteHistory(): Int
        fun markCleared(at: Long)
        suspend fun rebuild()
    }

    private val lock = Mutex()

    /** Returns false when the setting is off (and [force] is not set). */
    suspend fun clear(reason: String, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        if (!force && !enabled()) {
            log("clear skipped ($reason): setting off")
            return false
        }
        lock.withLock {
            val w = runCatching { ops.deleteWatched() }.onFailure { log("watched failed: $it") }.getOrDefault(0)
            val h = runCatching { ops.deleteHistory() }.onFailure { log("history failed: $it") }.getOrDefault(0)
            ops.markCleared(now)
            ops.rebuild()
            log("Continue watching cleared ($reason): watched+thumbs=$w history=$h")
        }
        return true
    }

    companion object {
        const val TAG = "SwissContinue"
        const val DEBUG_ACTION = "com.swissdark.launcher.DEBUG_SCREEN_OFF"

        /** Watch Next rows belong to other apps, so they are hidden rather than deleted. */
        fun clearedWatchNext(lastEngagement: Long, clearedAt: Long): Boolean =
            clearedAt > 0 && lastEngagement in 1..clearedAt

        fun deleteFiles(dir: File): Int = dir.listFiles()?.count { it.isFile && it.delete() } ?: 0
    }
}

/**
 * Runtime receiver for the TV going off. Registered by a long-lived component (the bound
 * notification listener keeps the process alive), since SCREEN_OFF cannot be declared in the manifest.
 *
 * - SCREEN_OFF: sent when the TV leaves the interactive state (standby / TCL suspend). Starting
 *   the screensaver (a dream) keeps the TV interactive and sends DREAMING_STARTED instead, which
 *   is deliberately not handled: a screensaver is not "TV off".
 * - SHUTDOWN: power cut from the menu. Boot is handled by BootReceiver.
 * - SCREEN_ON: recording of media sessions resumes.
 */
class PowerEvents(
    private val onOff: (reason: String, done: () -> Unit) -> Unit,
    private val onOn: () -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN -> {
                // The device may suspend right away: keep the broadcast open until the clear is written.
                val pending = goAsync()
                onOff(intent.action!!.substringAfterLast('.')) { pending.finish() }
            }
            Intent.ACTION_SCREEN_ON -> onOn()
        }
    }

    fun register(context: Context) {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(this, f)
    }
}
