package io.github.kiroha.dashcast.satellite.capture

/** Texture-thread state for one continuously authorized display, never a new capture grant.
 * Visibility and VirtualDisplay pause are independent. After either interruption, a cached
 * forceFrame timestamp cannot make the source ready: a new platform texture is required. */
internal class CaptureAvailability(private val changed: (Boolean) -> Unit) {
    private var visible = true
    private var resumed = true
    private var stopped = false
    private var fresh = false
    private var lastTexture: Long? = null
    private data class Output(val ready: Boolean, val epoch: Long)
    @Volatile private var output = Output(false, 0)
    val ready: Boolean get() = output.ready
    val readyEpoch: Long? get() = output.let { if (it.ready) it.epoch else null }
    fun owns(epoch: Long?): Boolean = output.let { it.ready && it.epoch == epoch }
    fun visibility(value: Boolean) {
        if (stopped) return
        visible = value
        if (!value) fresh = false
        reconcile()
    }
    fun displayResumed(value: Boolean) {
        if (stopped) return
        resumed = value
        if (!value) fresh = false
        reconcile()
    }
    fun texture(timestamp: Long): Boolean {
        if (stopped) return false
        val newer = lastTexture?.let { timestamp > it } ?: true
        if (newer) lastTexture = timestamp // Late callbacks cannot lower the freshness floor.
        if (!visible || !resumed || (!fresh && !newer)) return false
        fresh = true
        reconcile()
        return ready
    }
    fun stop() { stopped = true; fresh = false; reconcile() }
    private fun reconcile() {
        val value = !stopped && visible && resumed && fresh
        val previous = output
        if (value != previous.ready) {
            // Publish readiness and epoch atomically: a worker must never combine old ownership
            // with restored readiness before its ordered pause callback has drained.
            output = Output(value, previous.epoch + if (!value) 1 else 0)
            changed(value)
        }
    }
}
