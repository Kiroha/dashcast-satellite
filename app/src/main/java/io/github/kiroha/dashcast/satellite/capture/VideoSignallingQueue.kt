package io.github.kiroha.dashcast.satellite.capture

import org.json.JSONObject
import java.util.ArrayDeque

/** Owned by the serial WSS worker. Cancellation cannot emit a stop before its offer was sent. */
internal class VideoSignallingQueue {
    data class Pending(val text: String, val complete: (Boolean) -> Unit)
    private val values = ArrayDeque<Pending>()
    private var transmitted: String? = null
    fun offer(message: JSONObject, complete: (Boolean) -> Unit): Boolean {
        if (values.size >= 32) return false
        values.add(Pending(message.toString(), complete)); return true
    }
    fun cancel(id: String) {
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (JSONObject(item.text).optString("negotiation") == id) { iterator.remove(); item.complete(false) }
        }
        if (transmitted == id) {
            if (values.size >= 32) values.removeLast().complete(false)
            values.addFirst(Pending(JSONObject().put("type", "video.stop").put("negotiation", id).toString()) {})
        }
    }
    fun next(): Pending? = values.poll()
    fun sent(value: Pending) {
        val message = JSONObject(value.text)
        when (message.getString("type")) {
            "video.offer" -> transmitted = message.getString("negotiation")
            "video.stop" -> transmitted = null
        }
        value.complete(true)
    }
    fun clear() { transmitted = null; while(values.isNotEmpty()) values.remove().complete(false) }
}
