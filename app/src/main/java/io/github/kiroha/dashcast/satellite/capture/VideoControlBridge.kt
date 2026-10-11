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
    @Volatile var viewerReady = false
        internal set
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
    fun connected(value: VideoLink) { link = value; listeners.forEach { it.linkChanged(value) } }
    fun disconnected(generation: Long) {
        if (link?.generation != generation) return
        link = null
        listeners.forEach { it.linkChanged(null) }
    }
    fun receive(owner: VideoLink, message: JSONObject) {
        if (link !== owner) return
        when (message.optString("type")) {
            "video.ready" -> owner.viewerReady = true
            "video.closed" -> owner.viewerReady = false
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
    val active: Boolean get() = status in setOf("starting", "negotiating", "connected_waiting_frames", "streaming")
}
