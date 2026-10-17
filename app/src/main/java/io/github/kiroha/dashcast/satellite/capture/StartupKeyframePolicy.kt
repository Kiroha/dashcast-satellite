package io.github.kiroha.dashcast.satellite.capture

/** One immutable peer ticket. Only extra startup requests use this budget; media liveness does not. */
internal class StartupKeyframePolicy(
    private val eligible: () -> Boolean,
    private val clock: () -> Long = System::nanoTime,
) {
    @Volatile private var closed = false
    private var connectedAt: Long? = null
    private var lastSeen: Long? = null
    private var lastKey: Long? = null
    private var opportunity = 0
    private var encoder: Any? = null
    private var retired = false

    fun ownsSource(): Boolean = !closed && eligible()
    @Synchronized fun connected() {
        if (connectedAt == null && ownsSource()) connectedAt = clock()
    }
    /** The first initialized H264 encoder owns recovery. Replacement/reinit never resets its budget. */
    @Synchronized fun bind(owner: Any): Boolean {
        if (retired || encoder != null || !ownsSource()) return false
        encoder = owner
        return true
    }
    @Synchronized fun retire(owner: Any) { if (encoder === owner) retired = true }
    @Synchronized fun request(owner: Any, nativeKey: Boolean, encodable: Boolean): Boolean {
        if (retired || encoder !== owner || !ownsSource()) return false
        val start = connectedAt ?: return false
        val now = clock()
        if (now < start || lastSeen?.let { now < it } == true) { retired = true; return false }
        lastSeen = now
        val elapsed = now - start
        if (elapsed >= 12_000_000_000L) { retired = true; return false }
        if (!encodable && !nativeKey) return false // Never promote EMPTY, but account for a native KEY in a mixed hint.
        var due = false
        while (opportunity < delays.size && elapsed >= delays[opportunity]) { opportunity++; due = true }
        val previousKey = lastKey
        if (nativeKey) { lastKey = now; return false } // Preserve the SDK's request and consume a due slot.
        if (!due || previousKey?.let { now - it < 2_000_000_000L } == true) return false
        lastKey = now
        return true
    }
    fun close() { closed = true }
    companion object { private val delays = longArrayOf(2_000_000_000L, 5_000_000_000L, 9_000_000_000L) }
}
