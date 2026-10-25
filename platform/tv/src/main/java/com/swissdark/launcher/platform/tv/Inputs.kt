package com.swissdark.launcher.platform.tv

import android.content.Context
import android.content.Intent
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
class InputsAdapter(private val context: Context) : InputsRepository {
    private val tim: TvInputManager? = context.getSystemService(TvInputManager::class.java)

    override fun inputs(): List<TvInput> {
        val list = try {
            tim?.tvInputList.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        // CEC devices appear as child inputs of an HDMI port: use their names as the port's detail.
        val children = list.filter { it.parentId != null }.groupBy { it.parentId }
        val out = ArrayList<TvInput>()
        for (info in list) {
            if (info.parentId != null) continue
            val label = (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString()
            val state = try { tim?.getInputState(info.id) } catch (_: Exception) { null }
            val connected = state == TvInputManager.INPUT_STATE_CONNECTED || state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY
            val cec = children[info.id].orEmpty().map { (it.loadCustomLabel(context) ?: it.loadLabel(context)).toString() }
            val tuner = info.type == TvInputInfo.TYPE_TUNER || !info.isPassthroughInput
            val detail = when {
                tuner -> "Antenna"
                cec.isNotEmpty() -> cec.joinToString(" · ") + if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) " · Standby" else " · On"
                connected -> "Connected"
                else -> "Nothing connected"
            }
            out.add(TvInput(info.id, if (tuner) "TV" else label, detail, connected || tuner, tuner))
        }
        return out.sortedWith(compareBy<TvInput> { it.isTuner }.thenBy { it.label })
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
