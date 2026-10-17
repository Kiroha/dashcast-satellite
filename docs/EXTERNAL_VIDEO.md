# External application video — experimental

DashSat launches an application on the external Android box and shares its video with DashCast.
The source app, capture, rendering and hardware encoding run on the box. WebRTC transports a single
video track directly over LAN UDP with DTLS-SRTP. The existing authenticated, certificate-pinned WSS
socket carries signalling and optional manual, typed diagnostics. DashCast must decode and compose the received video before the
existing cluster projection displays it; it is not a zero-cost compressed-video relay.

## Setup while parked

1. Start the existing DashSat connection. Notification access is needed for guidance, not video.
2. In DashSat, open **Video projection · experimental**, choose an application, then **Start video sharing**.
3. Accept Android's fresh screen sharing dialog. On Android 14+, select the same app in Android's
   app-sharing chooser. The app selected for launch is not proof of which app Android shares.
   Android versions before app sharing mirror the box display. The selected app opens on the box
   after the foreground sharing session is authorized, even if no DashCast viewer is visible.
   **Open selected app** is also available manually.
4. If CarPlay and DashCast use the same car screen, leave CarPlay and return to DashCast. Open
   **Preview received map**; video starts when that viewer becomes ready. **Show on cluster** is
   also available after its existing projection is already ready. It does not start a new OEM
   projection or restore an OEM display. Check the main-display image before using the cluster route.
5. Use **Stop video sharing** or the foreground notification's Stop action while the app is open.

A visible viewer is not required to grant sharing. **Screen sharing active · waiting for DashCast**
means the foreground Android capture session remains authorized, but no video is being sent.
Stop remains available. The notification distinguishes waiting from preparing/sending video.

Capture can include personal content. Secure/DRM content may appear blank. No camera, microphone,
audio/data channel, remote input, hidden independent app display, unattended consent reuse or boot
capture is implemented. Keep interaction on the box/main display. OEM alerts and restrictions still
apply; neither connected video nor guidance establishes that the vehicle is parked.

Video, WSS control and guidance have separate state. Hiding/closing the DashCast viewer releases
its decoder and the sender's peer/encoder, suspends texture forwarding and static refresh, and leaves
this continuous, user-authorized Android capture session waiting. Reopening the viewer creates a
fresh peer/negotiation ID using the same MediaProjection and, once created, the same VirtualDisplay.
It never calls `getMediaProjection` again or creates a second VirtualDisplay from that consent.
Before the first viewer is ready, native video resources and the VirtualDisplay are deferred;
a projection callback observes revocation throughout the initial waiting period.

Temporarily covering the shared app or pausing its VirtualDisplay suspends video within the same
valid authorized session. **Screen sharing paused · open the shared app** keeps Stop available;
open the app that Android is actually sharing, then return to the DashCast viewer. Android must
report both visible content and a resumed display and provide a new platform texture before the
sender creates a fresh peer. An old queued/static texture cannot establish source freshness. There
is no five-second hidden-app deadline, no second VirtualDisplay and no reuse of a stopped grant.
An app Android considers hidden is not an independently rendered invisible application display.

Control/LAN loss or replacement of the authenticated WSS generation, lock/screen-off, capture
revocation (`MediaProjection.Callback.onStop`), VirtualDisplay stop, Stop or a fatal video failure
ends the session and frees projection/encoder/peer/VirtualDisplay. New Android consent is then required.
Receiver visibility and captured source visibility are separate: hiding the receiver does not permit
replaying hidden/revoked source content. Guidance reconnects through its existing service. Within
one valid capture, at most two new negotiation IDs per visible-viewer period may recover a viewer
error; a new `video.ready` is not required for that already-open viewer.

## Resource and network bounds

