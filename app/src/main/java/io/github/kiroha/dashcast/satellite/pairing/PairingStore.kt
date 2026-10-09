package io.github.kiroha.dashcast.satellite.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM envelope outside Android backup; the non-exportable key stays in AndroidKeyStore. */
class PairingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "satellite_pairing.v1"))

    fun save(profile: PairingProfile): Unit = synchronized(LOCK) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(profile.encoded().toByteArray(Charsets.UTF_8))
        val envelope = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(ciphertext).array()
        val stream = file.startWrite()
        try {
            stream.write(envelope)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw IllegalStateException("Pairing storage unavailable", error)
        }
    }

    fun load(): PairingProfile? = synchronized(LOCK) {
        try {
            if (!file.baseFile.exists()) null else {
                require(file.baseFile.length() in 30..(PairingProfile.MAX_BYTES + 64).toLong())
                val bytes = file.readFully()
                val ivSize = bytes[0].toInt() and 0xff
                require(ivSize == 12 && bytes.size > 1 + ivSize + 16)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                cipher.updateAAD(AAD)
                PairingProfile.parse(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
            }
        } catch (_: Exception) { null }
    }

    fun clear(): Unit = synchronized(LOCK) {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Pairing key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private companion object {
        val LOCK = Any()
        const val ALIAS = "dashcast_satellite_pairing_v1"
        val AAD = "dashcast-satellite:pairing:v1".toByteArray(Charsets.UTF_8)
    }
}
