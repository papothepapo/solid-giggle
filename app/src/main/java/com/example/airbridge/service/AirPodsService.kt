package com.example.airbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.airbridge.aap.AapProtocol
import com.example.airbridge.aap.AapSocketClient
import com.example.airbridge.aap.AancController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AirPodsService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketClient = AapSocketClient()
    private var listenJob: Job? = null
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getByteArrayExtra(AancController.EXTRA_COMMAND) ?: return
            sendControlPacket(payload)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1107, buildNotification("AirBridge idle"))
        ContextCompat.registerReceiver(
            this,
            commandReceiver,
            IntentFilter(AancController.ACTION_SEND_COMMAND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectAndListen(intent?.getStringExtra(EXTRA_DEVICE_ADDRESS))
        return START_STICKY
    }

    private fun connectAndListen(address: String?) {
        listenJob?.cancel()
        listenJob = serviceScope.launch {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
            val remoteDevice: BluetoothDevice = if (!address.isNullOrBlank()) {
                adapter.getRemoteDevice(address)
            } else {
                adapter.bondedDevices.firstOrNull() ?: return@launch
            }

            val connected = socketClient.connect(remoteDevice)
            if (!connected) return@launch

            socketClient.send(AapProtocol.handshakePacket)
            socketClient.send(AapProtocol.notificationsPacket)
            socketClient.send(AapProtocol.headTrackingStartPacket)

            while (isActive) {
                val packet = socketClient.read()
                if (packet == null) {
                    delay(250)
                    continue
                }
                sendBroadcast(Intent(ACTION_PACKET).putExtra(EXTRA_PACKET, packet))
            }
        }
    }

    fun sendControlPacket(payload: ByteArray) {
        serviceScope.launch {
            socketClient.send(payload)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        socketClient.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AirBridge")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirBridge Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "airbridge_service"
        const val ACTION_PACKET = "com.example.airbridge.PACKET"
        const val EXTRA_PACKET = "extra_packet"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }
}
