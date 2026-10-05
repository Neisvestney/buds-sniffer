package io.github.neisvestney.budssniffer.service

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build

object BudsAssociation {
    private fun cdm(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    // Re-pairing can leave older records behind; the newest one is the live pairing.
    fun current(context: Context): AssociationInfo? =
        cdm(context).myAssociations.filter { it.deviceMacAddress != null }.maxByOrNull { it.id }

    fun currentAddress(context: Context): String? = current(context)?.deviceMacAddress?.toString()?.uppercase()

    // The CDM dialog also lists bonded devices, so the already-paired buds show up without discovery.
    fun associate(
        context: Context,
        address: String,
        onPending: (IntentSender) -> Unit,
        onCreated: (AssociationInfo) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        cdm(context).associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                try {
                    startObserving(context, associationInfo)
                } catch (e: RuntimeException) {
                    onFailure("presence observing: ${e.message}")
                    return
                }
                onCreated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
        })
    }

    fun startObserving(context: Context, association: AssociationInfo) {
        val cdm = cdm(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            cdm.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
            )
        } else {
            val address = association.deviceMacAddress?.toString() ?: return
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(address)
        }
    }

    fun disassociate(context: Context) {
        val cdm = cdm(context)
        cdm.myAssociations.forEach { cdm.disassociate(it.id) }
    }
}
