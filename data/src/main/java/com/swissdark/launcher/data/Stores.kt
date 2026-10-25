package com.swissdark.launcher.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.swissdark.launcher.core.perf.Sections
import com.swissdark.launcher.core.perf.traced
import com.swissdark.launcher.domain.HomeSnapshot
import com.swissdark.launcher.domain.LauncherSettings
import com.swissdark.launcher.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

@OptIn(ExperimentalSerializationApi::class)
private object SettingsSerializer : Serializer<LauncherSettings> {
    override val defaultValue = LauncherSettings()
    override suspend fun readFrom(input: InputStream): LauncherSettings = try {
        ProtoBuf.decodeFromByteArray(LauncherSettings.serializer(), input.readBytes())
    } catch (e: Exception) {
        throw CorruptionException("settings", e)
    }

    override suspend fun writeTo(t: LauncherSettings, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(LauncherSettings.serializer(), t))
    }
}

/**
 * Proto DataStore (protobuf via kotlinx.serialization): pins, hidden apps, services you pay
 * for, wallpaper per slot, dim levels. The in-memory copy is what the UI reads.
 */
@OptIn(ExperimentalSerializationApi::class)
class SettingsStore(context: Context, private val scope: CoroutineScope) : SettingsRepository {
    private val file = File(context.filesDir, "settings.pb")
    private val store: DataStore<LauncherSettings> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { LauncherSettings() },
        scope = scope,
        produceFile = { file },
    )

    // First read is synchronous and tiny (< 10 KB), so the first frame has the real settings.
    private val _settings = MutableStateFlow(
        try {
            if (file.exists()) ProtoBuf.decodeFromByteArray(LauncherSettings.serializer(), file.readBytes()) else LauncherSettings()
        } catch (_: Exception) {
            LauncherSettings()
        },
    )
    override val settings: StateFlow<LauncherSettings> = _settings

    override fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        scope.launch { store.updateData { next } }
    }
}

/**
 * L2: the last HomeSnapshot as one protobuf file, read through a memory map at cold start.
 * Writes are debounced 2 s after each L1 change.
 */
@OptIn(ExperimentalSerializationApi::class)
class SnapshotStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "home.snapshot.pb")
    private var pending: Job? = null

    fun read(): HomeSnapshot? = traced(Sections.SNAPSHOT_READ) {
        if (!file.exists() || file.length() == 0L) return@traced null
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                ProtoBuf.decodeFromByteArray(HomeSnapshot.serializer(), bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeDebounced(s: HomeSnapshot) {
        pending?.cancel()
        pending = scope.launch {
            delay(2_000)
            write(s)
        }
    }

    /** Before standby: the process may be frozen before a debounced write would run. */
    fun writeNow(s: HomeSnapshot) {
        pending?.cancel()
        write(s)
    }

    private fun write(s: HomeSnapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(ProtoBuf.encodeToByteArray(HomeSnapshot.serializer(), s))
        tmp.renameTo(file)
    }
}
