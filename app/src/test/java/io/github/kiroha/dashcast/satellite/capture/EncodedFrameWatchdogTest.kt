package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class EncodedFrameWatchdogTest {
    @Test fun `missing stats callback expires independently of callback progress`() {
        val watchdog = EncodedFrameWatchdog(0)
        watchdog.encoded(5, 1_000_000_000)
        watchdog.requested(2_000_000_000)
        assertNull(watchdog.failure(7_000_000_000))
        assertEquals("stats_timeout", watchdog.failure(7_000_000_001))
    }
    @Test fun `completed reports without outbound frames cannot renew source liveness`() {
        val watchdog = EncodedFrameWatchdog(0)
        repeat(10) { watchdog.requested(it * 1_000_000_000L); watchdog.completed() }
        assertEquals("media_connection_timeout", watchdog.failure(10_000_000_001))
        val active = EncodedFrameWatchdog(0)
        assertTrue(active.encoded(2, 1_000_000_000))
        assertFalse(active.encoded(2, 5_000_000_000))
        active.requested(6_000_000_000); active.completed()
        assertEquals("encoder_stalled", active.failure(7_000_000_001))
    }
    @Test fun `actual increasing encoded frames renew the source deadline`() {
        val watchdog = EncodedFrameWatchdog(0)
        assertTrue(watchdog.encoded(1, 1_000_000_000))
        assertTrue(watchdog.encoded(2, 6_000_000_000))
        assertNull(watchdog.failure(11_000_000_000))
    }
}
