package com.example.airbridge.ble

import com.example.airbridge.crypto.AirPodsCrypto

object AirPodsBleParser {
    private const val APPLE_COMPANY_ID = 0x004C

    fun parse(manufacturerId: Int, data: ByteArray, encKey: ByteArray? = null): ProximityStatus? {
        if (manufacturerId != APPLE_COMPANY_ID || data.isEmpty() || data[0] != 0x07.toByte()) return null
        if (data.size < 27) return null

        val model = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val statusByte = data[5].toInt() and 0xFF
        val batteryByte = data[6].toInt() and 0xFF
        val caseByte = data[7].toInt() and 0xFF
        val lidByte = data[8].toInt() and 0xFF
        val connState = data[10].toInt() and 0xFF

        val primaryLeft = (statusByte and (1 shl 5)) != 0
        val inCase = (statusByte and (1 shl 6)) != 0

        val podUpper = decodeNibble((batteryByte shr 4) and 0x0F)
        val podLower = decodeNibble(batteryByte and 0x0F)
        val caseLevel = decodeNibble(caseByte and 0x0F)

        val leftBattery = if (primaryLeft) podUpper else podLower
        val rightBattery = if (primaryLeft) podLower else podUpper

        val chargingFlags = (caseByte shr 4) and 0x0F

        val encryptedPayload = data.copyOfRange(11, 27)
        val detailedBattery = encKey?.let { AirPodsCrypto.decryptBatteryPayload(it, encryptedPayload) }

        return ProximityStatus(
            paired = data[2] == 0x01.toByte(),
            model = AirPodsModel.fromId(model),
            inCase = inCase,
            caseOpen = (lidByte and (1 shl 3)) == 0,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseLevel,
            leftCharging = (chargingFlags and (1 shl 1)) != 0,
            rightCharging = (chargingFlags and 1) != 0,
            caseCharging = (chargingFlags and (1 shl 2)) != 0,
            connectionState = ConnectionState.fromByte(connState),
            encryptedBattery = detailedBattery
        )
    }

    private fun decodeNibble(nibble: Int): Int? = when (nibble) {
        in 0x0..0x9 -> nibble * 10
        in 0xA..0xE -> 100
        0xF -> null
        else -> null
    }
}

data class ProximityStatus(
    val paired: Boolean,
    val model: AirPodsModel,
    val inCase: Boolean,
    val caseOpen: Boolean,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val connectionState: ConnectionState,
    val encryptedBattery: DecryptedBattery?
)

data class DecryptedBattery(
    val leftPrecise: Int,
    val rightPrecise: Int,
    val casePrecise: Int,
    val flags: Int
)

enum class AirPodsModel(val modelId: Int) {
    AIRPODS_PRO_1(0x0E20),
    AIRPODS_PRO_2(0x1420),
    AIRPODS_PRO_2_USBC(0x2420),
    AIRPODS_3(0x1320),
    AIRPODS_MAX(0x0A20),
    UNKNOWN(-1);

    companion object {
        fun fromId(id: Int): AirPodsModel = entries.firstOrNull { it.modelId == id } ?: UNKNOWN
    }
}

enum class ConnectionState(val wire: Int) {
    DISCONNECTED(0x00),
    IDLE(0x04),
    MUSIC(0x05),
    CALL(0x06),
    RINGING(0x07),
    HANGING_UP(0x09),
    UNKNOWN(-1);

    companion object {
        fun fromByte(raw: Int): ConnectionState = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
    }
}
