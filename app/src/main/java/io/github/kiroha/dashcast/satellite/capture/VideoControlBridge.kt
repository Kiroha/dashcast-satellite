package io.github.kiroha.dashcast.satellite.capture

import android.net.Network
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** An authenticated WSS generation, never a profile/token. UDP uses this same LAN explicitly. */
data class VideoLan(val network: Network, val interfaceName: String, val addresses: Set<String>, val wifi: Boolean, val receiverHost: String)
class VideoLink(val generation: Long, val lan: VideoLan,
    private val terminate: (String) -> Unit = {},
    private val transmit: (JSONObject, (Boolean) -> Unit) -> Unit) {
    private data class Viewer(val ready: Boolean, val epoch: Long)
    @Volatile private var viewer = Viewer(false, 0)
    var viewerReady: Boolean
        get() = viewer.ready
        internal set(value) = synchronized(this) {
            val previous = viewer
            viewer = Viewer(value, previous.epoch + if (previous.ready && !value) 1 else 0)
        }
    internal val viewerReadyEpoch: Long? get() = viewer.let { if (it.ready) it.epoch else null }
    internal fun ownsViewer(epoch: Long?): Boolean = viewer.let { it.ready && it.epoch == epoch }
    fun send(message: JSONObject, complete: (Boolean) -> Unit = {}) = transmit(message, complete)
    fun endNegotiation(id: String) = terminate(id)
}

/** One control socket shared by guidance and video; there is no second authentication path. */
object VideoControlBridge {
    interface Listener {
        fun linkChanged(link: VideoLink?)
        fun signal(link: VideoLink, message: JSONObject)
    }
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var link: VideoLink? = null
        private set
    fun subscribe(listener: Listener): Closeable {
        listeners.add(listener)
        listener.linkChanged(link)
        return Closeable { listeners.remove(listener) }
    }
    fun connected(value: VideoLink) {
        link = value
        VideoDiagnostics.record(VideoDiagnostics.Event.CONTROL_CONNECTED)
        listeners.forEach { it.linkChanged(value) }
    }
    fun disconnected(generation: Long) {
        if (link?.generation != generation) return
        link = null
        VideoDiagnostics.record(VideoDiagnostics.Event.CONTROL_CLOSED)
        listeners.forEach { it.linkChanged(null) }
    }
    fun receive(owner: VideoLink, message: JSONObject) {
        if (link !== owner) return
        when (message.optString("type")) {
            "video.ready" -> { owner.viewerReady = true; VideoDiagnostics.record(VideoDiagnostics.Event.VIEWER_READY) }
            "video.closed" -> { owner.viewerReady = false; VideoDiagnostics.record(VideoDiagnostics.Event.VIEWER_CLOSED) }
        }
        listeners.forEach { it.signal(owner, message) }
    }
}

object VideoState {
    @Volatile var status: String = "stopped"
        internal set
    @Volatile var metrics: String = ""
        internal set
    @Volatile var captureBusy = false
        internal set
    val active: Boolean get() = status in setOf("starting", "waiting_viewer", "capture_paused", "negotiating", "connected_waiting_frames", "streaming")
}
