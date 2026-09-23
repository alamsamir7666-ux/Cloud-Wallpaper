package com.cloudimage.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CloudimageApplication : Application() {
    @Inject
    lateinit var bootstrapper: AppBootstrapper

    override fun onCreate() {
        super.onCreate()
        bootstrapper.start()
    }
}
