package io.github.neisvestney.budssniffer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.neisvestney.budssniffer.NotificationChannels
import io.github.neisvestney.budssniffer.buds.BatteryRepository
import io.github.neisvestney.budssniffer.buds.LinkStatus
import io.github.neisvestney.budssniffer.service.BudsAssociation
import io.github.neisvestney.budssniffer.service.BudsService
import io.github.neisvestney.budssniffer.ui.gauge.BudGaugePill
import io.github.neisvestney.budssniffer.widget.BudsWidget
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.POST_NOTIFICATIONS,
)

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun HomeScreen(onOpenProbe: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bluetooth by remember { mutableStateOf(context.has(Manifest.permission.BLUETOOTH_CONNECT)) }
    var notifications by remember { mutableStateOf(context.has(Manifest.permission.POST_NOTIFICATIONS)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bluetooth = context.has(Manifest.permission.BLUETOOTH_CONNECT)
        notifications = context.has(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Permissions can also be changed from system settings while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bluetooth = context.has(Manifest.permission.BLUETOOTH_CONNECT)
                notifications = context.has(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!bluetooth) {
            Text("Bluetooth permission is required")
            Button(onClick = { permissionLauncher.launch(PERMISSIONS) }) { Text("Grant permissions") }
            return@Column
        }
        if (!notifications) {
            Text("Without notifications there will be no low battery alerts", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { permissionLauncher.launch(PERMISSIONS) }) { Text("Allow notifications") }
        }
        BatteryCard()
        AssociationSection()
        if (notifications) {
            OutlinedButton(onClick = { NotificationChannels.openSettings(context, NotificationChannels.BACKGROUND) }) {
                Text("Background notification settings")
            }
        }
        OutlinedButton(onClick = onOpenProbe) { Text("Probe (debug)") }
    }
}

@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val battery by remember { BatteryRepository.battery(context) }.collectAsState(initial = null)
    val link by BatteryRepository.link.collectAsState()
    val live = link == LinkStatus.Connected

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BudGaugePill(battery, live)
        Text(
            "Link: $link" + (battery?.let {
                " · updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.updatedAt))}"
            } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun AssociationSection() {
    val context = LocalContext.current
    var associated by remember { mutableStateOf(BudsAssociation.currentAddress(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val scope = rememberCoroutineScope()

    val address = associated
    if (address != null) {
        Text("Earbuds: $address")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BudsService.start(context, address, force = true) }) { Text("Connect now") }
            OutlinedButton(onClick = { BudsService.stop(context) }) { Text("Stop") }
            OutlinedButton(onClick = {
                BudsService.stop(context)
                BudsAssociation.disassociate(context)
                associated = null
                scope.launch {
                    BatteryRepository.clear(context)
                    BudsWidget.refresh(context)
                }
            }) { Text("Unpair") }
        }
        return
    }

    Text("Pick your earbuds from paired devices:")
    // Not remembered: Bluetooth may be toggled or new buds paired while the screen is open.
    val bonded = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .sortedBy { it.name ?: it.address }
    for (device in bonded) {
        Text(
            "${device.name ?: "?"}  ${device.address}",
            modifier = Modifier.fillMaxWidth().clickable {
                error = null
                BudsAssociation.associate(
                    context,
                    device.address,
                    onPending = { senderLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                    onCreated = { info ->
                        val created = info.deviceMacAddress?.toString()?.uppercase() ?: device.address
                        associated = created
                        BudsService.start(context, created)
                    },
                    onFailure = { error = it },
                )
            }.padding(vertical = 8.dp),
        )
    }
    error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
}
