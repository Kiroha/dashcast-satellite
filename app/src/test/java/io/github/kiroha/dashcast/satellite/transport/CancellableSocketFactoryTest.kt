package io.github.kiroha.dashcast.satellite.transport

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancellableSocketFactoryTest {
    @Test fun `cancel before creation never allocates a socket`() {
        val factory = CancellableSocketFactory({ error("Must not allocate") }, { it })
        factory.close()
        try { factory.createSocket(); fail("Cancelled attempt accepted") }
        catch (_: SocketException) {}
    }

    @Test fun `cancel while raw socket creation finishes closes the late socket`() {
        val raw = Socket()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factory = CancellableSocketFactory({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            raw
        }, { error("Cancelled raw socket must not be prepared") })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val attempt = executor.submit<Socket> { factory.createSocket() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            factory.close()
            release.countDown()
            try { attempt.get(5, TimeUnit.SECONDS); fail("Cancelled socket returned") }
            catch (error: ExecutionException) { assertTrue(error.cause is SocketException) }
            assertTrue(raw.isClosed)
        } finally {
            release.countDown()
            factory.close()
            raw.close()
            executor.shutdownNow()
        }
    }

    @Test fun `cancel during preparation closes raw immediately and the late wrapper on return`() {
        val raw = Socket()
        val wrapped = Socket()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factory = CancellableSocketFactory({ raw }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            wrapped
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val attempt = executor.submit<Socket> { factory.createSocket() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            factory.close()
            assertTrue("Blocking connect must be cancellable", raw.isClosed)
            release.countDown()
            try { attempt.get(5, TimeUnit.SECONDS); fail("Cancelled socket returned") }
            catch (error: ExecutionException) { assertTrue(error.cause is SocketException) }
            assertTrue(wrapped.isClosed)
        } finally {
            release.countDown()
            factory.close()
            raw.close()
            wrapped.close()
            executor.shutdownNow()
        }
    }

    @Test fun `cancel owns both sockets after returning to the library`() {
        val raw = Socket()
        val wrapped = Socket()
        val factory = CancellableSocketFactory({ raw }, { wrapped })
        assertSame(wrapped, factory.createSocket())
        factory.close()
        assertTrue(raw.isClosed)
        assertTrue(wrapped.isClosed)
        factory.close()
    }
}
