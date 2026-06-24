package com.bingwa.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class HotspotRelayService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private val clientExecutor = Executors.newFixedThreadPool(4)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = RelayManager.load(this)
        if (!cfg.enabled || cfg.role != "RELAY" || cfg.method != "HOTSPOT") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        serverThread = Thread({ runServerLoop() }, "BingwaRelayServer").apply { start() }
        return START_STICKY
    }

    private fun runServerLoop() {
        try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(HOTSPOT_PORT))
            }
            while (running) {
                val socket = server?.accept() ?: break
                try {
                    clientExecutor.execute { handleClient(socket) }
                } catch (_: RejectedExecutionException) {
                    runCatching { socket.close() }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { server?.close() } catch (_: Exception) {}
            server = null
            running = false
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            val line = reader.readLine() ?: ""
            val obj = runCatching { JSONObject(line) }.getOrNull()
            if (obj == null) {
                writer.write("ERR invalid-json\n")
                writer.flush()
                socket.close()
                return
            }
            val cfg = RelayManager.load(this)
            val pin = (obj.optString("pin", "") ?: "").trim()
            if (cfg.pin.isNotBlank() && pin != cfg.pin) {
                writer.write("ERR invalid-pin\n")
                writer.flush()
                socket.close()
                return
            }
            val cmd = (obj.optString("cmd", "") ?: "").trim()
            if (cmd.uppercase() == "PING") {
                writer.write("OK PONG\n")
                writer.flush()
                return
            }
            val parts = cmd.split("\\s+".toRegex())
            if (parts.isNotEmpty() && parts[0].uppercase() == "TOKENSET") {
                val bal = parts.getOrNull(1)?.toIntOrNull() ?: -1
                if (bal < 0) {
                    writer.write("ERR bad-balance\n")
                    writer.flush()
                    return
                }
                TokenManager(applicationContext).setBalanceFromRelay(bal)
                writer.write("OK TOKENS\n")
                writer.flush()
                return
            }
            if (parts.isNotEmpty() && parts[0].uppercase() == "BALANCESET") {
                val encoded = parts.getOrNull(1).orEmpty()
                val display = RelayManager.decodeRelayText(encoded)?.trim().orEmpty()
                if (display.isBlank()) {
                    writer.write("ERR bad-airtime\n")
                    writer.flush()
                    return
                }
                RelayManager.setMirroredPrimaryAirtime(applicationContext, display)
                writer.write("OK BALANCE\n")
                writer.flush()
                return
            }
            if (parts.size < 3 || parts[0].uppercase() != "BUYAMT") {
                writer.write("ERR unsupported-cmd\n")
                writer.flush()
                socket.close()
                return
            }
            val phone = parts[1]
            val amount = parts[2].toIntOrNull() ?: 0
            val dest = if (cfg.sendResultsSms && cfg.pairedPhone.isNotBlank()) cfg.pairedPhone else null
            val txId = RelayManager.executeBuyAmountLocal(this, phone, amount, dest) ?: -1
            if (txId == -1) writer.write("ERR no-offer\n") else writer.write("OK $txId\n")
            writer.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        runCatching { serverThread?.interrupt() }
        serverThread = null
        clientExecutor.shutdownNow()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Relay Hotspot", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Bingwa Relay Hotspot")
                .setContentText("Listening for hotspot relay commands")
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Bingwa Relay Hotspot")
                .setContentText("Listening for hotspot relay commands")
                .setOngoing(true)
                .build()
        }

    companion object {
        private const val CHANNEL_ID = "relay_hotspot"
        private const val HOTSPOT_PORT = 8765
        private const val NOTIFICATION_ID = 2012
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
