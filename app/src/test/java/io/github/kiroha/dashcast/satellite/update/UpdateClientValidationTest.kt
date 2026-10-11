package io.github.kiroha.dashcast.satellite.update

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],application=Application::class)
class UpdateClientValidationTest {
    @Test fun `production installer validation rejects modified and truncated bytes before APK identity`() {
        val context=RuntimeEnvironment.getApplication()
        val file=File(context.cacheDir,"integrity-test.apk")
        val bytes="verified-download".toByteArray()
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release=UpdateRelease("v0.7.0-dev",ReleaseVersion(0,7,0,true),"",UpdateAsset("update.apk","",bytes.size.toLong(),digest),null)
        val client=UpdateClient(context)
        try {
            file.writeBytes(bytes)
            // Matching bytes reach Android archive parsing; these are intentionally not an APK.
            assertEquals("invalid_apk",assertThrows(IllegalStateException::class.java) {client.validate(file,release)}.message)
            file.writeBytes(bytes.copyOf().apply {this[0]=0})
            assertEquals("integrity_failed",assertThrows(IllegalArgumentException::class.java) {client.validate(file,release)}.message)
            file.writeBytes(bytes.copyOf(bytes.size-1))
            assertEquals("integrity_failed",assertThrows(IllegalArgumentException::class.java) {client.validate(file,release)}.message)
        } finally {file.delete();client.cancel()}
    }
}
