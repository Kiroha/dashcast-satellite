package io.github.kiroha.dashcast.satellite.capture

import android.media.projection.MediaProjection
import org.json.JSONObject
import java.io.Closeable

/** The continuous Android capture lease is independent of each visible-viewer peer. */
internal interface VideoCaptureEngine : Closeable {
    fun start(projection: MediaProjection, width: Int, height: Int, density: Int)
    fun viewerChanged(ready: Boolean)
    fun signal(message: JSONObject)
    fun collectStats()
}

/** Serialized by the service worker. A capture is initialized once, even across viewer resumes. */
internal class ViewerCaptureSession(
    private val initialize: () -> Unit,
    private val resume: () -> Unit,
    private val pause: () -> Unit,
    private val waiting: () -> Unit,
    private val isReady: () -> Boolean,
) {
    private var initialized = false
    private var sending = false
    private var closed = false
    fun viewerClosed() {
        if (closed) return
        sending = false
        pause()
        waiting()
    }
    fun reconcile() {
        if (closed) return
        if (isReady()) {
            if (sending) return
            if (!initialized) { initialize(); initialized = true }
            // Initial native setup can outlive the receiver's foreground transition.
            if (!isReady()) { pause(); waiting(); return }
            sending = true
            resume()
        } else {
            viewerClosed()
        }
    }
    fun close() { closed = true; sending = false }
}
