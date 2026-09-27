package com.cloudimage.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cloudimage.core.network.ForegroundActivityTracker
import com.cloudimage.core.network.ImageProgressRegistry
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CloudimageApplication :
    Application(),
    Configuration.Provider,
    ImageLoaderFactory {
    @Inject
    lateinit var bootstrapper: AppBootstrapper

    /**
     * The resumed-activity memory behind the app-side Cloudflare bypass —
     * the solver's challenge dialog needs a real window to attach to, and
     * this tracker is where it finds one from deep in the network stack.
     */
    @Inject
    lateinit var activityTracker: ForegroundActivityTracker

    /**
     * Builds workers with Hilt so [com.cloudimage.core.data.rotation.WallpaperRotateWorker]
     * gets its collaborators injected. The default WorkManager initializer is
     * disabled in the manifest, which makes on-demand initialization use
     * this configuration.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * The app's OkHttp client, shared with Coil so image loads ride the
     * same connection pool and Cloudflare-aware configuration.
     */
    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(activityTracker)
        bootstrapper.start()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    /**
     * The app-wide Coil loader (v1.0.17). Deriving from the app's client
     * keeps the connection pool shared; two differences matter for images:
     *
     * - the 30s call timeout is dropped — full-size wallpaper originals are
     *   multi-megabyte downloads that legitimately outlast it on slow
     *   networks, and the per-read timeout already guards stalls;
     * - [ImageProgressRegistry]'s interceptor rides along so the fullscreen
     *   viewer can report byte-accurate loading progress. It is a no-op
     *   pass-through for every URL nobody observes.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .okHttpClient(
                okHttpClient
                    .newBuilder()
                    .callTimeout(0, TimeUnit.SECONDS)
                    .addInterceptor(ImageProgressRegistry.interceptor())
                    .build(),
            ).build()
}
