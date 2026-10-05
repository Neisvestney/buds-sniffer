package io.github.neisvestney.budssniffer.probe

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.neisvestney.budssniffer.buds.summary

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
fun ProbeScreen(modifier: Modifier = Modifier, vm: ProbeViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!granted) {
            Button(onClick = { launcher.launch(PERMISSIONS) }) { Text("Grant Bluetooth permissions") }
            return@Column
        }
        DevicesSection(vm)
        HorizontalDivider()
        ControlsSection(vm)
        HorizontalDivider()
        LogSection(vm, Modifier.weight(1f))
    }
}

@Composable
private fun DevicesSection(vm: ProbeViewModel) {
    val scanning by vm.scanning.collectAsState()
    val devices by vm.devices.collectAsState()
    var showAll by rememberSaveable { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) {
            Text(if (scanning) "Stop scan" else "Scan")
        }
        Checkbox(checked = showAll, onCheckedChange = { showAll = it })
        Text("all devices")
    }
    val shown = devices.values.filter { showAll || it.isCandidate }.sortedByDescending { it.rssi }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
        items(shown, key = { it.address }) { d ->
            Column(Modifier.fillMaxWidth().clickable { vm.targetAddress.value = d.address }.padding(vertical = 4.dp)) {
                Text("${d.address}  ${d.rssi} dBm  ${d.name ?: ""}${if (d.hasAe00) "  [AE00]" else ""}")
                d.edrMac?.let { Text("  EDR $it", style = MaterialTheme.typography.bodySmall) }
                d.adBattery?.let { Text("  ad: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ControlsSection(vm: ProbeViewModel) {
    val address by vm.targetAddress.collectAsState()
    val link by vm.link.collectAsState()
    val battery by vm.battery.collectAsState()
    var rawHex by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = address,
        onValueChange = { vm.targetAddress.value = it },
        label = { Text("LE MAC") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Link: $link" + (battery?.let { "   ${it.summary()}" } ?: ""))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.connect() }, enabled = link == LinkState.Idle) { Text("Connect") }
        Button(onClick = { vm.authenticate() }, enabled = link == LinkState.Connected) { Text("Auth") }
        Button(onClick = { vm.getTargetInfo() }, enabled = link != LinkState.Idle && link != LinkState.Connecting) {
            Text("GetTargetInfo")
        }
        OutlinedButton(onClick = { vm.disconnect() }, enabled = link != LinkState.Idle) { Text("Disconnect") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = rawHex,
            onValueChange = { rawHex = it },
            label = { Text("raw hex → write char") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { vm.sendRaw(rawHex) },
            enabled = link != LinkState.Idle && link != LinkState.Connecting,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Send") }
    }
}

@Composable
private fun LogSection(vm: ProbeViewModel, modifier: Modifier) {
    val log by vm.log.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }

    Row {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
        OutlinedButton(onClick = { vm.clearLog() }, modifier = Modifier.padding(start = 8.dp)) { Text("Clear") }
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
