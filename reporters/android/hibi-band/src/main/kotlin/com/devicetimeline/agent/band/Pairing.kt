// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class Pairing(val address: String, val key: ByteArray) {
    companion object {
        private const val ALIAS = "hibi.band.pairing.v1"
        private fun storage(c: Context) = AtomicFile(File(c.noBackupFilesDir, "hibi-band-pairing.bin"))
        private fun parse(data: ByteArray): Pairing {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            require(obj.getString("model") == "miwear.watch.n67cn")
            val mac = obj.getString("mac").uppercase(java.util.Locale.ROOT)
            require(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac))
            val hex = obj.getString("authKey")
            require(Regex("[0-9A-Fa-f]{32}").matches(hex))
            return Pairing(mac, ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }
        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        private fun decrypt(file: AtomicFile, key: SecretKey): ByteArray {
            val data = file.readFully()
            require(data.size in 29..8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            return cipher.doFinal(data, 12, data.size - 12)
        }
        private fun migrate(context: Context, plain: ByteArray) {
            parse(plain).key.fill(0)
            val key = keystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            require(cipher.iv.size == 12)
            val encrypted = cipher.iv + cipher.doFinal(plain)
            val file = storage(context)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            val restored = decrypt(file, key)
            try { require(java.security.MessageDigest.isEqual(plain, restored)) }
            finally { restored.fill(0) }
        }
        fun importDocument(context: Context, input: java.io.InputStream) {
            val buffer = ByteArray(4097)
            try {
                var length = 0
                while (length < buffer.size) {
                    val count = input.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    require(count > 0)
                    length += count
                }
                require(length in 1..4096)
                val bytes = buffer.copyOf(length)
                try { migrate(context, bytes) } finally { bytes.fill(0) }
            } finally { buffer.fill(0) }
        }

        fun importIfAvailable(c: Context) {
            val pending = File(c.filesDir, "pairing-bootstrap.json")
            if (pending.isFile) {
                require(pending.length() in 1L..4096L)
                val data = pending.readBytes()
                try { migrate(c, data); require(pending.delete()) }
                finally { data.fill(0) }
            } else if (!storage(c).baseFile.exists()) {
                // Same-package update from the band probe: the previous secret key
                // remains accessible; never recreate a missing legacy alias or remove its file.
                val legacy = AtomicFile(File(c.noBackupFilesDir, "paired.bin"))
                if (!legacy.baseFile.exists()) return
                val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                val legacyKey = ks.getKey("hibi.probe.pairing.v1", null) as? SecretKey ?: return
                val data = decrypt(legacy, legacyKey)
                try { migrate(c, data) } finally { data.fill(0) }
            }
        }
        fun load(c: Context): Pairing {
            val plain = decrypt(storage(c), keystoreKey())
            return try { parse(plain) } finally { plain.fill(0) }
        }
    }
}
