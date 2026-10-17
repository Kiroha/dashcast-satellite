package io.github.kiroha.dashcast.satellite.capture

/** Worker-owned native allocation ledger. Partial initialization and teardown failures still drain owners. */
internal class PeerVideoResources(private val invalidate: () -> Unit, private val unbind: () -> Unit) {
    private val releases = ArrayDeque<() -> Unit>()
    private var closed = false
    fun <T> own(value: T, release: (T) -> Unit): T {
        check(!closed)
        releases.addFirst { release(value) }
        return value
    }
    fun close() {
        if (closed) return
        closed = true
        runCatching(invalidate)
        runCatching(unbind)
        while (releases.isNotEmpty()) runCatching(releases.removeFirst())
    }
}
