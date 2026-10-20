package com.cloudimage.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cloudimage.core.network.CloudflareBypasser
import com.cloudimage.core.network.CloudimageHttpClient
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

    /**
     * The Cloudflare state machine — the source of the clearance this
     * app's image loader replays under (v1.2.11).
     */
    @Inject
    lateinit var cloudflareBypasser: CloudflareBypasser

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
     *
     * - the read timeout is loosened to 45s (v1.2.26): the default 20s gap
     *   between bytes kills exactly the transfers this app exists for — a
     *   several-megabyte original crawling down a congested mobile link
     *   stops and starts, and a 20s stall mid-transfer surfaced as "Tap to
     *   retry" for images that were still moving. 45s keeps a genuinely
     *   dead connection failing in under a minute while slow-but-alive
     *   ones complete;
     *
     * - [ImageProgressRegistry]'s interceptor rides along so the fullscreen
     *   viewer can report byte-accurate loading progress. It is a no-op
     *   pass-through for every URL nobody observes.
     *
     * v1.2.9 adds the User-Agent brander: Coil builds its own requests, so
     * the app client's per-request agent never rode along — and image hosts
     * with a UA policy (Wikimedia's upload CDN 403s anything undescriptive)
     * rejected exactly those loads. The interceptor only fills the header
     * when absent, so explicitly-branded requests (the Cloudflare clearance
     * replay) keep the agent they earned.
     *
     * v1.2.11 puts the earned clearance itself on image traffic. Image
     * loads never ride the document ladder, so a source whose image CDN
     * sits in the same Cloudflare zone as its site (wallpapersden.com's
     * `images.` host) used to ask for thumbnails with neither the
     * clearance cookies nor the identity they were earned under — the
     * grid came back blank while documents flowed. The interceptor now
     * consults the bypasser's zone snapshot first: when the zone has a
     * clearance on file, the image request wears its User-Agent and
     * carries its cookies, exactly as the ladder's own replays do;
     * clearance binding wins over branding, the same rule the provider
     * facade follows. Hosts with no clearance brand as before.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .okHttpClient(
                okHttpClient
                    .newBuilder()
                    .callTimeout(0, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .addInterceptor(ImageProgressRegistry.interceptor())
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val clearance = cloudflareBypasser.currentBypassFor(request.url.host)
                        val branded =
                            when {
                                clearance != null ->
                                    request
                                        .newBuilder()
                                        .header("User-Agent", clearance.userAgent)
                                        .header("Cookie", clearance.cookieHeader)
                                        .build()
                                request.header("User-Agent") == null ->
                                    request
                                        .newBuilder()
                                        .header("User-Agent", CloudimageHttpClient.USER_AGENT)
                                        .build()
                                else -> request
                            }
                        chain.proceed(branded)
                    }.build(),
            ).build()
}
