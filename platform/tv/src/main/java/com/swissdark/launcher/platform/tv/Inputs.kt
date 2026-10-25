package com.swissdark.launcher.platform.tv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import com.swissdark.launcher.domain.InputsRepository
import com.swissdark.launcher.domain.LaunchResult
import com.swissdark.launcher.domain.TvInput

/**
 * TvInputManager.getTvInputList for listing (no permission needed); switching goes through
 * buildChannelUriForPassthroughInput, which TCL's own TV app (com.tcl.tv) handles.
 */
private val HW = Regex("/HW(\\d+)$")

class InputsAdapter(private val context: Context) : InputsRepository {
    private val tim: TvInputManager? = context.getSystemService(TvInputManager::class.java)

    /** Names of child (CEC) inputs; loadLabel loads another package's resources, so cache it. */
    private val labels = HashMap<String, String>()

    private fun label(info: TvInputInfo): String {
        info.loadCustomLabel(context)?.let { return it.toString() } // user-renamed: read fresh
        return synchronized(labels) { labels.getOrPut(info.id) { info.loadLabel(context).toString() } }
    }

    /** Hardware inputs come from a system TV input service (com.tcl.tvinput on this TV). */
    private fun isSystem(info: TvInputInfo): Boolean =
        (info.serviceInfo?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

    /** "…/HW15" -> 15: the HAL device id, in port order on TCL/Amlogic firmware. */
    private fun hwNumber(id: String): Int = HW.find(id)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private enum class Port { HDMI, AV, TUNER, OTHER }

    private fun port(info: TvInputInfo): Port = when (info.type) {
        TvInputInfo.TYPE_HDMI -> Port.HDMI
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SCART -> Port.AV
        TvInputInfo.TYPE_TUNER -> Port.TUNER
        else -> if (info.isPassthroughInput) Port.OTHER else Port.TUNER
    }

    /**
     * One "TV" (the system tuner), then "HDMI 1…n" in port order (CEC device names when known),
     * then "AV". Inputs published by apps (Play Movies, VLC, IPTV previews) are left out: they
     * are not sources on the TV and showed up as extra "TV · Antenna" rows.
     */
    override fun inputs(): List<TvInput> {
        val list = try {
            tim?.tvInputList.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        // CEC devices appear as child inputs of an HDMI port: use their names as the port's detail.
        val children = list.filter { it.parentId != null }.groupBy { it.parentId }
        val roots = list.filter { it.parentId == null && isSystem(it) }.sortedBy { hwNumber(it.id) }
        val out = ArrayList<TvInput>()
        var hdmi = 0
        var tunerAdded = false
        for (info in roots) {
            val kind = port(info)
            if (kind == Port.TUNER) {
                if (tunerAdded) continue
                tunerAdded = true
                out.add(TvInput(info.id, info.loadCustomLabel(context)?.toString() ?: "TV", "Antenna", true, true))
                continue
            }
            val state = try { tim?.getInputState(info.id) } catch (_: Exception) { null }
            val connected = state == TvInputManager.INPUT_STATE_CONNECTED || state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY
            // CEC device names follow whatever is plugged in: not cached.
            val cec = children[info.id].orEmpty().map { (it.loadCustomLabel(context) ?: it.loadLabel(context)).toString() }.filter { it.isNotBlank() }
            val n = if (kind == Port.HDMI) ++hdmi else 0
            val name = info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: when (kind) {
                Port.HDMI -> "HDMI $n"
                Port.AV -> "AV"
                else -> label(info)
            }
            val detail = when {
                cec.isNotEmpty() -> cec.joinToString(" · ") + if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) " · Standby" else " · On"
                state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Standby"
                connected -> "Connected"
                else -> "Nothing connected"
            }
            out.add(TvInput(info.id, name, detail, connected, false))
        }
        val order = mapOf(Port.HDMI to 0, Port.AV to 1, Port.OTHER to 2, Port.TUNER to 3)
        val kindById = roots.associate { it.id to port(it) }
        return out.sortedBy { order[kindById[it.id]] ?: 2 } // stable: keeps port order within a kind
    }

    override fun switchTo(input: TvInput): LaunchResult {
        val uri = if (input.isTuner) TvContract.buildChannelsUriForInput(input.id) else TvContract.buildChannelUriForPassthroughInput(input.id)
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            LaunchResult.Ok
        } catch (e: Exception) {
            // Fall back to TCL's TV app in front.
            val tv = context.packageManager.getLeanbackLaunchIntentForPackage("com.tcl.tv")
            if (tv != null) {
                context.startActivity(tv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                LaunchResult.OpenedAppHome
            } else LaunchResult.Failed(e.message ?: "no TV app")
        }
    }
}
