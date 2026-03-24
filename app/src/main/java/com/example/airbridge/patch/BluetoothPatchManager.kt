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
            append(installAndResolveR2Script())
            append(" && R2_PATH=\\\"$(command -v r2 || echo /data/local/tmp/r2)\\\" && ")
            append("\$R2_PATH -q -w ")
            append(libraryPath)
            append(" -c \\\"")
            append("aa; ")
            append("s sym.l2c_fcr_chk_chan_modes; wa mov w0, 1; ret; ")
            append("s sym.l2cu_send_peer_info_req; wa ret; ")
            append("wq\\\"")
            append("'")
        }

        val process = runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", command)) }.getOrNull()
            ?: return@withContext PatchResult(false, "Failed to spawn patch process.")

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            PatchResult(true, "Patch applied. Reboot required.")
        } else {
            val error = process.errorStream.bufferedReader().readText().trim()
            val output = process.inputStream.bufferedReader().readText().trim()
            val details = listOf(error, output).filter { it.isNotBlank() }.joinToString("\n")
            Log.e(TAG, "Patch failed [$exitCode]: $details")
            PatchResult(false, "Patch failed with exit code $exitCode. $details")
        }
    }

    private fun installAndResolveR2Script(): String =
        """
        if ! command -v r2 >/dev/null 2>&1 && [ ! -x /data/local/tmp/r2 ]; then
          if command -v pkg >/dev/null 2>&1; then pkg install -y radare2 >/dev/null 2>&1 || true; fi;
          if command -v apt-get >/dev/null 2>&1; then apt-get update >/dev/null 2>&1 || true; apt-get install -y radare2 >/dev/null 2>&1 || true; fi;
          if command -v apk >/dev/null 2>&1; then apk add --no-cache radare2 >/dev/null 2>&1 || true; fi;
          if ! command -v r2 >/dev/null 2>&1; then
            TMP_R2=/data/local/tmp/r2;
            URL_BASE=https://github.com/radareorg/radare2/releases/latest/download;
            if command -v curl >/dev/null 2>&1; then
              (curl -fsSL "${'$'}URL_BASE/r2-static-arm64" -o "${'$'}TMP_R2" || curl -fsSL "${'$'}URL_BASE/r2-static-aarch64" -o "${'$'}TMP_R2") >/dev/null 2>&1 || true;
            elif command -v wget >/dev/null 2>&1; then
              (wget -qO "${'$'}TMP_R2" "${'$'}URL_BASE/r2-static-arm64" || wget -qO "${'$'}TMP_R2" "${'$'}URL_BASE/r2-static-aarch64") >/dev/null 2>&1 || true;
            fi;
            chmod +x "${'$'}TMP_R2" >/dev/null 2>&1 || true;
          fi;
        fi;
        command -v r2 >/dev/null 2>&1 || [ -x /data/local/tmp/r2 ]
        """.trimIndent().replace("\n", " ")

    private fun detectLibraryPath(): String? {
        val candidates = listOf(
            "/vendor/lib64/hw/android.hardware.bluetooth@1.0-impl-mediatek.so",
            "/vendor/lib64/hw/android.hardware.bluetooth-service.mediatek.so",
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
