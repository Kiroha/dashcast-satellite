# External application video — experimental

DashSat launches an application on the external Android box and shares its video with DashCast.
The source app, capture, rendering and hardware encoding run on the box. WebRTC transports a single
video track directly over LAN UDP with DTLS-SRTP. The existing authenticated, certificate-pinned WSS
socket carries signalling only. DashCast must decode and compose the received video before the
existing cluster projection displays it; it is not a zero-cost compressed-video relay.

## Setup while parked

1. Start the existing DashSat connection. Notification access is needed for guidance, not video.
2. In DashCast, open **Preview received map**, or **Show on cluster** after its existing cluster
   projection is already ready. This does not start a new OEM projection or restore an OEM display.
3. In DashSat, open **Video projection · experimental**, choose an application, then **Start video sharing**.
4. Accept Android's fresh screen sharing dialog. On Android 14+, select the same app in Android's
   app-sharing chooser. The app selected for launch is not proof of which app Android shares.
   Android versions before app sharing mirror the box display. The selected app opens on the box
   after foreground capture has started. **Open selected app** is also available manually.
5. Check the received image on the main display before using the existing cluster route.
6. Use **Stop video sharing** or the foreground notification's Stop action while the app is open.

Capture can include personal content. Secure/DRM content may appear blank. No camera, microphone,
audio/data channel, remote input, hidden independent app display, unattended consent reuse or boot
capture is implemented. Keep interaction on the box/main display. OEM alerts and restrictions still
apply; neither connected video nor guidance establishes that the vehicle is parked.

Video, WSS control and guidance have separate state. Closing the viewer, loss of control/LAN,
capture revocation/pause, or persistently hidden shared content terminates capture and frees the
encoder/peer/VirtualDisplay. Guidance reconnects through its existing service. Video requires a new
user-consented session. Within one valid capture, at most two new negotiation IDs may recover a
viewer error; a new `video.ready` is not required for that already-open viewer.

## Resource and network bounds

Defaults: up to **960×540, 15 fps, 1.5 Mbps**, one track. Actual dimensions preserve source aspect
ratio and never enlarge a smaller source. H.264 is preferred among the box's hardware encoders.
No software encoder fallback is enabled. Static live content refreshes twice per second without
holding a texture buffer, so the receiver can observe decoded-frame progress beyond six seconds.
Visibility/pause/revocation callbacks and the live surface/VirtualDisplay bound this refresh. A
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

## Performance acceptance on the actual vehicle

The goal is lower sustained **vehicle** CPU/GPU workload than running the navigation app locally.
Hardware encoding on the box does not prove hardware decoding in the vehicle WebView. The receiver
has an opt-in overlay: long-press the main-display preview to toggle it, then open the cluster viewer.
It reports codec, dimensions, decoded FPS, bitrate, dropped frames and average decode time when
available. Hardware decoding remains **unknown** unless the WebView provides explicit platform
`powerEfficientDecoder` evidence; codec choice/low decode time alone is not evidence. Counters are
sanitized; overlay text contains no route or connection identifiers. Missing decoder counters do not
use received bytes as a freshness substitute, and a frozen map clears after six seconds.

Use identical route/scenario, display brightness, Overdrive settings and ambient conditions:

| Mode | Ten-minute check | Thirty-minute sustained check |
| --- | --- | --- |
| DashCast idle, same Overdrive recording | CPU/GPU, temperature, frame loss baseline | Thermal/recording baseline |
| Maps running and projected locally in the car | CPU/GPU, temperature, visible latency, frame loss | Sustained app workload |
| Maps running on Tbox; DashSat video to same cluster | Codec/HW evidence, decode time/FPS/drop, CPU/GPU, screen-to-cluster latency | Heat/throttling, frame loss, comparison with local Maps |

Record exact vehicle model/ROM/API/WebView version, box firmware/API, APK versions and negotiated
codec. Test static maps for over a minute, moving content, app sharing resize/rotation, viewer closure,
capture Stop, Android revoke/lock, Wi-Fi loss/recovery, and repeated consent sessions. Stop/revoke must
release actual MediaProjection, virtual display, peer, codec, texture helper and service, with no stale
image. Cluster projection teardown must close its viewer without changing OEM restoration behavior.
Retest guidance alongside video and with satellite disabled. All vehicle video/performance results
remain pending until measured; emulator/JVM/JavaScript checks do not establish hardware acceptance.
