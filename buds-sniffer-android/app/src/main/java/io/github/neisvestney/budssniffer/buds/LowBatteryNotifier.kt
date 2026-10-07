package io.github.neisvestney.budssniffer.buds

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.neisvestney.budssniffer.MainActivity
import io.github.neisvestney.budssniffer.NotificationChannels
import io.github.neisvestney.budssniffer.R

// Alerts once per discharge cycle and per bud; re-arms after charging or recovering above the threshold.
object LowBatteryNotifier {
    const val THRESHOLD = 20
    private const val REARM_MARGIN = 5

    private enum class Side(val label: String, val notificationId: Int) {
        Left("Left", 2001),
        Right("Right", 2002),
    }

    private fun alertedKey(side: Side) = booleanPreferencesKey("low_alerted_${side.name.lowercase()}")

    suspend fun check(context: Context, battery: BudsBattery) {
        check(context, Side.Left, battery.left)
        check(context, Side.Right, battery.right)
    }

    private suspend fun check(context: Context, side: Side, level: BudLevel?) {
        // Without permission nothing would be shown, so keep this cycle's single alert unspent.
        if (level == null || !canNotify(context)) return
        val key = alertedKey(side)
        var fire = false
        context.budsStore.edit { prefs ->
            val alerted = prefs[key] ?: false
            when {
                level.charging || level.percent > THRESHOLD + REARM_MARGIN -> prefs[key] = false
                !alerted && level.percent <= THRESHOLD -> {
                    prefs[key] = true
                    fire = true
                }
            }
        }
        if (fire) notify(context, side, level.percent)
    }

    private fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, side: Side, percent: Int) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.LOW_BATTERY)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle("${side.label} earbud: $percent%")
            .setContentText("Battery is running low")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        NotificationManagerCompat.from(context).notify(side.notificationId, notification)
    }
}
