package io.github.kiroha.dashcast.satellite.capture

import java.util.concurrent.atomic.AtomicBoolean

/** One immutable attempt's failure: codec callbacks only latch/post, and the worker rechecks ownership. */
internal class H264ProfileFailure(
    private val eligible: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val current: () -> Boolean,
    private val failed: () -> Unit,
) {
    private val posted = AtomicBoolean(false)
    fun report() {
        if (!eligible() || !posted.compareAndSet(false, true)) return
        post { if (eligible() && current()) failed() }
    }
}
