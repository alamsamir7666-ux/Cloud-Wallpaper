package com.cloudimage.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CloudimageApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var bootstrapper: AppBootstrapper

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
        bootstrapper.start()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