Defaults: up to **960×540, 15 fps, 1.5 Mbps**, one track. Actual dimensions preserve source aspect
ratio and never enlarge a smaller source. H.264 is preferred among the box's hardware encoders.
No software encoder fallback is enabled. Waiting has no peer, encoding, ICE, encoder watchdog or
native stats requests; the stats timer is inactive. After the first activation, retaining the same
VirtualDisplay may still incur box composition work while waiting; zero box GPU use is not promised.
Static live content refreshes twice per second while a viewer is ready, without
holding a texture buffer, so the receiver can observe decoded-frame progress beyond six seconds.
Visibility/pause/revocation callbacks and the live surface/VirtualDisplay bound this refresh.
After a source interruption, fresh source timestamps establish a new output epoch before repeats
can resume; a cached forced frame or late older callback cannot lower that timestamp floor.
An immutable ready/epoch snapshot fences old peer callbacks, stats/watchdog ticks and texture
forwarding permits even if the source becomes ready before its ordered worker pause drains. A
firmware capture stall that leaves all platform callbacks falsely active cannot be distinguished
from a static scene by this app alone; validate source-loss behavior on the real box.

Native NetworkMonitor receives only the selected authenticated LAN interface, private IPv4
addresses and network handle. Its Android native socket binder uses that handle for UDP; the
process/default network and Maps mobile data remain unchanged. There are no STUN/TURN, relay,
TCP ICE, IPv6, cellular or VPN media fallbacks. Local candidates from another interface are dropped.

Remote numeric candidates must equal the actual peer address of the authenticated TLS socket.
Canonical UUID-v4 `.local` browser host candidates are rewritten locally to that same IPv4 peer,
retaining their UDP port. SDP and trickled ICE share the same bounded policy; no mDNS/default-network
DNS lookup occurs, and SDP trust/ICE credentials remain unchanged. This is an app-specific fallback,
not a general mDNS resolver. If the browser binds that UDP port exclusively to another car interface,
negotiation times out and capture stops; no mobile-data fallback is attempted. All data is fenced by
the socket generation and a fresh negotiation ID. ICE waits for actual offer send/answer application.

Queues, candidate counts, SDP/message sizes, negotiations and pending diagnostics are bounded.
Video errors do not turn cached navigation into fresh guidance. No tokens, SDP, route text,
notifications or arbitrary native diagnostic logs are written to support logs.

## Video activity after switching displays

Returning to CarPlay hides the main-display DashCast viewer, so **waiting for DashCast** is
expected there when the shared source is still available. **Screen sharing paused · open the
shared app** instead means Android hid or paused that source; this remains a valid sharing session.
Neither status describes what happened while the viewer was previously visible. DashSat's
**Recent video activity** retains up to 32 process-local stages, with the eight newest shown
on the video page and all retained stages available through **Copy video diagnostics**.
Local session numbers distinguish separate authorized sharing attempts; the latest stage is
shown first. ICE events contribute only to saturating totals, without displacing useful stages.

The stages distinguish Android capture creation, native/hardware encoder setup, offer transmission,
answer receipt/application, media connection and actual encoded-frame progress. A sent offer means
the control transport accepted that send, not that the receiver processed it. It remains historical
even if the capture worker processes its completion after that peer has closed. Neither an accepted
offer nor encoded frames proves that DashCast displayed an image. The receiver bug report separately
records viewer readiness, received offers, queued answers, track/media connection and actual decoded
frame progress. Historical decoding also does not prove the current image is visible.

The history contains fixed stage/cause labels, elapsed ages, local attempt numbers and bounded
totals only. It is cleared when the app process exits and contains no routes, notifications,
tokens, SDP, candidate addresses, network/device identifiers, raw error messages or class names.
After returning from the receiver while parked, use **Send diagnostics to DashCast** before
starting another attempt. It sends one small, strictly typed snapshot over the existing authenticated
local connection, even while the DashCast viewer is hidden. This needs no internet, Telegram or
WhatsApp on the box. Wait for **Last transfer: diagnostics received by DashCast**, then return to
DashCast and send its normal bug report. That report includes the sender snapshot alongside receiver
history. No acknowledgement means receipt is unconfirmed; retry after checking the connection.
**Copy video diagnostics** remains available.

