package com.swissdark.launcher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * OTA self-update. A GitHub Release carries `update.json` (versionCode, name, url, sha256) next to
 * the APK; this checks it, and installs a newer build with a PackageInstaller session.
 * On Android 12 an app updating ITSELF with the same signature may set
 * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED] — silent, no dialog, no
 * "install unknown apps" grant. If the firmware refuses, the session reports pending user
 * action and the system's own installer dialog comes up (D-pad OK on "Install").
 *
 * The launcher is Home: a package update replaces the process and Home re-fires; the
 * accessibility fallback that holds Home survives package updates (only a force-stop stops it).
 */
class Updates(private val context: Context, private val http: OkHttpClient, private val scope: kotlinx.coroutines.CoroutineScope) {

    /** One release's metadata; the shape of `update.json`. Pure, unit-tested. */
    data class Meta(val versionCode: Int, val versionName: String, val url: String, val sha256: String?) {
        companion object {
            fun parse(text: String, currentCode: Int): Meta? = runCatching {
                val o = Json.parseToJsonElement(text).jsonObject
                Meta(
                    versionCode = o["versionCode"]?.jsonPrimitive?.int ?: return null,
                    versionName = o["versionName"]?.jsonPrimitive?.content ?: return null,
                    url = o["url"]?.jsonPrimitive?.content ?: return null,
                    sha256 = o["sha256"]?.jsonPrimitive?.content,
                )
            }.getOrNull()?.takeIf { it.versionCode > currentCode && it.url.startsWith("https://") }
        }
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val meta: Meta) : State
        data class Downloading(val percent: Int) : State
        data object Installing : State
        /** The firmware wants its own installer dialog; [installer] must start from the foreground. */
        data class Confirm(val installer: android.content.Intent) : State
        data class Failed(val why: String) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = mutable

    /** What the settings row shows; read in composition, so it recomposes as the state moves. */
    private val labelFlow = MutableStateFlow(labelOf(State.Idle))
    val label: StateFlow<String> get() = labelFlow

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    init {
        scope.launch { mutable.collect { labelFlow.value = labelOf(it) } }
    }

    private fun labelOf(s: State): String = when (s) {
        is State.Idle -> "OK checks"
        is State.Checking -> "Checking…"
        is State.UpToDate -> "Up to date"
        is State.Available -> "${s.meta.versionName} · OK installs"
        is State.Downloading -> "Downloading ${s.percent}%"
        is State.Installing -> "Installing…"
        is State.Confirm -> "Confirm · OK installs"
        is State.Failed -> "Failed · OK retries"
    }

    private fun currentCode(): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }

    /**
     * OK on the row: an offer installs, anything else checks first. [activity] — the screen the
     * OK was pressed on: this TV's app-start policy cancels activity starts that are not backed
     * by a user interaction, so the installer's dialog must be opened from the visible Activity,
     * not from the application context.
     */
    fun ok(activity: android.app.Activity? = null) {
        when (val s = mutable.value) {
            is State.Available -> install(s.meta)
            is State.Confirm -> {
                val intent = s.installer.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    if (activity != null) activity.startActivity(intent) else context.startActivity(intent)
                }.onFailure { mutable.value = State.Failed(it.message?.take(60) ?: "installer refused") }
            }
            else -> check(manual = true)
        }
    }

    /** Checks GitHub for a newer release, at most once a day unless [manual]. */
    fun maybeAutoCheck() {
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(24)) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (mutable.value is State.Checking || mutable.value is State.Downloading) return
        mutable.value = State.Checking
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    // Never the local HTTP cache, and a buster for GitHub's "latest" edge cache:
                    // a fresh release can otherwise read as "up to date" for minutes.
                    val url = METADATA + "?t=" + System.currentTimeMillis() / 1000
                    http.newCall(
                        Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
                    ).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body?.string() ?: error("empty")
                    }
                }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                val meta = Meta.parse(text, currentCode())
                mutable.value = if (meta != null) State.Available(meta) else State.UpToDate
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A silent auto-check never scolds: only a manual one surfaces the failure.
                if (manual) mutable.value = State.Failed("No update feed · OK retries") else mutable.value = State.Idle
            }
        }
    }

    /** Downloads, verifies and hands the APK to the package installer. */
    fun install(meta: Meta) {
        mutable.value = State.Downloading(0)
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { download(meta) }
                mutable.value = State.Installing
                withContext(Dispatchers.IO) { commit(apk) }
                // The package manager replaces the process from here; Home re-fires.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = State.Failed(e.message?.take(60) ?: "download failed")
            }
        }
    }

    /** Streams the APK into the cache while hashing it; returns the file or throws. */
    private fun download(meta: Meta): java.io.File {
        val out = java.io.File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(meta.url).build()).execute().use { r ->
            if (!r.isSuccessful) error("download HTTP ${r.code}")
            val body = r.body ?: error("empty download")
            val total = body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { src ->
                DigestInputStream(src, md).use { dig ->
                    out.outputStream().use { f ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = dig.read(buf)
                            if (n < 0) break
                            f.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                val cur = mutable.value
                                if (cur is State.Downloading && cur.percent != pct && pct % 5 == 0) mutable.value = State.Downloading(pct)
                            }
                        }
                    }
                }
            }
            meta.sha256?.let { want ->
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(want, ignoreCase = true)) error("checksum mismatch")
            }
        }
        return out
    }

    /** The PackageInstaller session; the TV installs the same package over itself. */
    private fun commit(apk: java.io.File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        // RECEIVER_NOT_EXPORTED is required from targetSdk 34; unknown on older platforms, where it is ignored.
        context.registerReceiver(receiver, IntentFilter(ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        val callback = PendingIntent.getBroadcast(
            context, sessionId,
            Intent(ACTION_STATUS).setPackage(context.packageName).putExtra("session", sessionId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session.openWrite("base", 0, apk.length()).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }
        session.commit(callback.intentSender)
        // No status within 25 s: the session died somewhere this TV does not report.
        scope.launch {
            kotlinx.coroutines.delay(25_000)
            if (mutable.value is State.Installing) mutable.value = State.Failed("installer did not answer")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            runCatching { c.unregisterReceiver(this) }
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // The firmware wants its own confirmation dialog. A background start from a
                    // receiver is canceled on this TV: hand the intent to the settings row, whose
                    // OK press starts it from the foreground.
                    val inner = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, android.content.Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    mutable.value = inner?.let { State.Confirm(it) } ?: State.Failed("installer gave no intent")
                }
                PackageInstaller.STATUS_SUCCESS -> mutable.value = State.Installing
                else -> {
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    mutable.value = State.Failed(msg?.take(60) ?: "install rejected")
                }
            }
        }
    }

    private companion object {
        const val METADATA = "https://github.com/Kuntal271/Swiss-TV/releases/latest/download/update.json"
        const val ACTION_STATUS = "com.swissdark.launcher.INSTALL_STATUS"
    }
}
