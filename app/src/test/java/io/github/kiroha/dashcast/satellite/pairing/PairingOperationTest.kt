package io.github.kiroha.dashcast.satellite.pairing

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PairingOperationTest {
    @After fun clear() { PairingOperation.forget {} }

    @Test fun `cancelled activity cannot save or cancel its replacement`() {
        val previous = PairingOperation.begin()!!
        assertNull(PairingOperation.begin())
        PairingOperation.cancel(previous)
        val replacement = PairingOperation.begin()!!
        PairingOperation.cancel(previous)
        assertTrue(PairingOperation.busy)
        assertFalse(PairingOperation.complete(previous) { fail("Late profile write") })
        var stored = false
        assertTrue(PairingOperation.complete(replacement) { stored = true })
        assertTrue(stored)
        assertFalse(PairingOperation.busy)
    }

    @Test fun `forget invalidates pending import before deleting the old profile`() {
        val ticket = PairingOperation.begin()!!
        var stored = true
        PairingOperation.forget { stored = false }
        assertFalse(PairingOperation.complete(ticket) { stored = true })
        assertFalse(stored)
    }

    @Test fun `failed storage releases the operation for retry`() {
        val revision = PairingOperation.revision
        val ticket = PairingOperation.begin()!!
        assertThrows(IllegalStateException::class.java) {
            PairingOperation.complete(ticket) { error("Unavailable storage") }
        }
        assertFalse(PairingOperation.busy)
        assertTrue(PairingOperation.revision > revision)
        assertNotNull(PairingOperation.begin())
    }
}
