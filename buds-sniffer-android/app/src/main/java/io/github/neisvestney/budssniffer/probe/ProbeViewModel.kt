package io.github.neisvestney.budssniffer.probe

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.BudsBattery
import io.github.neisvestney.budssniffer.rcsp.AdvInfo
import io.github.neisvestney.budssniffer.rcsp.RcspAttrs
import io.github.neisvestney.budssniffer.rcsp.RcspFrame
import io.github.neisvestney.budssniffer.rcsp.RcspLink
import io.github.neisvestney.budssniffer.rcsp.RcspPacket
import io.github.neisvestney.budssniffer.rcsp.hexToBytes
import io.github.neisvestney.budssniffer.rcsp.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanItem(
    val address: String,
    val name: String?,
    val rssi: Int,
    val xiaomiPayload: ByteArray?,
    val hasAe00: Boolean,
) {
    val isCandidate get() = xiaomiPayload != null || hasAe00

    // Fast Connect payload: [11..16] is the EDR MAC with a byte shuffle (see REVERSE_NOTES 1b)
    val edrMac: String?
        get() = xiaomiPayload?.takeIf { it.size >= 17 }?.let { p ->
            intArrayOf(12, 11, 13, 16, 15, 14).joinToString(":") { "%02X".format(p[it].toInt() and 0xFF) }
        }

    val adBattery: String?
        get() = xiaomiPayload?.takeIf { it.size >= 24 && it[1].toInt() == 0x01 }?.let { p ->
            fun b(i: Int) = (p[i].toInt() and 0x7F).coerceAtMost(100)
            "L ${b(5)} / R ${b(6)} / case ${b(7)}"
        }
}

enum class LinkState { Idle, Connecting, Connected, Authenticated }

@SuppressLint("MissingPermission")
class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _devices = MutableStateFlow<Map<String, ScanItem>>(emptyMap())
    val devices: StateFlow<Map<String, ScanItem>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _link = MutableStateFlow(LinkState.Idle)
    val link: StateFlow<LinkState> = _link

    private val _battery = MutableStateFlow<BudsBattery?>(null)
    val battery: StateFlow<BudsBattery?> = _battery

    val targetAddress = MutableStateFlow("")

    private var rcsp: RcspLink? = null
    private val linkJobs = mutableListOf<Job>()
    private var lastAdvParams: ByteArray? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        Log.d("BudsProbe", msg)
        _log.update { it + "${timeFormat.format(Date())}  $msg" }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val item = ScanItem(
                address = result.device.address,
                name = record?.deviceName ?: result.device.name,
                rssi = result.rssi,
                xiaomiPayload = record?.getManufacturerSpecificData(XIAOMI_COMPANY_ID),
                hasAe00 = record?.serviceUuids?.contains(ParcelUuid(RcspLink.AE00)) == true,
            )
            _devices.update { it + (item.address to item) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            log("scan failed: $errorCode")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return log("Bluetooth is off or unavailable")
        _devices.value = emptyMap()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        _scanning.value = true
        log("scan started")
    }

    fun stopScan() {
        if (!_scanning.value) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _scanning.value = false
        log("scan stopped")
    }

    fun connect() = viewModelScope.launch {
        val address = targetAddress.value.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return@launch log("invalid MAC: $address")
        val device = adapter?.getRemoteDevice(address) ?: return@launch log("Bluetooth unavailable")
        stopScan()
        disconnectInternal()

        val link = RcspLink(getApplication(), device, ::log)
        rcsp = link
        _link.value = LinkState.Connecting
        log("connecting to $address (LE)…")
        linkJobs += launch {
            try {
                link.open(this)
                _link.value = LinkState.Connected
                for (p in link.packets) describe(p)
                if (rcsp === link) log("link lost")
            } catch (e: TimeoutCancellationException) {
                log("connect timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("connect error: ${e.message}")
            }
            if (rcsp === link) disconnectInternal()
        }
    }

    fun authenticate() = viewModelScope.launch {
        val link = rcsp ?: return@launch log("not connected")
        try {
            link.authenticate()
            _link.value = LinkState.Authenticated
        } catch (e: Exception) {
            log("auth failed: ${e.message}")
        }
    }

    fun getTargetInfo() = sendLogged { RcspFrame.getTargetInfo(it.nextSn()) }

    fun sendRaw(hex: String) = sendLogged { hex.hexToBytes() }

    fun disconnect() = viewModelScope.launch {
        disconnectInternal()
        log("disconnected by user")
    }

    private fun sendLogged(build: (RcspLink) -> ByteArray) = viewModelScope.launch {
        val link = rcsp ?: return@launch log("not connected")
        runCatching { link.send(build(link)) }.onFailure { log("send error: ${it.message}") }
    }

    private fun describe(p: RcspPacket) {
        if (p.opcode == AdvInfo.CMD_NOTIFY_ADV_INFO) {
            AdvInfo.parse(p.params)?.let { _battery.value = it.mergedOnto(_battery.value) }
            // the push repeats every ~0.5 s, so only changes are worth a log line
            if (p.params.contentEquals(lastAdvParams)) return
            lastAdvParams = p.params
        }
        val kind = if (p.isCommand) "cmd" else "resp status=${p.status}"
        log("  pkt op=0x%02X %s sn=%d%s params=%s".format(
            p.opcode, kind, p.sn, if (p.needsResponse) " (wants reply)" else "", p.params.toHex(),
        ))
        if (p.opcode == AdvInfo.CMD_NOTIFY_ADV_INFO) return
        val attrs = RcspAttrs.parse(p.params) ?: return
        for (a in attrs) {
            log("    attr type=${a.type}: ${a.value.toHex()}")
            if (a.type == RcspAttrs.ATTR_TYPE_MULT_BATTERY) {
                val b = RcspAttrs.multiBattery(a.value)
                _battery.value = BudsBattery(
                    b.left?.let { BudLevel(it, false) },
                    b.right?.let { BudLevel(it, false) },
                    b.case?.let { BudLevel(it, false) },
                    System.currentTimeMillis(),
                )
            }
        }
    }

    private fun disconnectInternal() {
        linkJobs.forEach { it.cancel() }
        linkJobs.clear()
        rcsp?.close()
        rcsp = null
        lastAdvParams = null
        _link.value = LinkState.Idle
    }

    override fun onCleared() {
        stopScan()
        disconnectInternal()
    }

    companion object {
        const val XIAOMI_COMPANY_ID = 0x038F
    }
}
