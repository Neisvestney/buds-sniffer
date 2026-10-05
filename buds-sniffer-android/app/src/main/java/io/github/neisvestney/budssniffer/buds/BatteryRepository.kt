package io.github.neisvestney.budssniffer.buds

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

internal val Context.budsStore by preferencesDataStore("buds")

enum class LinkStatus { Idle, Connecting, Stalled, Connected }

object BatteryRepository {
    private val _link = MutableStateFlow(LinkStatus.Idle)
    val link: StateFlow<LinkStatus> = _link

    private val UPDATED_AT = longPreferencesKey("updated_at")

    private class LevelKeys(name: String) {
        val percent = intPreferencesKey("${name}_percent")
        val charging = booleanPreferencesKey("${name}_charging")
    }

    private val LEFT = LevelKeys("left")
    private val RIGHT = LevelKeys("right")
    private val CASE = LevelKeys("case")

    fun setLink(status: LinkStatus) {
        _link.value = status
    }

    fun battery(context: Context): Flow<BudsBattery?> = context.budsStore.data.map { it.toBattery() }

    suspend fun store(context: Context, battery: BudsBattery) {
        context.budsStore.edit { prefs ->
            prefs[UPDATED_AT] = battery.updatedAt
            prefs.put(LEFT, battery.left)
            prefs.put(RIGHT, battery.right)
            prefs.put(CASE, battery.case)
        }
    }

    // Also drops the low-battery alert flags kept in the same store.
    suspend fun clear(context: Context) {
        context.budsStore.edit { it.clear() }
    }

    private fun Preferences.toBattery(): BudsBattery? {
        val ts = this[UPDATED_AT] ?: return null
        return BudsBattery(level(LEFT), level(RIGHT), level(CASE), ts)
    }

    private fun Preferences.level(keys: LevelKeys): BudLevel? {
        val percent = this[keys.percent] ?: return null
        return BudLevel(percent, this[keys.charging] ?: false)
    }

    private fun MutablePreferences.put(keys: LevelKeys, level: BudLevel?) {
        if (level == null) {
            remove(keys.percent)
            remove(keys.charging)
        } else {
            this[keys.percent] = level.percent
            this[keys.charging] = level.charging
        }
    }
}
