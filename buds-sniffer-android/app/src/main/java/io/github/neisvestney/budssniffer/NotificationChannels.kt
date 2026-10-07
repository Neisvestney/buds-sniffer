package io.github.neisvestney.budssniffer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

// Created upfront: with a single channel the system settings show no per-category toggles.
object NotificationChannels {
    const val BACKGROUND = "buds_background"
    const val LOW_BATTERY = "low_battery"

    fun create(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(BACKGROUND, "Background connection", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(LOW_BATTERY, "Low battery", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun openSettings(context: Context, channelId: String) {
        val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(
                Intent(app).setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(app)
        }
    }
}
