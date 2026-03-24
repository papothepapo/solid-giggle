package com.example.airbridge.patch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BluetoothPatchManager {
    suspend fun applyPatch(): PatchResult = withContext(Dispatchers.IO) {
        val libraryPath = detectLibraryPath() ?: return@withContext PatchResult(
            success = false,
            message = "No known MediaTek Bluetooth implementation library found."
        )

        val patchTargets = runCatching { resolvePatchTargets(libraryPath) }.getOrElse { error ->
            Log.e(TAG, "Failed to resolve patch targets", error)
            return@withContext PatchResult(
                false,
                "Failed to inspect Bluetooth library symbols: ${error.message ?: "unknown error"}"
            )
        }

        if (patchTargets.isEmpty()) {
            return@withContext PatchResult(
                false,
                "Could not find target symbols in $libraryPath. The library may be stripped or unsupported on this ROM."
            )
        }

        val patchScript = buildShellPatchScript(libraryPath, patchTargets)

        val process = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", patchScript)) }.getOrNull()
            ?: return@withContext PatchResult(false, "Failed to start root patch process. Ensure root access is granted.")

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

    private fun buildShellPatchScript(libraryPath: String, targets: List<PatchTarget>): String {
        val writes = targets.joinToString(separator = " ") { target ->
            val payload = target.bytes.joinToString(separator = "") { byte ->
                "\\\\x%02x".format(byte.toInt() and 0xFF)
            }
            "printf '$payload' | dd of='$libraryPath' bs=1 seek=${target.offset} conv=notrunc >/dev/null 2>&1 || exit 4;"
        }

        return """
            mount -o rw,remount /vendor >/dev/null 2>&1 || true;
            mount -o rw,remount /system >/dev/null 2>&1 || true;
            [ -w '$libraryPath' ] || chmod u+w '$libraryPath' >/dev/null 2>&1 || true;
            $writes
        """.trimIndent().replace("\n", " ")
    }

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

    private fun resolvePatchTargets(path: String): List<PatchTarget> {
        val symbolOffsets = ElfSymbolResolver.findSymbolFileOffsets(
            path = path,
            symbolNames = setOf(
                "l2c_fcr_chk_chan_modes",
                "l2cu_send_peer_info_req"
            )
        )
        val targets = mutableListOf<PatchTarget>()
        symbolOffsets["l2c_fcr_chk_chan_modes"]?.let {
            targets += PatchTarget(
                offset = it,
                bytes = byteArrayOf(
                    0x20.toByte(), 0x00.toByte(), 0x80.toByte(), 0x52.toByte(), // mov w0, #1
                    0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte()  // ret
                )
            )
        }
        symbolOffsets["l2cu_send_peer_info_req"]?.let {
            targets += PatchTarget(
                offset = it,
                bytes = byteArrayOf(
                    0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte() // ret
                )
            )
        }
        return targets
    }

    private data class PatchTarget(
        val offset: Long,
        val bytes: ByteArray
    )

    companion object {
        private const val TAG = "BluetoothPatchManager"
    }
}

data class PatchResult(
    val success: Boolean,
    val message: String
)

private object ElfSymbolResolver {
    private const val ELF_MAGIC = 0x464C457F
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val PT_LOAD = 1
    private const val SHT_DYNSYM = 11

    fun findSymbolFileOffsets(path: String, symbolNames: Set<String>): Map<String, Long> {
        if (symbolNames.isEmpty()) return emptyMap()
        RandomAccessFile(path, "r").use { file ->
            val ident = ByteArray(16)
            file.readFully(ident)

            val magic = ByteBuffer.wrap(ident, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(magic == ELF_MAGIC) { "Not an ELF file." }
            require(ident[4].toInt() == ELFCLASS64) { "Only ELF64 is supported." }
            require(ident[5].toInt() == ELFDATA2LSB) { "Only little-endian ELF is supported." }

            file.seek(32)
            val ePhoff = readLong(file)
            val eShoff = readLong(file)
            file.skipBytes(6) // e_flags(4) + e_ehsize(2)
            val ePhentsize = readShort(file).toInt() and 0xFFFF
            val ePhnum = readShort(file).toInt() and 0xFFFF
            val eShentsize = readShort(file).toInt() and 0xFFFF
            val eShnum = readShort(file).toInt() and 0xFFFF

            val loadSegments = (0 until ePhnum).mapNotNull { index ->
                val off = ePhoff + index.toLong() * ePhentsize
                file.seek(off)
                val type = readInt(file)
                file.skipBytes(4) // p_flags
                val pOffset = readLong(file)
                val pVaddr = readLong(file)
                file.skipBytes(8) // p_paddr
                val pFilesz = readLong(file)
                if (type == PT_LOAD) Segment(pOffset, pVaddr, pFilesz) else null
            }

            val sections = (0 until eShnum).map { index ->
                val off = eShoff + index.toLong() * eShentsize
                file.seek(off)
                file.skipBytes(4) // sh_name
                val type = readInt(file)
                file.skipBytes(8) // sh_flags
                file.skipBytes(8) // sh_addr
                val shOffset = readLong(file)
                val shSize = readLong(file)
                val link = readInt(file)
                file.skipBytes(4) // sh_info
                file.skipBytes(8) // sh_addralign
                val entsize = readLong(file)
                Section(type, shOffset, shSize, link, entsize)
            }

            val dynSymIndex = sections.indexOfFirst { it.type == SHT_DYNSYM }
            if (dynSymIndex == -1) return emptyMap()

            val dynSym = sections[dynSymIndex]
            val strTab = sections.getOrNull(dynSym.link)
                ?: return emptyMap()
            if (dynSym.entsize <= 0L) return emptyMap()

            val symbolCount = (dynSym.size / dynSym.entsize).toInt()
            val results = mutableMapOf<String, Long>()
            for (i in 0 until symbolCount) {
                val symOff = dynSym.offset + i * dynSym.entsize
                file.seek(symOff)
                val stName = readInt(file)
                file.skipBytes(1) // st_info
                file.skipBytes(1) // st_other
                file.skipBytes(2) // st_shndx
                val stValue = readLong(file)
                file.skipBytes(8) // st_size

                val name = readStringAt(file, strTab.offset + stName.toLong(), strTab.size)
                if (name !in symbolNames) continue
                val fileOffset = vaddrToFileOffset(stValue, loadSegments) ?: continue
                results[name] = fileOffset
                if (results.size == symbolNames.size) break
            }
            return results
        }
    }

    private fun vaddrToFileOffset(vaddr: Long, segments: List<Segment>): Long? {
        val seg = segments.firstOrNull {
            vaddr >= it.vaddr && vaddr < (it.vaddr + it.fileSize)
        } ?: return null
        return seg.offset + (vaddr - seg.vaddr)
    }

    private fun readStringAt(file: RandomAccessFile, offset: Long, maxBytes: Long): String {
        if (maxBytes <= 0L) return ""
        file.seek(offset)
        val bytes = ArrayList<Byte>(64)
        var consumed = 0L
        while (consumed < maxBytes) {
            val b = file.readByte()
            if (b.toInt() == 0) break
            bytes += b
            consumed++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun readShort(file: RandomAccessFile): Short {
        val bytes = ByteArray(2)
        file.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short
    }

    private fun readInt(file: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        file.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLong(file: RandomAccessFile): Long {
        val bytes = ByteArray(8)
        file.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private data class Segment(
        val offset: Long,
        val vaddr: Long,
        val fileSize: Long
    )

    private data class Section(
        val type: Int,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entsize: Long
    )
}
