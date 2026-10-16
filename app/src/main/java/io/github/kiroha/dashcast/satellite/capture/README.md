Video is an experimental second milestone. Capture runs only on the companion through
user-consented MediaProjection in a dedicated foreground service. The selected application
launches on the box's normal display. Android 14+ controls single-app versus whole-display sharing;
the launcher selection cannot verify which app the user actually shares. Earlier Android versions
share the display. There is no hidden independent display, remote input or automatic capture restart.

A pinned native WebRTC sender uses one hardware-encoded video track, EGL textures, empty ICE servers
and UDP host candidates on the authenticated WSS LAN. H.264 is preferred; other hardware codecs are
negotiated if available. Hardware encoder absence causes a controlled failure. Defaults are bounded
by 960×540, 15 fps and 1.5 Mbps. A static live capture refreshes its SurfaceTexture twice per second;
temporary content hiding/display pause and viewer closure suspend transmission while retaining
one valid projection/VirtualDisplay. Resume requires the shared app to be visible, its display resumed,
a new source texture and a ready viewer. No idle stats/encoder work or cached hidden-image replay
is allowed. Revocation, display stop, lock, Stop and control loss terminate the lease; new consent
is then required. Guidance continues independently.

See docs/EXTERNAL_VIDEO.md and docs/WEBRTC_DEPENDENCY.md for setup, measurements and limitations.
