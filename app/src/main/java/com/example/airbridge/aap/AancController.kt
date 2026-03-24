package com.example.airbridge.aap

import android.content.Context
import android.content.Intent
import com.example.airbridge.service.AirPodsService

class AancController {
    fun setMode(context: Context, mode: AncMode) {
        val packet = AapProtocol.controlCommand(identifier = 0x0D, data = byteArrayOf(mode.wireValue))
        sendCommand(context, packet)
    }

    fun setConversationAwareness(context: Context, enabled: Boolean) {
        val payload = byteArrayOf(if (enabled) 0x01 else 0x02)
        sendCommand(context, AapProtocol.controlCommand(identifier = 0x28, data = payload))
    }

    fun requestKeys(context: Context) {
        sendCommand(context, AapProtocol.requestProximityKeysPacket)
    }

    fun parseHeadTracking(packet: ByteArray) = AapProtocol.parseHeadTracking(packet)

    private fun sendCommand(context: Context, payload: ByteArray) {
        context.sendBroadcast(
            Intent(ACTION_SEND_COMMAND)
                .setPackage(context.packageName)
                .putExtra(EXTRA_COMMAND, payload)
        )
    }

    companion object {
        const val ACTION_SEND_COMMAND = "com.example.airbridge.SEND_COMMAND"
        const val EXTRA_COMMAND = "extra_command"
    }
}
