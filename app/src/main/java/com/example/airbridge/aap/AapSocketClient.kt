package com.example.airbridge.aap

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method

class AapSocketClient {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    suspend fun connect(device: BluetoothDevice, psm: Int = 0x1001): Boolean = withContext(Dispatchers.IO) {
        val methods = listOf(
            "createInsecureL2capSocket",
            "createL2capSocket",
            "createInsecureL2capChannel",
            "createL2capChannel"
        )

        methods.forEach { methodName ->
            val connected = runCatching {
                val method: Method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
                val created = method.invoke(device, psm) as BluetoothSocket
                created.connect()
                socket = created
                input = created.inputStream
                output = created.outputStream
                Log.i(TAG, "Connected to AAP PSM $psm using $methodName")
                true
            }.onFailure {
                Log.w(TAG, "L2CAP connect attempt failed via $methodName", it)
                close()
            }.getOrDefault(false)

            if (connected) return@withContext true
        }

        Log.e(TAG, "Failed to connect to AAP PSM $psm using all known hidden/public L2CAP APIs")
        false
    }

    suspend fun send(packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext false
        runCatching {
            out.write(packet)
            out.flush()
            true
        }.onFailure {
            Log.e(TAG, "Failed to send packet", it)
        }.getOrDefault(false)
    }

    suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val inp = input ?: return@withContext null
        val buffer = ByteArray(4096)
        runCatching {
            val size = inp.read(buffer)
            if (size <= 0) null else buffer.copyOf(size)
        }.onFailure {
            Log.e(TAG, "Read failed", it)
        }.getOrNull()
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        private const val TAG = "AapSocketClient"
    }
}
