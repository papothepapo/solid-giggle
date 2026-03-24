package com.example.airbridge.patch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BluetoothPatchManager {
    suspend fun applyPatch(): PatchResult = withContext(Dispatchers.IO) {
        val libraryPath = detectLibraryPath() ?: return@withContext PatchResult(
            success = false,
            message = "No known MediaTek Bluetooth implementation library found."
        )

        val command = buildString {
            append("su -c '")
            append("r2 -q -w ")
            append(libraryPath)
            append(" -c \"")
            append("aa; ")
            append("s sym.l2c_fcr_chk_chan_modes; wa mov w0, 1; ret; ")
            append("s sym.l2cu_send_peer_info_req; wa ret; ")
            append("wq\"")
            append("'")
        }

        val process = runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", command)) }.getOrNull()
            ?: return@withContext PatchResult(false, "Failed to spawn patch process.")

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            PatchResult(true, "Patch applied. Reboot required.")
        } else {
            val error = process.errorStream.bufferedReader().readText()
            Log.e(TAG, "Patch failed: $error")
            PatchResult(false, "Patch failed with exit code $exitCode: $error")
        }
    }

    private fun detectLibraryPath(): String? {
        val candidates = listOf(
            "/vendor/lib64/hw/android.hardware.bluetooth@1.0-impl-mediatek.so",
            "/system/lib64/libbluetooth_jni.so"
        )
        return candidates.firstOrNull {
            runCatching { java.io.File(it).exists() }.getOrDefault(false)
        }
    }

    companion object {
        private const val TAG = "BluetoothPatchManager"
    }
}

data class PatchResult(
    val success: Boolean,
    val message: String
)
