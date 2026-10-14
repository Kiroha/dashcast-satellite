package io.github.kiroha.dashcast.satellite.capture

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoDiagnosticsTest {
    @Test fun `timeline keeps bounded recent history and scalar totals independently`() {
        var now = 1000L
        val history = VideoDiagnostics.Timeline { now }
        repeat(100) { history.record(VideoDiagnostics.Event.VIEWER_READY) }
        repeat(200) { history.record(VideoDiagnostics.Event.ICE_RECEIVED, recent = false) }
        now = 6000
        assertEquals(8, history.recent().lines().size)
        assertTrue(history.recent().lines().all { it.startsWith("5s ago") })
        assertTrue(history.summary().contains("viewer_ready=100"))
        assertTrue(history.summary().contains("ice_received=200"))
        assertEquals(32, history.summary().lineSequence().count { it.startsWith("5s ago") })
        assertTrue(history.summary().length < 6000)
    }
    @Test fun `raw exception payloads class names and untrusted terminal reasons are excluded`() {
        val payload = "private token SDP notification 192.168.49.1"
        class PrivateSensitiveException : RuntimeException(payload)
        VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, PrivateSensitiveException())
        VideoDiagnostics.terminal(payload)
        val text = VideoDiagnostics.summary()
        assertFalse(text.contains(payload))
        assertFalse(text.contains("PrivateSensitiveException"))
        assertTrue(text.contains("Capture setup failed (other)"))
    }

    @Test fun `newest events are first and a late send retains its original sharing session`() {
        var now = 1000L
        val history = VideoDiagnostics.Timeline { now }
        history.record(VideoDiagnostics.Event.SHARE_STARTED)
        val old = history.currentShare()
        history.record(VideoDiagnostics.Event.WAITING_VIEWER)
        now = 2000
        history.record(VideoDiagnostics.Event.STOPPED)
        history.record(VideoDiagnostics.Event.SHARE_STARTED)
        assertEquals(2L, history.currentShare())
        now = 3000
        history.record(VideoDiagnostics.Event.OFFER_SENT, sharingSession = old)
        val lines = history.recent().lines()
        assertTrue(lines[0].contains("Session 1 · Video offer sent"))
        assertTrue(lines[1].contains("Session 2 · New authorized"))
        assertTrue(lines[2].contains("Session 1 · Screen sharing stopped"))
    }

    @Test fun `connection timeout and stalled encoder produce distinct honest evidence`() {
        VideoDiagnostics.terminal("media_connection_timeout")
        VideoDiagnostics.terminal("encoder_stalled")
        val text = VideoDiagnostics.summary()
        assertTrue(text.contains("connection_timeout="))
        assertTrue(text.contains("encoder_stalled="))
        assertTrue(text.contains("Video connection or first encoded frame timed out"))
        assertTrue(text.contains("Video encoding stopped progressing"))
    }

    @Test fun `process counters saturate without adding unbounded timeline entries`() {
        val history = VideoDiagnostics.Timeline { 0L }
        repeat(1_000_005) { history.record(VideoDiagnostics.Event.ICE_SENT, recent = false) }
        assertTrue(history.summary().contains("ice_sent=1000000"))
        assertFalse(history.summary().contains("1000005"))
        assertEquals("", history.recent())
    }
}
