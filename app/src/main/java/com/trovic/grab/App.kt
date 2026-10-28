package com.trovic.grab

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
    }
}
