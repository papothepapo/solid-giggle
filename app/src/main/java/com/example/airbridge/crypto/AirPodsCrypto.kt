package com.example.airbridge.crypto

import com.example.airbridge.ble.DecryptedBattery
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AirPodsCrypto {
    fun decryptBatteryPayload(encKey: ByteArray, encryptedPayload: ByteArray): DecryptedBattery? {
        if (encKey.size != 16 || encryptedPayload.size != 16) return null
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val key = SecretKeySpec(encKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decrypted = cipher.doFinal(encryptedPayload)

        val left = decrypted[0].toInt() and 0x7F
        val right = decrypted[1].toInt() and 0x7F
        val case = decrypted[2].toInt() and 0x7F
        val flags = ByteBuffer.wrap(decrypted, 3, 1).get().toInt() and 0xFF
        return DecryptedBattery(leftPrecise = left, rightPrecise = right, casePrecise = case, flags = flags)
    }

    fun verifyResolvablePrivateAddress(irk: ByteArray, prand: ByteArray, hash: ByteArray): Boolean {
        if (irk.size != 16 || prand.size != 3 || hash.size != 3) return false
        val payload = ByteArray(16)
        prand.copyInto(payload, 13)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk, "AES"))
        val encrypted = cipher.doFinal(payload)
        return encrypted.copyOfRange(13, 16).contentEquals(hash)
    }
}
