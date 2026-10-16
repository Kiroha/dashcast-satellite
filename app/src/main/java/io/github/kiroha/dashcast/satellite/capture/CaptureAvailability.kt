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
    @Volatile var ready = false
        private set
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
        if (value != ready) { ready = value; changed(value) }
    }
}
