package com.example.airbridge.patch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BluetoothPatchManager {
    suspend fun applyPatch(): PatchResult = withContext(Dispatchers.IO) {
        val candidates = detectLibraryPaths()
        if (candidates.isEmpty()) {
            return@withContext PatchResult(
                success = false,
                message = "No supported Bluetooth library found. Expected at least $PREFERRED_LIBRARY_PATH."
            )
        }

        val resolvedPatch = resolvePatch(candidates) ?: return@withContext PatchResult(
            success = false,
            message = "Could not find target symbols in any supported Bluetooth library. Checked: ${candidates.joinToString()}."
        )
        val libraryPath = resolvedPatch.libraryPath
        val patchTargets = resolvedPatch.targets

        val patchScript = buildShellPatchScript(libraryPath, patchTargets)

        val rootCheck = verifyRootAccess()
        if (rootCheck.command == null) {
            val details = rootCheck.attempts.joinToString(" | ")
            return@withContext PatchResult(
                false,
                "Root access failed ($details). Open Magisk, allow AirBridge superuser access, and disable denylist hiding for this app."
            )
        }

        val process = runCatching { runAsRoot(rootCheck.command, patchScript) }.getOrElse {
            Log.e(TAG, "Failed to execute root patch script", it)
            return@withContext PatchResult(
                false,
                "Failed to execute root patch script. Ensure Magisk granted root access."
            )
        }

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            PatchResult(true, "Patch applied. Reboot required.")
        } else {
            val error = process.errorStream.bufferedReader().readText().trim()
            val output = process.inputStream.bufferedReader().readText().trim()
            val details = listOf(error, output).filter { it.isNotBlank() }.joinToString("\n")
            Log.e(TAG, "Patch failed [$exitCode]: $details")
            val reason = when {
                details.contains("permission denied", ignoreCase = true) ->
                    "Root permission denied. Approve the prompt in your root manager and try again."
                details.contains("not allowed", ignoreCase = true) || details.contains("denied", ignoreCase = true) ->
                    "Magisk denied the request. Open Magisk > Superuser and grant access to AirBridge."
                details.contains("not found", ignoreCase = true) ->
                    "Required root tooling is missing. Ensure `su`, `dd`, and `mount` are available."
                else -> details
            }
            PatchResult(false, "Patch failed with exit code $exitCode. $reason")
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

    private fun verifyRootAccess(): RootAccessCheck {
        val attempts = mutableListOf<String>()
        ROOT_SHELL_CANDIDATES.forEach { candidate ->
            val probe = runCatching { runAsRoot(candidate, "id -u") }.getOrElse { err ->
                attempts += "${candidate.joinToString(" ")}: ${err.message ?: "failed to start"}"
                return@forEach
            }
            val exitCode = probe.waitFor()
            val stdout = probe.inputStream.bufferedReader().readText().trim()
            val stderr = probe.errorStream.bufferedReader().readText().trim()
            if (exitCode == 0 && stdout == "0") {
                return RootAccessCheck(candidate, attempts)
            }

            val details = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString(", ")
            attempts += "${candidate.joinToString(" ")}: exit=$exitCode${if (details.isNotBlank()) ", $details" else ""}"
        }
        return RootAccessCheck(null, attempts)
    }

    private fun runAsRoot(rootCommand: List<String>, command: String): Process {
        return ProcessBuilder(rootCommand + command)
            .redirectErrorStream(false)
            .start()
    }

    private data class RootAccessCheck(
        val command: List<String>?,
        val attempts: List<String>
    )

    private fun runAsRoot(command: String): Process {
        var lastError: Throwable? = null
        ROOT_SHELL_CANDIDATES.forEach { candidate ->
            runCatching {
                return runAsRoot(candidate, command)
            }.onFailure { err ->
                lastError = err
            }
        }
        throw IllegalStateException(
            "No usable su command found. Tried: ${ROOT_SHELL_CANDIDATES.joinToString { it.joinToString(" ") }}",
            lastError
        )
    }

    private fun detectLibraryPaths(): List<String> {
        val explicitCandidates = listOf(
            PREFERRED_LIBRARY_PATH,
            "/apex/com.android.btservices/lib64/libbluetooth.so",
            "/apex/com.android.btservices/lib/libbluetooth.so",
            "/apex/com.android.btservices/lib64/libbluetooth_jni.so",
            "/apex/com.android.btservices/lib/libbluetooth_jni.so",
            "/apex/com.android.btservices/lib64/libbluetooth_mtk.so",
            "/apex/com.android.btservices/lib/libbluetooth_mtk.so",
            "/vendor/lib64/libbluetooth.so",
            "/vendor/lib/libbluetooth.so",
            "/vendor/lib64/hw/android.hardware.bluetooth@1.0-impl-mediatek.so",
            "/vendor/lib64/hw/android.hardware.bluetooth@1.0.impl-mediatek.so",
            "/vendor/lib64/hw/android.hardware.bluetooth-service.mediatek.so",
            "/vendor/lib64/hw/bluetooth.default.so",
            "/vendor/lib/hw/android.hardware.bluetooth@1.0-impl-mediatek.so",
            "/vendor/lib/hw/android.hardware.bluetooth@1.0.impl-mediatek.so",
            "/vendor/lib/hw/android.hardware.bluetooth-service.mediatek.so",
            "/vendor/lib/hw/bluetooth.default.so",
            "/vendor/lib64/libbluetooth_mtk.so",
            "/vendor/lib/libbluetooth_mtk.so",
            "/system/lib/libbluetooth.so",
            "/system/lib64/libbluetooth_mtk.so",
            "/system/lib/libbluetooth_mtk.so",
            "/system/lib64/libbluetooth_jni.so",
            "/system/lib/libbluetooth_jni.so",
            "/system/lib64/hw/bluetooth.default.so",
            "/system/lib/hw/bluetooth.default.so"
        )

        val dynamicCandidates = listOf(
            "/apex/com.android.btservices/lib64",
            "/apex/com.android.btservices/lib",
            "/vendor/lib64/hw",
            "/vendor/lib/hw",
            "/vendor/lib64",
            "/vendor/lib",
            "/system/lib64",
            "/system/lib"
        ).flatMap { directory ->
            File(directory).listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.filter {
                    val name = it.name.lowercase()
                    name.endsWith(".so") &&
                        name.contains("bluetooth") &&
                        (name.contains("mediatek") || name.contains("mtk") || name.contains("jni") || name == "libbluetooth.so" || name == "bluetooth.default.so")
                }
                ?.map { it.absolutePath }
                ?.toList()
                .orEmpty()
        }

        return (explicitCandidates + dynamicCandidates)
            .distinct()
            .filter { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    private fun resolvePatch(paths: List<String>): ResolvedPatch? {
        var hadInspectionError = false
        paths.forEach { path ->
            val result = runCatching { resolvePatchTargets(path) }.getOrElse { error ->
                hadInspectionError = true
                Log.w(TAG, "Skipping $path: ${error.message}", error)
                return@forEach
            } ?: return@forEach
            if (result.targets.isNotEmpty()) return result
        }

        if (hadInspectionError) {
            Log.w(TAG, "One or more candidate Bluetooth libraries could not be inspected.")
        }
        return null
    }

    private fun resolvePatchTargets(path: String): ResolvedPatch {
        val lookupResult = ElfSymbolResolver.findSymbolFileOffsets(
            path = path,
            symbolNames = TARGET_SYMBOLS
        )

        val patchTemplates = when (lookupResult.arch) {
            ElfArch.ELF64 -> PATCH_BYTES_ARM64
            ElfArch.ELF32 -> PATCH_BYTES_ARM32
        }

        val targets = mutableListOf<PatchTarget>()
        lookupResult.symbolOffsets["l2c_fcr_chk_chan_modes"]?.let {
            targets += PatchTarget(
                offset = it,
                bytes = patchTemplates.getValue("l2c_fcr_chk_chan_modes")
            )
        }
        lookupResult.symbolOffsets["l2cu_send_peer_info_req"]?.let {
            targets += PatchTarget(
                offset = it,
                bytes = patchTemplates.getValue("l2cu_send_peer_info_req")
            )
        }
        return ResolvedPatch(path, targets)
    }

    private data class PatchTarget(
        val offset: Long,
        val bytes: ByteArray
    )
    private data class ResolvedPatch(
        val libraryPath: String,
        val targets: List<PatchTarget>
    )

    companion object {
        private const val TAG = "BluetoothPatchManager"
        private const val PREFERRED_LIBRARY_PATH = "/system/lib64/libbluetooth.so"
        private val ROOT_SHELL_CANDIDATES = listOf(
            listOf("/system/bin/su", "-c"),
            listOf("su", "-c"),
            listOf("/system/xbin/su", "-c"),
            listOf("/sbin/su", "-c"),
            listOf("su", "0", "sh", "-c")
        )
        private val TARGET_SYMBOLS = setOf(
            "l2c_fcr_chk_chan_modes",
            "l2cu_send_peer_info_req"
        )
        private val PATCH_BYTES_ARM64 = mapOf(
            "l2c_fcr_chk_chan_modes" to byteArrayOf(
                0x20.toByte(), 0x00.toByte(), 0x80.toByte(), 0x52.toByte(), // mov w0, #1
                0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte()  // ret
            ),
            "l2cu_send_peer_info_req" to byteArrayOf(
                0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte() // ret
            )
        )
        private val PATCH_BYTES_ARM32 = mapOf(
            "l2c_fcr_chk_chan_modes" to byteArrayOf(
                0x01.toByte(), 0x00.toByte(), 0xA0.toByte(), 0xE3.toByte(), // mov r0, #1
                0x1E.toByte(), 0xFF.toByte(), 0x2F.toByte(), 0xE1.toByte()  // bx lr
            ),
            "l2cu_send_peer_info_req" to byteArrayOf(
                0x1E.toByte(), 0xFF.toByte(), 0x2F.toByte(), 0xE1.toByte() // bx lr
            )
        )
    }
}

data class PatchResult(
    val success: Boolean,
    val message: String
)

private object ElfSymbolResolver {
    private const val ELF_MAGIC = 0x464C457F
    private const val ELFCLASS32 = 1
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val PT_LOAD = 1
    private const val SHT_DYNSYM = 11
    private const val SHT_SYMTAB = 2

    fun findSymbolFileOffsets(path: String, symbolNames: Set<String>): SymbolLookupResult {
        if (symbolNames.isEmpty()) return SymbolLookupResult(ElfArch.ELF64, emptyMap())
        RandomAccessFile(path, "r").use { file ->
            val ident = ByteArray(16)
            file.readFully(ident)

            val magic = ByteBuffer.wrap(ident, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(magic == ELF_MAGIC) { "Not an ELF file." }
            require(ident[5].toInt() == ELFDATA2LSB) { "Only little-endian ELF is supported." }

            val elfClass = ident[4].toInt()
            return when (elfClass) {
                ELFCLASS64 -> findSymbolFileOffsets64(file, symbolNames)
                ELFCLASS32 -> findSymbolFileOffsets32(file, symbolNames)
                else -> error("Unsupported ELF class: $elfClass")
            }
        }
    }

    private fun findSymbolFileOffsets64(file: RandomAccessFile, symbolNames: Set<String>): SymbolLookupResult {
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

        val symbols = lookupSymbols(file, sections, loadSegments, symbolNames)
        return SymbolLookupResult(ElfArch.ELF64, symbols)
    }

    private fun findSymbolFileOffsets32(file: RandomAccessFile, symbolNames: Set<String>): SymbolLookupResult {
        file.seek(28)
        val ePhoff = readInt(file).toLong() and 0xFFFFFFFFL
        val eShoff = readInt(file).toLong() and 0xFFFFFFFFL
        file.skipBytes(6) // e_flags(4) + e_ehsize(2)
        val ePhentsize = readShort(file).toInt() and 0xFFFF
        val ePhnum = readShort(file).toInt() and 0xFFFF
        val eShentsize = readShort(file).toInt() and 0xFFFF
        val eShnum = readShort(file).toInt() and 0xFFFF

        val loadSegments = (0 until ePhnum).mapNotNull { index ->
            val off = ePhoff + index.toLong() * ePhentsize
            file.seek(off)
            val type = readInt(file)
            val pOffset = readInt(file).toLong() and 0xFFFFFFFFL
            val pVaddr = readInt(file).toLong() and 0xFFFFFFFFL
            file.skipBytes(4) // p_paddr
            val pFilesz = readInt(file).toLong() and 0xFFFFFFFFL
            file.skipBytes(8) // p_memsz + p_flags
            if (type == PT_LOAD) Segment(pOffset, pVaddr, pFilesz) else null
        }

        val sections = (0 until eShnum).map { index ->
            val off = eShoff + index.toLong() * eShentsize
            file.seek(off)
            file.skipBytes(4) // sh_name
            val type = readInt(file)
            file.skipBytes(4) // sh_flags
            file.skipBytes(4) // sh_addr
            val shOffset = readInt(file).toLong() and 0xFFFFFFFFL
            val shSize = readInt(file).toLong() and 0xFFFFFFFFL
            val link = readInt(file)
            file.skipBytes(8) // sh_info + sh_addralign
            val entsize = readInt(file).toLong() and 0xFFFFFFFFL
            Section(type, shOffset, shSize, link, entsize)
        }

        val symbols = lookupSymbols(file, sections, loadSegments, symbolNames)
        return SymbolLookupResult(ElfArch.ELF32, symbols)
    }

    private fun lookupSymbols(
        file: RandomAccessFile,
        sections: List<Section>,
        loadSegments: List<Segment>,
        symbolNames: Set<String>
    ): Map<String, Long> {
        val results = mutableMapOf<String, Long>()

        val symbolSections = sections.filter { it.type == SHT_DYNSYM || it.type == SHT_SYMTAB }
        for (symbolSection in symbolSections) {
            val strTab = sections.getOrNull(symbolSection.link) ?: continue
            if (symbolSection.entsize <= 0L) continue

            val symbolCount = (symbolSection.size / symbolSection.entsize).toInt()
            for (i in 0 until symbolCount) {
                val symOff = symbolSection.offset + i * symbolSection.entsize
                file.seek(symOff)
                val stName = readInt(file)
                file.skipBytes(1) // st_info
                file.skipBytes(1) // st_other
                file.skipBytes(2) // st_shndx
                val stValue = when (symbolSection.entsize) {
                    24L -> readLong(file).also { file.skipBytes(8) } // ELF64
                    16L -> {
                        val value = readInt(file).toLong() and 0xFFFFFFFFL
                        file.skipBytes(4) // st_size
                        value
                    }
                    else -> continue
                }

                if (stName == 0) continue
                val name = readStringAt(file, strTab.offset + stName.toLong(), strTab.size)
                if (name.isBlank()) continue

                val matchedTarget = symbolNames.firstOrNull { target ->
                    name == target ||
                        name.startsWith("$target.") ||
                        name.startsWith("_$target") ||
                        name.contains(target)
                } ?: continue

                if (results.containsKey(matchedTarget)) continue
                val fileOffset = vaddrToFileOffset(stValue, loadSegments) ?: continue
                results[matchedTarget] = fileOffset
                if (results.size == symbolNames.size) return results
            }
        }

        return results
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

private data class SymbolLookupResult(
    val arch: ElfArch,
    val symbolOffsets: Map<String, Long>
)

private enum class ElfArch {
    ELF32,
    ELF64
}
