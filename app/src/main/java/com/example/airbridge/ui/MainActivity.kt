package com.example.airbridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.airbridge.aap.AancController
import com.example.airbridge.aap.AncMode
import com.example.airbridge.ble.AirPodsBleParser
import com.example.airbridge.databinding.ActivityMainBinding
import com.example.airbridge.patch.BluetoothPatchManager
import com.example.airbridge.service.AirPodsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bluetoothPatchManager = BluetoothPatchManager()
    private val ancController = AancController()
    private var currentMode = AncMode.OFF

    private val packetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packet = intent?.getByteArrayExtra(AirPodsService.EXTRA_PACKET) ?: return
            val head = ancController.parseHeadTracking(packet)
            if (head != null) {
                binding.headTrackingStatus.text = "Head Tracking: h=${head.horizontal}, v=${head.vertical}"
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val scan = result ?: return
            val records = scan.scanRecord?.manufacturerSpecificData ?: return
            for (idx in 0 until records.size()) {
                val manufacturerId = records.keyAt(idx)
                val payload = records.valueAt(idx)
                val parsed = AirPodsBleParser.parse(manufacturerId, payload)
                if (parsed != null) {
                    binding.connectionStatus.text = "Connection: ${parsed.connectionState.name}"
                    binding.batteryStatus.text =
                        "Battery: L=${parsed.leftBattery ?: "--"}% R=${parsed.rightBattery ?: "--"}% Case=${parsed.caseBattery ?: "--"}%"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.autoPlayPauseToggle.isChecked = prefs.getBoolean(KEY_AUTO_PLAY_PAUSE, true)
        binding.autoPlayPauseToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_PLAY_PAUSE, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Automatic play/pause enabled" else "Automatic play/pause disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.startServiceButton.setOnClickListener {
            val serviceIntent = Intent(this, AirPodsService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.connectionStatus.text = "Connection: service started"
            startBleScan()
        }

        binding.stopServiceButton.setOnClickListener {
            stopService(Intent(this, AirPodsService::class.java))
            binding.connectionStatus.text = "Connection: service stopped"
        }

        binding.ancCycleButton.setOnClickListener {
            currentMode = currentMode.next()
            ancController.setMode(this, currentMode)
            binding.ancStatus.text = "ANC Mode: ${currentMode.name}"
        }

        binding.requestKeysButton.setOnClickListener {
            ancController.requestKeys(this)
            Toast.makeText(this, "Requested IRK and ENC_KEY", Toast.LENGTH_SHORT).show()
        }

        binding.applyPatchButton.setOnClickListener {
            uiScope.launch {
                binding.toolStatus.text = "Tools: validating root + r2 availability"
                val result = bluetoothPatchManager.applyPatch()
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                binding.toolStatus.text = "Tools: ${if (result.success) "ready" else "issue detected"}"
            }
        }

        ContextCompat.registerReceiver(
            this,
            packetReceiver,
            IntentFilter(AirPodsService.ACTION_PACKET),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun startBleScan() {
        val scanner: BluetoothLeScanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        scanner.startScan(scanCallback)
    }

    override fun onDestroy() {
        unregisterReceiver(packetReceiver)
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "airbridge_settings"
        private const val KEY_AUTO_PLAY_PAUSE = "auto_play_pause"
    }
}
