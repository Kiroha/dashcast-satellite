package io.github.kiroha.dashcast.satellite

import io.github.kiroha.dashcast.satellite.transport.TransportState
import io.github.kiroha.dashcast.satellite.transport.TransportStatus

/** Contains status only, never pairing material or source content. */
object SatelliteState {
    private var owner = 0L
    @Volatile var transport = TransportStatus(TransportState.STOPPED)
        private set

    @Synchronized fun beginSession(): Long = ++owner

    @Synchronized fun update(session: Long, status: TransportStatus) {
        if (owner == session) transport = status
    }

    @Synchronized fun endSession(session: Long, failed: Boolean = false) {
        if (owner == session) {
            owner++
            transport = TransportStatus(TransportState.STOPPED,
                detail = if (failed) "service_start_failed" else null)
        }
    }
}
