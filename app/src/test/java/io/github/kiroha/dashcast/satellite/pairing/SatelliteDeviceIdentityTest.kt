package io.github.kiroha.dashcast.satellite.pairing

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteDeviceIdentityTest {
    private lateinit var context: Application
    private lateinit var file: File
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.noBackupFilesDir, SatelliteDeviceIdentity.FILE)
        file.delete()
    }
    @After fun tearDown() { file.delete() }

    @Test fun `installation identity survives reload and concurrent readers outside backup`() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val identities = pool.invokeAll((1..6).map { Callable { SatelliteDeviceIdentity.load(context) } }).map { it.get() }
            assertEquals(1, identities.map { it.id }.distinct().size)
            val identity = identities.first()
            assertEquals(4, UUID.fromString(identity.id).version())
            assertEquals(identity.id, file.readText())
            assertEquals(context.noBackupFilesDir, file.parentFile)
            assertEquals(identity.id, identity.toJson().getString("id"))
            assertEquals(identity.name, identity.toJson().getString("name"))
            assertEquals(2, identity.toJson().length())
        } finally { pool.shutdownNow() }
    }

    @Test fun `corrupted identity is replaced with a new bounded identifier`() {
        file.parentFile!!.mkdirs()
        file.writeText("a".repeat(100_000))
        val identity = SatelliteDeviceIdentity.load(context)
        assertEquals(36, identity.id.length)
        assertEquals(36L, file.length())
        assertEquals(identity.id, SatelliteDeviceIdentity.load(context).id)
    }

    @Test fun `model labels are useful without duplicate manufacturer and remove control text`() {
        assertEquals("Carlinkit Tbox Ultra", SatelliteDeviceIdentity.displayName("Carlinkit", "Tbox Ultra"))
        assertEquals("Carlinkit Tbox Ultra", SatelliteDeviceIdentity.displayName("Carlinkit", "Carlinkit Tbox Ultra"))
        assertEquals("Android satellite", SatelliteDeviceIdentity.displayName("unknown", "unknown"))
        assertEquals("Tbox Ultra", SatelliteDeviceIdentity.displayName(null, "\u202eTbox\n Ultra"))
        assertEquals("Tbox Ultra", SatelliteDeviceIdentity.displayName(null, "Tbox\u2028Ultra"))
    }

    @Test fun `model labels obey receiver character and UTF8 bounds`() {
        val longAscii = SatelliteDeviceIdentity.displayName("x".repeat(200), "model")
        assertTrue(longAscii.length <= 80)
        val longUnicode = SatelliteDeviceIdentity.displayName("界".repeat(100), "model")
        assertTrue(longUnicode.toByteArray(Charsets.UTF_8).size <= 160)
        assertTrue(longUnicode.isNotBlank())
        assertFalse(SatelliteDeviceIdentity.displayName("\uD83D\uDE00", "Tbox").any { Character.isSurrogate(it) })
    }

    @Test fun `receiver visual ID matches the certificate prefix without changing full pin`() {
        assertEquals("1234-5678-90AB-CDEF", SatelliteDeviceIdentity.receiverId("1234567890abcdef" + "a".repeat(48)))
        assertThrows(IllegalArgumentException::class.java) { SatelliteDeviceIdentity.receiverId("invalid") }
    }
}
