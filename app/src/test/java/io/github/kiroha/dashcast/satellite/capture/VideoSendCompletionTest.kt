package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class VideoSendCompletionTest {
    @Test fun `sent offer and ICE remain historical when owner is replaced before worker callback`() {
        for (event in listOf(VideoDiagnostics.Event.OFFER_SENT, VideoDiagnostics.Event.ICE_SENT)) {
            val pending = ArrayDeque<() -> Unit>()
            val evidence = mutableListOf<Pair<VideoDiagnostics.Event, Long>>()
            val old = VideoNegotiation("old")
            var current = old
            var failures = 0
            val delivered = mutableListOf<String>()
            old.localIce("queued candidate", delivered::add)
            val completion = VideoSendCompletion(event, 1, pending::add,
                owns = { current === old && !old.closed },
                sent = { old.offerSent(delivered::add) }, failed = { failures++ },
                record = { value, session -> evidence += value to session })
            completion.complete(true)
            // Evidence is immediate, before any capture-worker action can run.
            assertEquals(listOf(event to 1L), evidence)
            old.close(); current = VideoNegotiation("replacement")
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
            assertTrue(delivered.isEmpty())
            assertEquals(0, failures)
            assertFalse(current.closed)
        }
    }

    @Test fun `late successful send is retained even when capture worker drops posts after shutdown`() {
        val evidence = mutableListOf<Pair<VideoDiagnostics.Event, Long>>()
        val completion = VideoSendCompletion(VideoDiagnostics.Event.OFFER_SENT, 4, post = {}, owns = { false },
            sent = { fail("closed capture mutated") }, failed = { fail("closed capture failed again") },
            record = { value, session -> evidence += value to session })
        completion.complete(true)
        assertEquals(listOf(VideoDiagnostics.Event.OFFER_SENT to 4L), evidence)
    }

    @Test fun `cancelled queued send is not reported sent and cannot fail a replacement`() {
        val pending = ArrayDeque<() -> Unit>()
        var owns = true
        val completion = VideoSendCompletion(VideoDiagnostics.Event.OFFER_SENT, 1, pending::add, owns = { owns },
            sent = { fail("cancelled offer advanced") }, failed = { fail("replacement failed") },
            record = { _, _ -> fail("cancelled offer reported sent") })
        completion.complete(false)
        owns = false
        pending.removeFirst().invoke()
    }

    @Test fun `current send completion flushes candidate queue only on worker and reports current failure`() {
        val pending = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        var failures = 0
        val current = VideoNegotiation("current")
        current.localIce("queued candidate", delivered::add)
        val completion = VideoSendCompletion(VideoDiagnostics.Event.OFFER_SENT, 1, pending::add,
            owns = { !current.closed }, sent = { current.offerSent(delivered::add) }, failed = { failures++ },
            record = { _, _ -> })
        completion.complete(true)
        assertTrue(delivered.isEmpty())
        pending.removeFirst().invoke()
        assertEquals(listOf("queued candidate"), delivered)
        completion.complete(false)
        assertEquals(0, failures)
        pending.removeFirst().invoke()
        assertEquals(1, failures)
    }
}
