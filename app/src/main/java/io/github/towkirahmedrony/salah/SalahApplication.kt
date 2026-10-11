package io.github.towkirahmedrony.salah

import android.app.Application
import io.github.towkirahmedrony.salah.di.salahModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application entry point.
 *
 * Dependency wiring is started once here, before any UI exists, so every screen resolves its
 * dependencies from the container instead of constructing them itself.
 */
class SalahApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@SalahApplication)
            modules(salahModules)
        }
    }
}
