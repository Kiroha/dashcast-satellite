package io.github.kiroha.dashcast.satellite

import android.app.Application
import android.content.Intent
import io.github.kiroha.dashcast.satellite.navigation.NavigationObservationBus
import io.github.kiroha.dashcast.satellite.transport.TransportState
import io.github.kiroha.dashcast.satellite.transport.TransportStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceLifecycleTest {
    @Test fun `old service completion cannot overwrite replacement connection`() {
        val old = SatelliteState.beginSession()
        val current = SatelliteState.beginSession()
        SatelliteState.update(current, TransportStatus(TransportState.CONNECTED, true))
        SatelliteState.update(old, TransportStatus(TransportState.RECONNECTING))
        SatelliteState.endSession(old)
        assertEquals(TransportState.CONNECTED, SatelliteState.transport.state)
        assertTrue(SatelliteState.transport.remoteGuidance)
        SatelliteState.endSession(current)
        assertEquals(TransportState.STOPPED, SatelliteState.transport.state)
        SatelliteState.update(current, TransportStatus(TransportState.CONNECTED, true))
        assertEquals(TransportState.STOPPED, SatelliteState.transport.state)
    }

    @Test fun `missing protected profile stops service and leaves no active source`() {
        val app = RuntimeEnvironment.getApplication()
        SatelliteSettings(app).enabled = true
        val controller = Robolectric.buildService(SatelliteService::class.java).create()
        controller.get().onStartCommand(Intent(app, SatelliteService::class.java), 0, 1)
        assertFalse(SatelliteSettings(app).enabled)
        controller.destroy()
        assertFalse(NavigationObservationBus.configuration.transmittingEnabled)
        assertEquals(TransportState.STOPPED, SatelliteState.transport.state)
        assertEquals("service_start_failed", SatelliteState.transport.detail)
    }

    @Test fun `boot does not launch guidance without both explicit choices`() {
        val app = RuntimeEnvironment.getApplication()
        val settings = SatelliteSettings(app)
        val receiver = BootReceiver()
        for ((enabled, restore) in listOf(false to false, true to false, false to true)) {
            settings.enabled = enabled
            settings.restartAfterBoot = restore
            receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertNull(shadowOf(app).nextStartedService)
        }
        settings.enabled = true
        settings.restartAfterBoot = true
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(SatelliteService::class.java.name,
            shadowOf(app).nextStartedService.component?.className)
    }
}
