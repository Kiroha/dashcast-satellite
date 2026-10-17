package io.github.kiroha.dashcast.satellite.capture

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/** Texture-thread only. Binding/unbinding is synchronous there; no captured frame is retained. */
internal class CaptureObserverRelay : CapturerObserver {
    private data class Binding(val observer: CapturerObserver, val resize: (Int, Int) -> Unit,
        val eligible: () -> Boolean, var started: Boolean = false)
    private var binding: Binding? = null
    private var running = false
    private var width = 0
    private var height = 0
    fun bind(observer: CapturerObserver, resize: (Int, Int) -> Unit, eligible: () -> Boolean) {
        check(binding == null)
        val current = Binding(observer, resize, eligible).also { binding = it }
        if (width > 0 && height > 0) resize(width, height)
        if (running && eligible()) { current.started = true; observer.onCapturerStarted(true) }
    }
    fun unbind() {
        val current = binding
        binding = null // Even a throwing delegate cannot leave a source attached during disposal.
        if (current?.started == true) current.observer.onCapturerStopped()
    }
    fun resized(width: Int, height: Int) {
        this.width = width; this.height = height
        binding?.takeIf { it.eligible() }?.resize?.invoke(width, height)
    }
    override fun onCapturerStarted(success: Boolean) {
        running = success
        binding?.takeIf { it.eligible() }?.let { it.started = success; it.observer.onCapturerStarted(success) }
    }
    override fun onCapturerStopped() {
        running = false
        binding?.let { if (it.started) { it.started = false; it.observer.onCapturerStopped() } }
    }
    override fun onFrameCaptured(frame: VideoFrame) {
        binding?.takeIf { it.started && it.eligible() }?.observer?.onFrameCaptured(frame)
    }
}
