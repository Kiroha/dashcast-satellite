package io.github.kiroha.dashcast.satellite.capture

import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkChangeDetectorFactory
import java.net.InetAddress

/** Native WebRTC receives only the authenticated LAN handle, allowing its Android socket binder
 * to bind UDP to that network. Filtering WSS alone does not route native media sockets. */
internal class LanNetworkDetector(private val lan: VideoLan) : NetworkChangeDetector {
    private val type = if (lan.wifi) NetworkChangeDetector.ConnectionType.CONNECTION_WIFI
        else NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET
    override fun getCurrentConnectionType() = type
    override fun supportNetworkCallback() = true
    override fun getActiveNetworkList() = listOf(NetworkChangeDetector.NetworkInformation(
        lan.interfaceName, type, NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
        lan.network.networkHandle, lan.addresses.map {
            NetworkChangeDetector.IPAddress(InetAddress.getByName(it).address)
        }.toTypedArray(), NetworkChangeDetector.NetworkSlice.NO_SLICE))
    override fun destroy() = Unit // Control socket owns LAN loss and immediately destroys the peer.
    companion object {
        fun factory(lan: VideoLan) = NetworkChangeDetectorFactory { _, _ -> LanNetworkDetector(lan) }
    }
}
