package io.github.kiroha.dashcast.satellite.pairing

/** Serializes profile replacement and forgetting across recreated or overlapping activities. */
internal object PairingOperation {
    class Ticket internal constructor()

    private val lock = Any()
    private var active: Ticket? = null
    private var currentRevision = 0L

    val busy: Boolean get() = synchronized(lock) { active != null }
    val revision: Long get() = synchronized(lock) { currentRevision }

    fun begin(): Ticket? = synchronized(lock) {
        if (active != null) null else Ticket().also { active = it }
    }

    /** The activity must still own this attempt when storage is changed. */
    fun complete(ticket: Ticket, save: () -> Unit): Boolean = synchronized(lock) {
        if (active !== ticket) return false
        try {
            save()
            true
        } finally {
            active = null
            currentRevision++
        }
    }

    fun cancel(ticket: Ticket) = synchronized(lock) {
        if (active === ticket) active = null
    }

    fun forget(clear: () -> Unit) = synchronized(lock) {
        active = null
        try { clear() } finally { currentRevision++ }
    }
}
