package io.github.neisvestney.budssniffer

import android.app.Application

class BudsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
