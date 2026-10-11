package io.github.kiroha.dashcast.satellite.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Retained only across configuration changes; explicit exit cancels and removes staged downloads. */
class UpdateSession(private val client: UpdateBackend,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()) {
    constructor(context: Context) : this(UpdateClient(context.applicationContext))
    private val handler = Handler(Looper.getMainLooper())
    var observer: (() -> Unit)? = null
    var state: String = "idle"; private set
    var release: UpdateRelease? = null; private set
    var file: File? = null; private set
    var percent: Int = 0; private set
    @Volatile private var closed = false
    private fun publish(change: () -> Unit) = handler.post {
        if (!closed) { change(); observer?.invoke() }
    }
    fun check() {
        if (state in listOf("checking", "downloading", "verifying")) return
        state = "checking"; release = null; file?.delete(); file = null; observer?.invoke()
        executor.execute {
            try { val latest = client.latest(); publish { release = latest; state = if (latest == null) "current" else "available" } }
            catch (_: Exception) { publish { state = "error" } }
        }
    }
    fun download() {
        val candidate = release ?: return
        if (state != "available" && state != "error") return
        state = "downloading"; percent = 0; observer?.invoke()
        executor.execute {
            try {
                val result = client.download(candidate) { progress ->
                    if (progress != percent) publish { percent = progress }
                }
                handler.post {
                    if (closed) result.delete() else { file = result; state = "ready"; observer?.invoke() }
                }
            } catch (_: Exception) { publish { state = "error" } }
        }
    }
    fun verifyForInstall() {
        val candidate = release ?: return
        val staged = file ?: return
        if (state != "ready") return
        state = "verifying"; observer?.invoke()
        executor.execute {
            try { client.validate(staged, candidate); publish { state = "installable" } }
            catch (_: Exception) { staged.delete(); publish { file = null; state = "error" } }
        }
    }
    fun installerOpened() { state = "ready" }
    fun installerFailed() { state = "ready"; observer?.invoke() }
    fun close() {
        closed = true; observer = null; client.cancel(); executor.shutdownNow(); file?.delete()
    }
}
