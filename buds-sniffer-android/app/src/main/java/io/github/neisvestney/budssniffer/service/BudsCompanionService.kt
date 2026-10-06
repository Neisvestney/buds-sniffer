package io.github.neisvestney.budssniffer.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.neisvestney.budssniffer.buds.BatteryRepository
import io.github.neisvestney.budssniffer.widget.BudsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Woken by the system when the associated buds connect / disconnect over Classic Bluetooth.
// Android 16 delivers onDevicePresenceEvent, 13–15 the older callbacks; both paths are idempotent.
class BudsCompanionService : CompanionDeviceService() {

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Log.d(TAG, "presence event ${event.event} for association ${event.associationId}")
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED, DevicePresenceEvent.EVENT_BLE_APPEARED ->
                addressOf(event.associationId)?.let { BudsService.start(this, it) }
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> BudsService.presenceLost(this)
            DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> {
                BudsService.stop(this)
                runBlocking { BatteryRepository.clear(this@BudsCompanionService) }
                val app = applicationContext
                scope.launch { BudsWidget.refresh(app) }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val address = associationInfo.deviceMacAddress?.toString()?.uppercase() ?: return
        Log.d(TAG, "appeared $address")
        BudsService.start(this, address)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(TAG, "disappeared ${associationInfo.deviceMacAddress}")
        BudsService.presenceLost(this)
    }

    private fun addressOf(associationId: Int): String? {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        return cdm.myAssociations.firstOrNull { it.id == associationId }
            ?.deviceMacAddress?.toString()?.uppercase()
    }

    private companion object {
        const val TAG = "BudsCompanion"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
