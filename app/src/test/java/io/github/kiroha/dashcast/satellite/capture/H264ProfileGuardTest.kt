package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class H264ProfileGuardTest {
    private fun sps(profile: Int = 66, flags: Int = 224, level: Int = 31) =
        byteArrayOf(0, 0, 0, 1, 0x67, profile.toByte(), flags.toByte(), level.toByte(), 0x80.toByte())
    private val pps = byteArrayOf(0, 0, 1, 0x68, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 1, 0x65, 0x80.toByte())
    private val delta = byteArrayOf(0, 0, 1, 0x41, 0x80.toByte())
    private fun buffer(vararg values: ByteArray) = ByteBuffer.wrap(values.reduce(ByteArray::plus))
    private fun params(profile: String = "42e01f", asymmetry: String = "1") =
        mapOf("profile-level-id" to profile, "packetization-mode" to "1", "level-asymmetry-allowed" to asymmetry)

    @Test fun `negotiated contract refuses lower levels even with asymmetry and never assumes plain Baseline is constrained`() {
        for (profile in listOf("42e01f", "42e020", "4d801f", "58c01f", "42F01F"))
            for (asymmetry in listOf("0", "1")) assertTrue(profile, H264BaselineContract.permitsLevel31(params(profile, asymmetry)))
        for (profile in listOf("42e01e", "42e00b", "42001f", "64001f", "640c1f", "4d001f", "58401f", "424c1f", "42ec1f", "42e000", "42e0ff", "garbage"))
            for (asymmetry in listOf("0", "1")) assertFalse(profile, H264BaselineContract.permitsLevel31(params(profile, asymmetry)))
        assertFalse(H264BaselineContract.permitsLevel31(emptyMap()))
        assertFalse(H264BaselineContract.permitsLevel31(params() + ("packetization-mode" to "0")))
        assertFalse(H264BaselineContract.permitsLevel31(params() + ("level-asymmetry-allowed" to "2")))
    }
    @Test fun `actual constrained Baseline equivalent SPS patterns and lower levels pass without retaining or mutating buffers`() {
        for ((profile, flags) in listOf(66 to 224, 66 to 64, 77 to 128, 88 to 192)) {
            val guard = H264ProfileGuard({ true }) { fail("unexpected refusal") }
            val owner = guard.begin()
            val bytes = sps(profile, flags, 30) + pps + idr
            val raw = ByteBuffer.allocateDirect(bytes.size + 8).apply { position(4); put(bytes); limit(position()); position(4); mark() }
            val view = raw.asReadOnlyBuffer(); val position = view.position(); val limit = view.limit(); view.mark()
            assertTrue(guard.allow(owner, view)); assertEquals(position, view.position()); assertEquals(limit, view.limit()); view.reset()
            val copy = ByteArray(bytes.size); view.get(copy); assertArrayEquals(bytes, copy)
            assertTrue(guard.allow(owner, buffer(delta)))
        }
    }
    @Test fun `High Main plain Baseline bad flags excess level and a second incompatible SPS are blocked exactly once`() {
        for (bad in listOf(sps(100, 0), sps(77, 0), sps(66, 0), sps(66, 0x4c), sps(88, 0xcc), sps(level = 32), sps(level = 0), sps() + sps(100, 0))) {
            var failures = 0; val guard = H264ProfileGuard({ true }) { failures++ }; val owner = guard.begin()
            assertFalse(guard.allow(owner, buffer(bad, pps, idr)))
            assertFalse(guard.allow(owner, buffer(bad, pps, idr)))
            assertFalse(guard.allow(owner, buffer(sps(), pps, idr)))
            assertEquals(1, failures)
        }
    }
    @Test fun `guard has no startup budget and checks later internal reconfiguration even after many ordinary outputs`() {
        var failures = 0; val guard = H264ProfileGuard({ true }) { failures++ }; val owner = guard.begin()
        assertTrue(guard.allow(owner, buffer(sps(), pps, idr)))
        repeat(500) { assertTrue(guard.allow(owner, buffer(delta))) }
        assertFalse(guard.allow(owner, buffer(sps(100, 0), pps, idr)))
        assertEquals(1, failures)
    }
    @Test fun `first output needs observable SPS and bounded malformed prefixes are rejected`() {
        val cases = listOf(delta, idr, byteArrayOf(), byteArrayOf(0, 0, 1, 0x67, 66, -32),
            byteArrayOf(0, 0, 1, 0xe7.toByte(), 66, -32, 31), ByteArray(5000),
            ByteArray(0).let { (0..40).fold(it) { a, _ -> a + byteArrayOf(0, 0, 1, 6, 0x80.toByte()) } } + sps() + idr)
        cases.forEach { value ->
            var failures = 0; val guard = H264ProfileGuard({ true }) { failures++ }; val owner = guard.begin()
            assertFalse(guard.allow(owner, buffer(value))); assertEquals(1, failures)
        }
        // A large picture payload is not scanned or copied after the first slice header.
        val guard = H264ProfileGuard({ true }) { fail("picture payload scanned") }
        assertTrue(guard.allow(guard.begin(), buffer(sps(), pps, idr, ByteArray(2_000_000))))
    }
    @Test fun `old callbacks cannot deliver or fail a new initialization and retirement fences delivery`() {
        var failures = 0; var eligible = true
        val guard = H264ProfileGuard({ eligible }) { failures++ }
        val old = guard.begin(); val current = guard.begin()
        assertFalse(guard.allow(old, buffer(sps(100, 0), idr))); assertEquals(0, failures)
        assertFalse(guard.allow(current, buffer(delta))) // A previous generation's SPS cannot authorize this one.
        val next = H264ProfileGuard({ eligible }) { failures++ }; val token = next.begin()
        assertTrue(next.allow(token, buffer(sps(), idr)))
        next.retire(token); assertFalse(next.allow(token, buffer(sps(100, 0), idr)))
        val revoked = next.begin(); eligible = false
        assertFalse(next.allow(revoked, buffer(sps(100, 0), idr))); assertEquals(1, failures)
    }
    @Test fun `eligibility callback reinitialization returning true cannot authorize an old generation`() {
        lateinit var guard: H264ProfileGuard
        var checks = 0; var old: Any? = null; var failures = 0
        guard = H264ProfileGuard({
            if (++checks == 2) { guard.retire(old); guard.begin() }
            true
        }) { failures++ }
        old = guard.begin()
        assertFalse(guard.allow(checkNotNull(old), buffer(sps(), idr)))
        assertEquals(0, failures)
    }
}
