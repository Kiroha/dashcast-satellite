package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class ViewerCaptureSessionTest {
    @Test fun `waiting has no native initialization and repeated ready creates one capture`() {
        var ready = false
        var displays = 0
        var resumes = 0
        var pauses = 0
        var waits = 0
        val session = ViewerCaptureSession({ displays++ }, { resumes++ }, { pauses++ }, { waits++ }, { ready })
        session.reconcile()
        assertEquals(0, displays); assertEquals(0, resumes); assertEquals(1, waits)
        ready = true; session.reconcile(); session.reconcile()
        assertEquals(1, displays); assertEquals(1, resumes)
        ready = false; session.reconcile()
        ready = true; session.reconcile()
        assertEquals(1, displays); assertEquals(2, resumes)
        assertEquals(2, pauses)
        session.close(); session.reconcile()
        assertEquals(2, resumes)
    }
    @Test fun `viewer closing during first initialization cannot resume stale ready`() {
        var ready = true
        var displays = 0
        var resumes = 0
        val session = ViewerCaptureSession({ displays++; ready = false }, { resumes++ }, {}, {}, { ready })
        session.reconcile()
        assertEquals(1, displays); assertEquals(0, resumes)
        ready = true; session.reconcile()
        assertEquals(1, displays); assertEquals(1, resumes)
    }
    @Test fun `ordered close followed by queued ready fences old peer even when latest state is ready`() {
        var peers = 0
        var pauses = 0
        val session = ViewerCaptureSession({}, { peers++ }, { pauses++ }, {}, { true })
        session.reconcile()
        session.viewerClosed()
        session.reconcile()
        assertEquals(2, peers); assertEquals(1, pauses)
    }
}
