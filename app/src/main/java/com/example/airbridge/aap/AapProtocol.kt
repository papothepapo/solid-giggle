package com.example.airbridge.aap

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AapProtocol {
    val frameHeader = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    val handshakePacket = byteArrayOf(
        0x00, 0x00, 0x04, 0x00,
        0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    val notificationsPacket = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x0F, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )

    val headTrackingStartPacket = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x17, 0x00, 0x00, 0x00,
        0x10, 0x00, 0x10, 0x00,
        0x08, 0xA1.toByte(), 0x02, 0x42,
        0x0B, 0x08, 0x0E, 0x10,
        0x02, 0x1A, 0x05, 0x01,
        0x40, 0x9C.toByte(), 0x00, 0x00
    )

    val requestProximityKeysPacket = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x30, 0x00, 0x05, 0x00
    )

    fun controlCommand(identifier: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= 4) { "Control command payload cannot exceed 4 bytes." }
        val payload = ByteArray(6 + 1 + data.size)
        frameHeader.copyInto(payload, 0)
        payload[4] = 0x09
        payload[5] = 0x00
        payload[6] = identifier.toByte()
        data.copyInto(payload, 7)
        return payload
    }

    fun renameCommand(name: String): ByteArray {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(frameHeader.size + 4 + utf8.size)
        frameHeader.copyInto(packet, 0)
        packet[4] = 0x1E
        packet[5] = 0x00
        packet[6] = utf8.size.toByte()
        packet[7] = 0x00
        utf8.copyInto(packet, 8)
        return packet
    }

    fun parseHeadTracking(raw: ByteArray): HeadTrackingSample? {
        if (raw.size < 55) return null
        val horizontal = ByteBuffer.wrap(raw, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short
        val vertical = ByteBuffer.wrap(raw, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short
        return HeadTrackingSample(horizontal = horizontal, vertical = vertical)
    }
}

data class HeadTrackingSample(
    val horizontal: Short,
    val vertical: Short
)

enum class AncMode(val wireValue: Byte) {
    OFF(0x01),
    ANC(0x02),
    TRANSPARENCY(0x03),
    ADAPTIVE(0x04);

    fun next(): AncMode = entries[(ordinal + 1) % entries.size]
}
