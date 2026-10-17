package io.github.kiroha.dashcast.satellite.capture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class H264ProfileFailureTest {
    private fun link(generation: Long) = VideoLink(generation,
        VideoLan(ShadowNetwork.newInstance(303), "wlan0", setOf("192.168.49.2"), true, "192.168.49.1")) { _, _ -> }
    @Test fun `duplicate reports only post once and never execute failure on the codec callback`() {
        val work = mutableListOf<() -> Unit>(); var failures = 0
        val ticket = StartupKeyframePolicy({ true })
        val signal = H264ProfileFailure(ticket::ownsSource, work::add, { true }) { failures++ }
        repeat(20) { signal.report() }
        assertEquals(1, work.size); assertEquals(0, failures)
        work.single().invoke(); assertEquals(1, failures)
    }
    @Test fun `queued failure cannot affect a replacement after viewer source WSS terminal or ticket invalidation`() {
        for (mode in 0..5) {
            val owner = link(4001); VideoControlBridge.connected(owner); owner.viewerReady = true
            var sourceEpoch = 1; var ending = false; var current = true
            val viewer = owner.viewerReadyEpoch
            val ticket = StartupKeyframePolicy({ !ending && sourceEpoch == 1 && owner.ownsViewer(viewer) && VideoControlBridge.link === owner })
            val work = mutableListOf<() -> Unit>(); var failures = 0
            val signal = H264ProfileFailure(ticket::ownsSource, work::add, { current }) { failures++ }
            try {
                signal.report(); assertEquals(1, work.size)
                when (mode) {
                    0 -> { VideoControlBridge.receive(owner, JSONObject().put("type", "video.closed")); VideoControlBridge.receive(owner, JSONObject().put("type", "video.ready")) }
                    1 -> sourceEpoch = 2
                    2 -> VideoControlBridge.connected(link(4002))
                    3 -> ending = true
                    4 -> ticket.close()
                    5 -> current = false
                }
                work.single().invoke(); signal.report(); assertEquals(0, failures)
            } finally { VideoControlBridge.link?.let { VideoControlBridge.disconnected(it.generation) } }
        }
    }
}
