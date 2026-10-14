package io.github.kiroha.dashcast.satellite.capture

/** Keep actual transport-send evidence even when the capture worker no longer owns the peer. */
internal class VideoSendCompletion(
    private val event: VideoDiagnostics.Event,
    private val sharingSession: Long,
    private val post: (() -> Unit) -> Unit,
    private val owns: () -> Boolean,
    private val sent: () -> Unit = {},
    private val failed: () -> Unit,
    private val record: (VideoDiagnostics.Event, Long) -> Unit = { value, session ->
        VideoDiagnostics.record(value, recent = value != VideoDiagnostics.Event.ICE_SENT, sharingSession = session)
    },
) {
    fun complete(success: Boolean) {
        // The worker may be delayed or already shut down; diagnostics are synchronized scalars.
        if (success) record(event, sharingSession)
        post {
            if (owns()) {
                if (success) sent() else failed()
            }
        }
    }
}
