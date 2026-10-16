package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureAvailabilityTest {
    @Test fun `initial capture and resume require a real new texture before any cached repeat`() {
        val changes = mutableListOf<Boolean>()
        val source = CaptureAvailability { changes += it }
        assertFalse(source.ready)
        assertTrue(source.texture(100)); assertTrue(source.texture(100))
        source.visibility(false); source.visibility(true)
        assertFalse(source.ready)
        assertFalse(source.texture(100)) // An already queued forceFrame is not a new platform texture.
        assertFalse(source.texture(99)) // A late older callback cannot establish a new epoch either.
        assertTrue(source.texture(101))
        assertEquals(listOf(true, false, true), changes)
    }
    @Test fun `hidden source never becomes available from textures or a display resume`() {
        val source = CaptureAvailability {}
        source.texture(100)
        source.visibility(false)
        source.displayResumed(false); source.displayResumed(true)
        assertFalse(source.texture(101)); assertFalse(source.ready)
        source.visibility(true)
        assertFalse(source.ready)
        assertFalse(source.texture(101)) // A frame already observed while hidden is not fresh now.
        assertTrue(source.texture(102))
    }
    @Test fun `visible source still waits for display resume and then a new texture`() {
        val source = CaptureAvailability {}
        source.texture(100)
        source.displayResumed(false)
        source.visibility(false); source.visibility(true)
        assertFalse(source.texture(101)); assertFalse(source.ready)
        source.displayResumed(true)
        assertFalse(source.texture(100))
        assertTrue(source.texture(102))
    }
    @Test fun `terminal stop cannot be revived by visibility display or texture callbacks`() {
        val changes = mutableListOf<Boolean>()
        val source = CaptureAvailability { changes += it }
        source.texture(100); source.stop(); source.stop()
        source.visibility(true); source.displayResumed(true)
        assertFalse(source.texture(101)); assertFalse(source.ready)
        assertEquals(listOf(true, false), changes)
    }
    @Test fun `ordered hidden visible bursts preserve the pause before a fresh source`() {
        val changes = mutableListOf<Boolean>()
        val source = CaptureAvailability { changes += it }
        source.texture(1)
        repeat(10) { index -> source.visibility(false); source.visibility(true); source.texture(index + 2L) }
        assertEquals(21, changes.size)
        assertEquals(listOf(false, true), changes.takeLast(2))
    }
    @Test fun `late older callbacks cannot lower the freshness floor while hidden or display paused`() {
        for (hide in listOf(true, false)) {
            val source = CaptureAvailability {}
            source.texture(100)
            if (hide) source.visibility(false) else source.displayResumed(false)
            source.texture(99)
            if (hide) source.visibility(true) else source.displayResumed(true)
            assertFalse(source.texture(100))
            assertTrue(source.texture(101))
        }
    }
    @Test fun `restored availability never revives a previous peer or delivery epoch`() {
        val source = CaptureAvailability {}
        source.texture(100)
        val old = source.readyEpoch
        assertTrue(source.owns(old))
        source.visibility(false)
        assertFalse(source.owns(old))
        source.visibility(true); source.texture(101)
        assertTrue(source.ready)
        assertFalse(source.owns(old))
        assertTrue(source.owns(source.readyEpoch))
        source.displayResumed(false); source.displayResumed(true); source.texture(102)
        assertFalse(source.owns(old))
        assertFalse(source.owns(null))
    }
    @Test fun `source visibility and display pause remain distinct until both restore`() {
        for (visibleFirst in listOf(true, false)) {
            val source = CaptureAvailability {}
            source.texture(100)
            source.visibility(false); source.displayResumed(false)
            if (visibleFirst) source.visibility(true) else source.displayResumed(true)
            assertFalse(source.texture(101)); assertFalse(source.ready)
            if (visibleFirst) source.displayResumed(true) else source.visibility(true)
            assertFalse(source.ready)
            assertTrue(source.texture(102))
        }
    }
}
