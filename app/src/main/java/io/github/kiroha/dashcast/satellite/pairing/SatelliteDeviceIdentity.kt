package io.github.kiroha.dashcast.satellite.pairing

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** A display identity for this installation, not a hardware identifier or authentication key. */
class SatelliteDeviceIdentity private constructor(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        internal const val FILE = "satellite_device_id.v1"
        private val lock = Any()
        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /** Call once on a worker. Storage failure lets the caller send a legacy hello without metadata. */
        fun load(context: Context): SatelliteDeviceIdentity = synchronized(lock) {
            val file = AtomicFile(File(context.noBackupFilesDir, FILE))
            val stored = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                file.openRead().use { input ->
                    val bytes = ByteArray(37)
                    var length = 0
                    while (length < bytes.size) {
                        val count = input.read(bytes, length, bytes.size - length)
                        if (count < 0) break
                        length += count
                    }
                    String(bytes, 0, length, Charsets.US_ASCII).takeIf { uuid.matches(it) }
                }
            } else null
            val id = stored ?: UUID.randomUUID().toString().also { value ->
                val output = file.startWrite()
                try {
                    output.write(value.toByteArray(Charsets.US_ASCII))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
            SatelliteDeviceIdentity(id, displayName(Build.MANUFACTURER, Build.MODEL))
        }

        internal fun displayName(manufacturer: String?, model: String?): String {
            fun clean(value: String?): String = value.orEmpty().map { if (it.isWhitespace()) ' ' else it }.filter {
                !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() &&
                    !Character.isSurrogate(it)
            }.joinToString("").trim().replace(Regex(" +"), " ").takeUnless { it.equals("unknown", true) }.orEmpty()
            val maker = clean(manufacturer)
            val product = clean(model)
            val combined = if (product.startsWith(maker, ignoreCase = true)) product else "$maker $product".trim()
            var result = combined.ifBlank { "Android satellite" }.take(80)
            while (result.toByteArray(Charsets.UTF_8).size > 160) result = result.dropLast(1)
            return result.trim().ifBlank { "Android satellite" }
        }

        /** Shared visual receiver ID. TLS continues to use all 64 certificate fingerprint characters. */
        fun receiverId(certificateSha256: String): String {
            require(certificateSha256.matches(Regex("[0-9a-f]{64}")))
            return certificateSha256.take(16).uppercase(Locale.ROOT).chunked(4).joinToString("-")
        }
    }
}