The send button needs a receiver advertising the optional schema 1 capability; it is disabled with
an explanation when disconnected or unsupported. It does not depend on video/capture readiness.
The sender keeps one latest pending snapshot, times out after five seconds, and never retries it on
another socket. Acknowledgement means a typed snapshot committed to receiver RAM, not successful
video. The receiver keeps one snapshot for at most 24 hours after receipt, including ordinary
disconnects. Process restart, a different/unknown authenticated peer, receiver disable or token
revocation clears it. The displayed result is the historical **last transfer**, so current
connectivity/capability is shown separately when unavailable.

The payload contains only allowlisted stage/state/cause enums, Boolean capture state, bounded
version/counter/share ordinals and elapsed ages. It never sends the clipboard summary, raw metrics,
SDP, notifications, route text, addresses, credentials or peer/negotiation IDs. At most 32 event types
and 32 newest history rows fit a 16 KiB UTF-8 limit. Ages saturate at 24 hours; counters and local
ordinals at one million are lower bounds, and capped ordinals can merge attempts. Event age at capture,
queue age and receiver receipt age remain distinct historical values.

An older receiver may deliver an unscoped `viewer_unavailable` error after a newer `video.ready`.
This error is retained as evidence without clearing readiness. Only explicit `video.ready` and
`video.closed` events change viewer availability; existing finite negotiation deadlines still
end an attempt that cannot establish media. The updated receiver serializes its unavailable
rejection with viewer attachment so a new readiness signal cannot be overtaken by that rejection.
No wire message or protocol fixture changes are required.

## Performance acceptance on the actual vehicle

The goal is lower sustained **vehicle** CPU/GPU workload than running the navigation app locally.
DashCast 1.9.46-beta uses app-local native WebRTC matching DashSat 0.9.9-dev, with hardware-only
H.264 decode and EGL texture rendering. Reports distinguish codec creation, successful hardware
initialization, decoded textures and completed renders. Use **Send diagnostics to DashCast**;
the former long-press WebView overlay is retired. A decoder name or low latency alone does not
prove that an image was rendered, and hardware decode does not establish lower total workload.
No received byte/control counter refreshes an image; explicit stop clears immediately and stalled
rendering clears after six seconds. H.265 remains unadvertised pending end-to-end validation.

Use identical route/scenario, display brightness, Overdrive settings and ambient conditions:

| Mode | Ten-minute check | Thirty-minute sustained check |
| --- | --- | --- |
| DashCast idle, same Overdrive recording | CPU/GPU, temperature, frame loss baseline | Thermal/recording baseline |
| Maps running and projected locally in the car | CPU/GPU, temperature, visible latency, frame loss | Sustained app workload |
| Maps running on Tbox; DashSat video to same cluster | Codec, efficiency hint/independent decoder evidence, decode time/FPS/drop, CPU/GPU, screen-to-cluster latency | Heat/throttling, frame loss, comparison with local Maps |

Record exact vehicle model/ROM/API/WebView version, box firmware/API, APK versions and negotiated
codec. Test static maps for over a minute, moving content, app sharing resize/rotation, viewer closure,
capture Stop, Android revoke/lock (including before the first viewer), Wi-Fi loss/recovery, and
repeated consent sessions. Test the actual shared-screen order: consent on the box with no visible
viewer, launch the chosen app, leave CarPlay, open Preview, return to CarPlay, then reopen Preview
without granting another consent while the same session remains valid. Cover the actually shared
app for more than five seconds, return to it, then reopen the viewer: the same consent/VirtualDisplay
must resume only after a new visible texture. Also test display pause/resume and source visibility
restoring in either order, hidden/visible callback bursts, Stop from the paused notification, and no
stats/encoder/watchdog work while either endpoint is unavailable. No session may resume after
Stop, revoke, lock or control loss. Stop/revoke must
release actual MediaProjection, virtual display, peer, codec, texture helper and service, with no stale
image. Cluster projection teardown must close its viewer without changing OEM restoration behavior.
Retest guidance alongside video and with satellite disabled. All vehicle video/performance results
remain pending until measured; emulator/JVM/JavaScript checks do not establish hardware acceptance.

## Android capture callback boundaries

