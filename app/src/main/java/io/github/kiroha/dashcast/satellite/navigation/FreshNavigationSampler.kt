package io.github.kiroha.dashcast.satellite.navigation

/** Every sample reads the OS again; a timer has no cached Guidance to relabel as fresh. */
class FreshNavigationSampler(
    private val readCurrentNotifications: () -> List<NavigationNotification>,
    private val elapsedRealtime: () -> Long,
) {
    private val evaluator = NavigationSnapshotEvaluator()

    fun sample(
        source: NavigationSource,
        transmittingEnabled: Boolean,
        listenerConnected: Boolean,
        permissionGranted: Boolean,
    ): SourceObservation {
        val observedAt = elapsedRealtime()
        fun stopped(status: SourceStatus) = SourceObservation(Observation.Stop(observedAt), status)
        if (!permissionGranted) return stopped(SourceStatus.PERMISSION_MISSING)
        if (!transmittingEnabled) return stopped(SourceStatus.INACTIVE)
        if (!listenerConnected) return stopped(SourceStatus.SOURCE_UNAVAILABLE)
        return try {
            evaluator.evaluate(source, readCurrentNotifications(), observedAt)
        } catch (_: SecurityException) {
            stopped(SourceStatus.PERMISSION_MISSING)
        } catch (_: RuntimeException) {
            // A binder failure does not make the last successful read a current source observation.
            stopped(SourceStatus.SOURCE_UNAVAILABLE)
        }
    }
}
