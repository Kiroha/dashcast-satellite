package io.github.kiroha.dashcast.satellite.navigation

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** Process-local observation boundary. User preferences belong to the foreground owner. */
object NavigationObservationBus {
    data class Configuration(val source: NavigationSource, val transmittingEnabled: Boolean)

    private val observers = CopyOnWriteArrayList<(Observation, SourceStatus) -> Unit>()
    private val configurationObservers = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var configuration = Configuration(NavigationSource.MAPS, false)
        private set
    @Volatile var status: SourceStatus = SourceStatus.PERMISSION_MISSING
        private set
    @Volatile var listenerConnected: Boolean = false
        internal set

    // Keep status only. A new subscriber must obtain a fresh OS snapshot, never a cached route.
    fun subscribe(observer: (Observation, SourceStatus) -> Unit): Closeable {
        observers.add(observer)
        observer(Observation.Stop(SystemClock.elapsedRealtime()), status)
        return Closeable { observers.remove(observer) }
    }

    fun configure(source: NavigationSource, transmittingEnabled: Boolean) {
        val next = Configuration(source, transmittingEnabled)
        val previous = configuration
        configuration = next
        if (previous.source != source || !transmittingEnabled || !previous.transmittingEnabled) {
            publish(SourceObservation(Observation.Stop(SystemClock.elapsedRealtime()),
                if (status == SourceStatus.PERMISSION_MISSING) SourceStatus.PERMISSION_MISSING else SourceStatus.INACTIVE),
                force = true)
        }
        // Even unchanged configuration requests a genuine resnapshot after socket replacement.
        configurationObservers.forEach { it() }
    }

    internal fun observeConfiguration(observer: () -> Unit): Closeable {
        configurationObservers.add(observer)
        return Closeable { configurationObservers.remove(observer) }
    }

    internal fun publish(value: SourceObservation, force: Boolean = false) {
        val previous = status
        status = value.status
        if (!force && value.observation is Observation.Stop && previous == value.status) return
        observers.forEach { observer -> observer(value.observation, value.status) }
    }
}
