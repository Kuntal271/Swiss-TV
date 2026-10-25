package com.swissdark.launcher.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GET on OkHttp's own threads (never the caller's): status code and body text.
 * Cancelling the coroutine cancels the call. Network failures throw IOException.
 */
internal suspend fun OkHttpClient.fetch(req: Request): Pair<Int, String> {
    val call = newCall(req)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use { it.code to it.body?.string().orEmpty() }
                } catch (e: IOException) {
                    cont.resumeWithException(e); return
                }
                cont.resume(r)
            }
        })
    }
}
