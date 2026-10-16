package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

/** The same policies used by the native sender, including ordered texture-to-worker callbacks. */
class CaptureContinuationTest {
    private class Session {
        var viewerReady = true
        var displays = 0
        var peers = 0
        var pauses = 0
        var initialized = false
        var ended = false
        val callbacks = ArrayDeque<() -> Unit>()
        val capture: CaptureAvailability = CaptureAvailability { ready -> callbacks.addLast {
            if (!ended) {
                if (!ready) viewer.viewerClosed() else viewer.reconcile()
            }
        } }
        val viewer: ViewerCaptureSession = ViewerCaptureSession(
            initialize = { displays++; initialized = true },
            resume = { peers++ }, pause = { pauses++ }, waiting = {},
            isReady = { !ended && viewerReady && (!initialized || capture.ready) },
        )
        fun drain() { while (callbacks.isNotEmpty()) callbacks.removeFirst()() }
        fun stop() { ended = true; viewer.close(); capture.stop() }
    }
    @Test fun `one display and consent survive a source hidden longer than five seconds`() {
        val session = Session()
        session.viewer.reconcile()
        assertEquals(1, session.displays); assertEquals(0, session.peers)
        session.capture.texture(100); session.drain()
        assertEquals(1, session.peers)
        session.capture.visibility(false); session.drain()
        assertFalse(session.capture.ready)
        // No deadline exists: restoration, rather than elapsed time, controls this valid lease.
        session.capture.visibility(true)
        assertFalse(session.capture.texture(100)); session.drain()
        assertEquals(1, session.peers)
        session.capture.texture(101); session.drain()
        assertEquals(2, session.peers); assertEquals(1, session.displays)
    }
    @Test fun `hidden then fresh burst pauses the old peer even if latest availability is already ready`() {
        val session = Session()
        session.viewer.reconcile(); session.capture.texture(100); session.drain()
        val pauses = session.pauses
        session.capture.visibility(false); session.capture.visibility(true); session.capture.texture(101)
        assertTrue(session.capture.ready)
        session.drain()
        assertEquals(pauses + 1, session.pauses)
        assertEquals(2, session.peers); assertEquals(1, session.displays)
    }
    @Test fun `returning to the shared app without a viewer keeps the same display and no sending peer`() {
        val session = Session()
        session.viewer.reconcile(); session.capture.texture(100); session.drain()
        session.viewerReady = false; session.viewer.viewerClosed()
        session.capture.visibility(false); session.capture.visibility(true); session.capture.texture(101); session.drain()
        assertEquals(1, session.peers); assertEquals(1, session.displays)
        session.viewerReady = true; session.viewer.reconcile()
        assertEquals(2, session.peers); assertEquals(1, session.displays)
    }
    @Test fun `both visibility and display must restore before a new peer while terminal stop forbids revival`() {
        val session = Session()
        session.viewer.reconcile(); session.capture.texture(100); session.drain()
        session.capture.visibility(false); session.capture.displayResumed(false); session.drain()
        session.capture.visibility(true); session.capture.texture(101); session.drain()
        assertEquals(1, session.peers)
        session.capture.displayResumed(true); session.capture.texture(102); session.drain()
        assertEquals(2, session.peers)
        session.capture.visibility(false); session.stop()
        session.capture.visibility(true); session.capture.displayResumed(true); session.capture.texture(103); session.drain()
        session.viewer.reconcile()
        assertEquals(2, session.peers); assertEquals(1, session.displays)
    }
}
