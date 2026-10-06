package io.github.neisvestney.budssniffer.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetManager.Companion.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

// Separate from budsStore: that one is wiped on unpair.
private val Context.previewStore by preferencesDataStore("widget_previews")

object WidgetPreviews {
    private const val TAG = "WidgetPreviews"
    private val receivers = listOf(BudsWidgetReceiver::class, AutoHideBudsWidgetReceiver::class)

    // setWidgetPreviews is rate-limited, so each receiver is published once per install/update.
    suspend fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        try {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val published = context.previewStore.data.first()
            val manager = GlanceAppWidgetManager(context)
            for (receiver in receivers) {
                val key = longPreferencesKey("published_${receiver.simpleName}")
                if (published[key] == installed) continue
                if (manager.setWidgetPreviews(receiver) == SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
                    context.previewStore.edit { it[key] = installed }
                } else {
                    Log.w(TAG, "Rate-limited publishing preview for ${receiver.simpleName}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish widget previews", e)
        }
    }
}
