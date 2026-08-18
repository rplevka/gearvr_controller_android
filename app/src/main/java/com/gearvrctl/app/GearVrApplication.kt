package com.gearvrctl.app

import android.app.Application
import com.gearvrctl.app.ble.ControllerRepository
import com.gearvrctl.app.config.SettingsRepository

class GearVrApplication : Application() {
    lateinit var controllerRepository: ControllerRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        controllerRepository = ControllerRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
    }
}
