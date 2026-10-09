package io.github.kiroha.dashcast.satellite.transport

import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

/** Owns one attempt, including sockets not yet returned to Java-WebSocket. */
internal open class CancellableSocketFactory(
    private val createRawSocket: () -> Socket,
    private val prepareSocket: (Socket) -> Socket,
) : SocketFactory(), Closeable {
    private val lock = Any()
    private val sockets = ArrayList<Socket>(2)
    private var started = false
    private var cancelled = false

    final override fun createSocket(): Socket {
        synchronized(lock) {
            if (cancelled || started) throw SocketException("Connection attempt ended")
            started = true
        }
        return try {
            val raw = retain(createRawSocket())
            // The raw socket is already owned while connect/TLS setup can block.
            retain(prepareSocket(raw))
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun retain(socket: Socket): Socket {
        synchronized(lock) {
            if (!cancelled) {
                sockets.add(socket)
                return socket
            }
        }
        closeQuietly(socket)
        throw SocketException("Connection attempt ended")
    }

    final override fun close() {
        val owned = synchronized(lock) {
            cancelled = true
            sockets.toList().also { sockets.clear() }
        }
        // Retain ownership after return too: cancellation may precede the library's assignment.
        owned.asReversed().forEach(::closeQuietly)
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
    }

    final override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
    final override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    final override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
    final override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
}
