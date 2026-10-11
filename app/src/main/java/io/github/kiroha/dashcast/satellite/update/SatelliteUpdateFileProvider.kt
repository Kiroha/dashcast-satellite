package io.github.kiroha.dashcast.satellite.update

import androidx.core.content.FileProvider

/** A dedicated provider avoids device-specific problems with declaring FileProvider directly. */
class SatelliteUpdateFileProvider : FileProvider()
