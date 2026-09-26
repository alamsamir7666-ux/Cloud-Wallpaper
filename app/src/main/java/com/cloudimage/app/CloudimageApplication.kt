package com.cloudimage.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cloudimage.core.network.ForegroundActivityTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CloudimageApplication :
    Application(),
    Configuration.Provider {
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
}
