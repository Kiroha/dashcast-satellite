package io.github.kiroha.dashcast.satellite.pairing

/** Public display information only. The full certificate pin remains in the protected profile. */
data class SavedReceiver(val displayId: String, val hosts: List<String>) {
    companion object {
        fun from(profile: PairingProfile) = SavedReceiver(
            SatelliteDeviceIdentity.receiverId(profile.certificateSha256),
            profile.hosts.toList(),
        )
    }
}
