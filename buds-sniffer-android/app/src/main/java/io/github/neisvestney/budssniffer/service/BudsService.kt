package io.github.neisvestney.budssniffer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.neisvestney.budssniffer.MainActivity
import io.github.neisvestney.budssniffer.R
import io.github.neisvestney.budssniffer.buds.BatteryRepository
import io.github.neisvestney.budssniffer.buds.BudsBattery
import io.github.neisvestney.budssniffer.buds.LinkStatus
import io.github.neisvestney.budssniffer.buds.LowBatteryNotifier
import io.github.neisvestney.budssniffer.buds.summary
import io.github.neisvestney.budssniffer.rcsp.AdvInfo
import io.github.neisvestney.budssniffer.rcsp.RcspLink
import io.github.neisvestney.budssniffer.widget.BudsWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Keeps the RCSP link to the buds alive while they are connected and publishes battery updates.
@SuppressLint("MissingPermission")
class BudsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var linkJob: Job? = null
    private var address: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_MIN),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newAddress = intent?.getStringExtra(EXTRA_ADDRESS)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(statusText()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        dismissed = false
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true &&
            BatteryRepository.link.value != LinkStatus.Connected
        if (newAddress != null && (force || newAddress != address || linkJob?.isActive != true)) {
            address = newAddress
            owner = this
            runningAddress = newAddress
            linkJob?.cancel()
            linkJob = scope.launch { runLink(newAddress, restartStall = force) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // A newer instance may already own the static state.
        if (owner === this) {
            owner = null
            runningAddress = null
        }
        setLink(LinkStatus.Idle)
        super.onDestroy()
    }

    private suspend fun runLink(address: String, restartStall: Boolean) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            stopSelf()
            return
        }
        val device = adapter.getRemoteDevice(address)
        var backoffMs = INITIAL_BACKOFF_MS

        setLink(LinkStatus.Connecting, restartStall)
        while (true) {
            setLink(LinkStatus.Connecting)
            session = null
            lastShown = null
            val link = RcspLink(this, device) { Log.d(TAG, it) }
            try {
                coroutineScope {
                    link.open(this)
                    link.authenticate()
                    setLink(LinkStatus.Connected)
                    backoffMs = INITIAL_BACKOFF_MS
                    for (p in link.packets) {
                        if (p.opcode != AdvInfo.CMD_NOTIFY_ADV_INFO) continue
                        AdvInfo.parse(p.params)?.let { onBattery(it) }
                    }
                    Log.d(TAG, "link closed")
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "link timeout: ${e.message}")
            } catch (e: CancellationException) {
                link.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link failed: ${e.message}")
            }
            link.close()
            setLink(LinkStatus.Connecting)
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    // Merged only within one connection so stale levels from a previous session never look live.
    private var session: BudsBattery? = null
    private var lastShown: BudsBattery? = null
    private var lastStoredAt = 0L

    private suspend fun onBattery(fresh: BudsBattery) {
        val previous = session
        val merged = fresh.mergedOnto(previous)
        session = merged
        val changed = merged.copy(updatedAt = 0) != previous?.copy(updatedAt = 0)
        if (changed || merged.updatedAt - lastStoredAt >= STORE_HEARTBEAT_MS) {
            BatteryRepository.store(this, merged)
            lastStoredAt = merged.updatedAt
        }
        if (merged.copy(updatedAt = 0) == lastShown?.copy(updatedAt = 0)) return
        lastShown = merged
        showStatus()
        LowBatteryNotifier.check(this, merged)
        BudsWidget.refresh(this)
    }

    private var stallJob: Job? = null

    // Stalled is cosmetic: retries keep going, the UI just stops pretending a connect is imminent.
    private fun setLink(status: LinkStatus, restartStall: Boolean = false) {
        val previous = BatteryRepository.link.value
        if (status == LinkStatus.Connecting && previous == LinkStatus.Stalled && !restartStall) return
        BatteryRepository.setLink(status)

        if (status == LinkStatus.Connecting) {
            if (restartStall) stallJob?.cancel()
            if (stallJob?.isActive != true) {
                stallJob = scope.launch {
                    delay(STALL_TIMEOUT_MS)
                    setLink(LinkStatus.Stalled)
                }
            }
        } else {
            stallJob?.cancel()
        }

        if (status != LinkStatus.Idle) showStatus()
        // The widget greys out levels while not connected; its Glance session may be gone, so push a refresh.
        if ((previous == LinkStatus.Connected) != (status == LinkStatus.Connected)) {
            val app = applicationContext
            refreshScope.launch { BudsWidget.refresh(app) }
        }
    }

    private fun statusText(): String = when (BatteryRepository.link.value) {
        LinkStatus.Connected -> lastShown?.summary() ?: "Connected"
        LinkStatus.Stalled -> "Can't reach the earbuds, still retrying"
        else -> "Connecting…"
    }

    private fun showStatus() {
        if (dismissed) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(statusText()))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(text)
            .setContentIntent(open)
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    this, 1, Intent(this, NotificationDismissedReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "BudsService"
        private const val CHANNEL_ID = "buds_background"
        private const val OLD_CHANNEL_ID = "buds_link"
        private const val NOTIFICATION_ID = 1001
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STALL_TIMEOUT_MS = 20_000L
        private const val STORE_HEARTBEAT_MS = 60_000L
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_FORCE = "force"

        // Once swiped away (Android 14+ allows it for FGS) stay hidden until the service is started again.
        @Volatile internal var dismissed = false
        @Volatile private var runningAddress: String? = null
        @Volatile private var owner: BudsService? = null
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Re-starting a running FGS re-posts its notification, so presence events must not do it needlessly.
        // force: user asked to reconnect now, so skip the pending backoff.
        fun start(context: Context, address: String, force: Boolean = false) {
            if (!force && runningAddress == address) return
            context.startForegroundService(
                Intent(context, BudsService::class.java)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_FORCE, force),
            )
        }

        fun stop(context: Context) {
            runningAddress = null
            context.stopService(Intent(context, BudsService::class.java))
        }
    }
}

class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BudsService.dismissed = true
    }
}
