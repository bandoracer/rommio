package io.github.bandoracer.rommio

import android.app.Application

class RommNativeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        io.github.bandoracer.rommio.data.work.ProgressSyncWorker.start(this)
        container.warmCoreVerification()
    }
}