[MediaProjection.Callback](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback)
distinguishes captured-content visibility from `onStop`, which invalidates the projection.
[VirtualDisplay.Callback](https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback)
provides separate `onPaused`, `onResumed` and terminal `onStopped` events. The sender retains one
continuously authorized projection/display through temporary pauses and releases it on terminal
loss, following the [MediaProjection session rules](https://developer.android.com/media/grow/media-projection).
The foreground notification remains visible and stoppable during a pause. JVM/state-machine checks
verify these application boundaries; actual Android 15/Tbox callbacks and resumed video remain to
be confirmed on the hardware. This lifecycle correction does not identify the cause of H264 RTP
arriving without a decoded first frame in the earlier short vehicle attempt.


### First-frame diagnostics

The manual **Send diagnostics to DashCast** action retains the latest original stats sample
from up to four local sender attempts, including attempts already closed when switching screens.
With a receiver advertising both diagnostics capabilities, the existing snapshot includes nullable
encoded/keyframe counts, bytes/packets and received PLI/NACK/FIR feedback. Older receivers receive
the unchanged legacy snapshot. Missing fields remain unknown; historical encoding never confirms
a decoded or displayed image. No extra polling, socket, codec fallback or capture session is added.


### Bounded H.264 startup recovery

DashSat can ask its existing hardware encoder for up to three extra startup keyframes near 2, 5 and
9 seconds after the first connected event. The finite 12-second window, minimum spacing and
per-attempt/encoder ownership prevent continuous sync requests or catchup bursts. Empty/drop hints
and native keyframe requests keep their original arguments. No new timer, stats request, codec AAR,
profile, permission, consent or wire message is required. Other codecs retain their ordinary path.

The per-viewer native factory/source/track are released after synchronous texture-thread unbinding.
The same Android-authorized MediaProjection/VirtualDisplay/EGL helper supplies replacement sources.
Source visibility and viewer epochs fence old callbacks immediately, including false-true transitions
before worker cleanup drains. Keyframe requests never refresh encoded-frame or receiver-image liveness.

Use **Send diagnostics to DashCast** after an attempt and report the actual `keyFramesEncoded`
plus receiver decoded-frame observations. The hardware may decline a request; successful encoding or
extra keyframes do not establish decoder compatibility, a displayed image or CPU/GPU savings.


### Actual encoded H264 header evidence

With DashCast 1.9.41-beta or later advertising exact `videoDiagnosticsH264:1` alongside the two
older capabilities, the manual transfer uses details schema 2. The first hardware H264 output and
up to three later declared key outputs carry scalar header observations, each with its own original
age independently of RTC statistics. A strict per-attempt limit covers callbacks (180), elapsed
window (12 seconds), total inspected bytes (256 KiB) and bytes per selected output (96 KiB).
There is no new timer or steady inspection once the budget is finished.

SPS/PPS/IDR presence is unknown for unsupported framing or incomplete evidence. Only the first
recognized SPS's three fixed header bytes are reported; this is not a validated SPS or an
association with an IDR. The width/height fields are SDK EncodedImage metadata, not parsed SPS
dimensions. Inspection never retains, copies or modifies a media buffer or exports payload bytes.
Native callback delivery, codec preferences and finite startup keyframe recovery are preserved.

For a parked-device test, update both apps, start sharing and keep the DashCast viewer visible for
at least 20 seconds. Return to DashSat, select **Send diagnostics to DashCast**, wait for the
received confirmation, then create the DashCast bug report. Existing pairing remains valid.
An updated diagnostic report does not itself confirm a decoded image or reduced CPU/GPU load.

## Explicit hardware H264 profile configuration (0.9.7-dev)

The Tbox field report showed actual High-profile SPS bytes (`64001f`) while the receiver negotiated
Constrained Baseline level 3.1 (`42e01f`). The extra startup keyframes did not correct that mismatch.
The SHA-pinned WebRTC SDK's Java Baseline path leaves the MediaCodec profile and level unspecified;
disabling High-profile advertisement in its factory does not force the hardware's output profile.

A narrow Kotlin adapter in `org.webrtc` now reuses the unmodified SDK encoder with an explicit
MediaCodec profile and level 3.1. The SDK still chooses the hardware codec using its existing model
exceptions and support policy. Its uninitialized candidate supplies the implementation name, then
is released exactly once before creating the configured encoder. No MediaCodec, EGL input surface,
output thread or shader is initialized for this probe. The actual encoder retains the original EGL
context, color formats, CBR settings, bitrate adjustment and keyframe behavior. Other codecs retain
their existing path. The native AAR is unchanged; there is no reflection, software encoder fallback,
SPS modification or SDP rewriting.

Declared Constrained Baseline capability is preferred on API 27 and later. Otherwise declared
Baseline capability is requested explicitly and the actual output must still pass the Constrained
Baseline check. A lower negotiated level is refused rather than silently encoding level 3.1, even
when level asymmetry is enabled. The wrapper applies the profile and level on every configure,
including SDK-internal resolution or surface-mode restarts. A configuration refusal releases the
new codec before returning the SDK's ordinary failure status; it does not retry another profile.

A separate lifetime guard checks the fixed SPS profile/constraint/level bytes in a bounded Annex B
prefix (at most 4 KiB and 32 NAL headers, stopping before picture payload). It requires an observable
compatible SPS before delivery and rejects known incompatible SPS before the native sender receives
the encoded image. The guard is independent of the finite startup diagnostics and keyframe budgets.
It does not retain, copy, modify or release media buffers, validate complete SPS syntax or establish
that a receiver image was decoded. Each initialization has its own callback generation; a stale
callback cannot authorize a new initialization or report a failure for it.

One immutable-attempt failure is latched and posted to the service worker. The worker rechecks
source, viewer, control connection and current-attempt ownership before ending sharing. Codec
callbacks never drain the texture helper or close native resources. The app displays an explicit
compatible-H264 failure; existing guidance and control remain independent. Existing manual
diagnostics retain their original first-output/next-three-keyframes/12-second contract: a late
rejection stops sharing but its exact SPS values may be outside that diagnostic window.

Keep DashCast 1.9.41-beta or later, install DashSat 0.9.7-dev without re-pairing, and start a new
authorized share while parked. Keep the viewer visible for 20 seconds. If it stays black or sharing
stops, send diagnostics to DashCast, wait for receipt and create its bug report. Hardware acceptance
requires actual compatible SPS output, a decoded image and separate latency/CPU/GPU measurements.

## Explicit synthetic reference test

DashSat 0.9.8-dev adds a bounded, consented comparison stream for unresolved first-image failures.
Select an app and use **Test video compatibility** while parked, then follow Android capture consent
and switch back to the DashCast viewer. The selected app must remain open on the Tbox to produce
fresh platform frames, but its pixels are never sent in this mode. Stop is available in the capture
notification. Reference mode bypasses hardware encoder preflight; normal sharing still requires it.
The test does not automatically become normal sharing, which requires a new Android consent.

The two packaged fixtures are generated by `tools/generate_reference_video.py` using PyAV 16.1.0
and libx264 with one thread. Each is independently decoded before being written, contains exactly
SPS/PPS/SEI/one IDR, is 960×452 Constrained Baseline level 3.1, and has an IDR larger than 1200 bytes
to exercise fragmented RTP. It contains only generated colors and large TEST PATTERN lettering.
The app verifies fixture SHA-256 and size on the worker before allocating either of two immutable
direct buffers. Native reference counts own payload leases; a full pool drops an output.

The capture session owns the 60-second consent deadline and the 15-second deadline starting with
the first reference negotiation. Peers, source epochs and viewer resumes cannot renew them. At
most 75 reference outputs are allowed, spaced by at least 200 ms with no catch-up. Original capture
timestamps are preserved and consumed once; reference mode disables cached `forceFrame` output.
Final source/WSS/viewer ownership and session deadline are checked before handing an encoded image
to native WebRTC. No captured pixel conversion or encoding takes place in the reference factory.

`videoReferenceTest:1`, together with all three older diagnostics capabilities, gates the UI,
reference offer and schema-3 details. Both histories distinguish reference from capture; older
sender detail formats omit reference attempts. The test is inconclusive if no frames were sent.
Seeing the reference establishes support for that known stream, not all hardware-encoder outputs.
No decoded reference image, real-device latency or CPU/GPU saving is claimed from JVM/emulator tests.
