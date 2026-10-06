package io.github.neisvestney.budssniffer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// True if A2DP or HFP is connected to the device. Callers must hold BLUETOOTH_CONNECT.
@SuppressLint("MissingPermission")
suspend fun isClassicConnected(context: Context, device: BluetoothDevice): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    return listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any { profile ->
        val proxy = withTimeoutOrNull(2_000) { adapter.awaitProfileProxy(context, profile) }
            ?: return@any false
        try {
            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        } finally {
            adapter.closeProfileProxy(profile, proxy)
        }
    }
}

private suspend fun BluetoothAdapter.awaitProfileProxy(context: Context, profile: Int): BluetoothProfile? =
    suspendCancellableCoroutine { cont ->
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                if (cont.isActive) cont.resume(proxy) else closeProfileProxy(p, proxy)
            }

            override fun onServiceDisconnected(p: Int) {}
        }
        if (!getProfileProxy(context, listener, profile)) cont.resume(null)
    }
