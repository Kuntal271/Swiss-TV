package com.swissdark.launcher

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import com.swissdark.launcher.data.DataGraph
import okio.Path.Companion.toOkioPath

class LauncherApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var graph: DataGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = DataGraph(this, BuildConfig.TMDB_KEY)
        DataGraph.instance = graph
        // Off the main thread, in parallel with activity + Compose start-up: the wallpaper map,
        // settings, the Home snapshot, Up next banners, and the image loader with Up next art.
        graph.prewarm { urls ->
            val loader = SingletonImageLoader.get(this)
            urls.forEach { loader.enqueue(ImageRequest.Builder(this).data(it).size(480, 270).build()) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil trims its own memory cache; these are ours. The wallpaper stays: warm Home needs it.
        graph.trimMemory(level)
    }

    // WorkManager starts on first use, not at process start: keeps cold start short.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    /**
     * Coil: exact-size decodes (each Art call asks for the drawn size), hardware bitmaps,
     * a small memory cache for visible items ±2 and a 150 MB disk cache.
     */
    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(150L * 1024 * 1024).build() }
            .build()
}
