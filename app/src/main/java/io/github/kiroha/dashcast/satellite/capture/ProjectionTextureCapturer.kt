package io.github.kiroha.dashcast.satellite.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** One consent / one VirtualDisplay. Resize the display in place, never recreate it on API 34+.
 * Frames remain EGL textures; no Bitmap, readback or per-frame CPU pixel conversion. */
internal class ProjectionTextureCapturer(
    private val projection: MediaProjection,
    private val helper: SurfaceTextureHelper,
    private val observer: CapturerObserver,
    private val density: Int,
    private val stopped: (String) -> Unit,
    private val resized: (Int, Int) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var valid = true
    private var observedTexture = false
    private var lastOutputNs = 0L
    private var deliveryEnabled = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { valid = false; stopped("capture_revoked") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed.get() && width > 0 && height > 0) resize(width, height)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (closed.get()) return
            valid = isVisible
            helper.handler.removeCallbacks(hiddenDeadline)
            helper.handler.removeCallbacks(repeat)
            if (isVisible && deliveryEnabled) helper.handler.postDelayed(repeat, 500)
            else if (!isVisible) helper.handler.postDelayed(hiddenDeadline, 5000)
        }
    }
    private val hiddenDeadline = Runnable { if (!closed.get() && !valid) stopped("capture_hidden") }
    private val repeat = object : Runnable {
        override fun run() {
            if (closed.get() || !valid || !deliveryEnabled || display == null) return
            // A static map still needs decoded frames. Read the live SurfaceTexture again, rather
            // than retaining a buffer (which would prevent SurfaceTextureHelper receiving updates).
            if (observedTexture && surface?.isValid == true) helper.forceFrame()
            helper.handler.postDelayed(this, 500)
        }
    }
    fun start(width: Int, height: Int) {
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, Runnable {
            check(!closed.get())
            projection.registerCallback(projectionCallback, helper.handler)
            val size = dimensions(width, height)
            helper.setTextureSize(size.first, size.second)
            resized(size.first, size.second)
            surface = Surface(helper.surfaceTexture)
            helper.startListening { frame ->
                if (!closed.get() && valid) {
                    observedTexture = true
                    if (!deliveryEnabled) return@startListening
                    val now = System.nanoTime()
                    if (now - lastOutputNs >= 1_000_000_000L / VideoPolicy.FPS) {
                        lastOutputNs = now
                        // forceFrame preserves the SurfaceTexture timestamp on a static scene.
                        // Retimestamp only while the platform capture and display remain valid.
                        frame.buffer.retain()
                        val output = VideoFrame(frame.buffer, frame.rotation, now)
                        try { observer.onFrameCaptured(output) } finally { output.release() }
                    }
                }
            }
            display = projection.createVirtualDisplay("DashSat video", size.first, size.second,
                density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { valid = false; stopped("capture_paused") }
                    override fun onStopped() { valid = false; if (!closed.get()) stopped("capture_stopped") }
                }, helper.handler) ?: error("capture_unavailable")
            observer.onCapturerStarted(true)
            if (deliveryEnabled) helper.handler.postDelayed(repeat, 500)
        })
    }
    fun setDeliveryEnabled(enabled: Boolean) {
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, Runnable {
            if (closed.get()) return@Runnable
            deliveryEnabled = enabled
            lastOutputNs = 0L
            // The same continuously valid VD/surface supplies current textures. No buffer is
            // retained while waiting; forceFrame also keeps a static live map available on resume.
            helper.handler.removeCallbacks(repeat)
            if (enabled && valid) helper.handler.postDelayed(repeat, 500)
        })
    }
    private fun resize(width: Int, height: Int) {
        val size = dimensions(width, height)
        helper.setTextureSize(size.first, size.second)
        display?.resize(size.first, size.second, density)
        resized(size.first, size.second)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, Runnable {
            valid = false
            helper.handler.removeCallbacks(repeat)
            helper.handler.removeCallbacks(hiddenDeadline)
            helper.stopListening()
            display?.release(); display = null
            surface?.release(); surface = null
            projection.unregisterCallback(projectionCallback)
            projection.stop()
            observer.onCapturerStopped()
        })
    }
    companion object {
        fun dimensions(width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val scale = minOf(VideoPolicy.WIDTH.toDouble()/width, VideoPolicy.HEIGHT.toDouble()/height, 1.0)
            return (maxOf(2, (width * scale).roundToInt() / 2 * 2)) to
                (maxOf(2, (height * scale).roundToInt() / 2 * 2))
        }
    }
}
