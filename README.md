# DashSat

Repository: [Kiroha/dashcast-satellite](https://github.com/Kiroha/dashcast-satellite).

An independent Android app that observes navigation guidance on the box and sends it to the
DashCast receiver over the local network. First device to validate: **Carlinkit Tbox Ultra 1, Android 15**
(user-tested guidance on October 11, 2026).

The user confirmed synchronized guidance from official Google Maps on this box to DashCast on
DiLink 3 / Android 10, with no perceptible delay in that test. Stop/reconnect/reboot acceptance and
measured latency remain pending. On Android 9 or newer, Maps notification arrows can be recognized from a bounded,
audited reference set; unknown or ambiguous images remain unsupported. ABRP still requires explicit
text guidance. The experimental second milestone adds user-consented video capture; vehicle video is not yet validated.

## Getting started

1. Install the debug APK on the box. It uses the application ID
   `io.github.kiroha.dashcast.satellite.debug` and a standard Android signing key.
2. Connect the box and DashCast to the same local network. Check that the private addresses shown
   in the DashCast profile are reachable from the box. The satellite socket uses a Wi-Fi/Ethernet
   network; the process and other apps keep their default network.
3. In a DashCast version supporting code pairing, enable the satellite receiver and select
   **Pair a device**. Switch to the Tbox interface, select **Pair with DashCast** and enter
   the displayed temporary six-digit code. Satellite tries the Wi-Fi IPv4 gateway
   and saves the profile automatically. If needed, select **Advanced: enter the car IP address** and use
   a local IPv4 address displayed by DashCast. The pairing window lasts two minutes; no file or camera is needed.
   DashCast 1.9.14-beta keeps preparation failures visible with **Try again**. Hiding a pending
   or ready window preserves its deadline; an explicit retry starts a fresh one.
   **Advanced: import a pairing file** remains available for older receivers. Internal storage
   is encrypted and excluded from backups; remove transferred profile files after importing them.
4. Explicitly grant notification access in Android and select **Google Maps** or **ABRP**.
   Reading notifications and displaying notifications require separate permissions. If Android
   says **App was denied access**, use **Open App info for restricted settings** → **⋮** →
   **Allow restricted settings**, then return and enable notification access. This is the
   [Android procedure](https://support.google.com/android/answer/12623953?hl=en); the app cannot
   grant access itself, and the box firmware may restrict the available settings.
5. In DashCast, select **Use satellite guidance** and the desired HUD/cluster outputs.
   In DashSat, tap **Start**, then start actual navigation in the selected source app.
6. Check the **Connection** and **Source** states, then the physical display. An established
   connection does not prove that a usable maneuver is available. The receiver guidance status
   reflects the last handshake or rejection; reconnect after changing this setting to confirm it.
7. Stop navigation, revoke permission, and disconnect/reconnect Wi-Fi: previous guidance must
   disappear, then new valid guidance must resume.

Resuming after reboot is an explicit choice and applies only to guidance. Android or the box's
firmware may restrict services; the Start button allows manual recovery.
A hotspot hosted by the box may not expose a usable LAN `Network` to apps:
validate the actual topology in the [Carlinkit checklist](docs/CARLINKIT_VALIDATION.md).

## Identifying the connection

The saved profile shows the **DashCast ID** and saved IPv4 car addresses. Compare that ID with the
Satellite page in the vehicle. Saving a profile does not establish a live connection.
**This DashSat** shows this installation's device label and random ID; DashCast displays it
after authentication, or explicitly shows its last connection while offline. Older senders
appear as an unknown device. The display name/ID is sender-reported metadata, not an additional
authentication factor or a per-device revocation list.

DashSat shows the address currently tried and a fixed explanation of the last connection
failure. The selected LAN's current IPv4 gateway and local-subnet addresses are tried before
other saved private IPv4 addresses, with backoff after a bounded candidate cycle. This also
allows recovery after a gateway address changes, using the original certificate pin and token.
Old v1 profiles containing IPv6 remain readable; IPv6 is not attempted. If no local IPv4
candidate is available, the app displays a fixed explanation. Cellular/VPN sockets and
process routing are excluded from this path. Install **DashCast 1.9.14-beta and Satellite 0.5.0-dev**
for this recovery and visible pairing failures; existing pairing profiles remain usable.

## Build and checks

JDK 21 for Gradle, Java/Kotlin 17 compilation, Android SDK 36, minimum API 26, target API 36.
Wrapper, AGP, and dependency versions are pinned. Set `ANDROID_HOME` or create a private
`local.properties` containing `sdk.dir=…`, then run:

```sh
python3 tools/verify_protocol.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
CI runs the same checks, including the receiver's protocol fixtures.
The initial local verification results are in [VALIDATION_RESULTS.md](docs/VALIDATION_RESULTS.md).

Releases are independent of DashCast. The manual `Signed satellite build` workflow uses the
`satellite-release` environment and secrets dedicated to this app:
`SATELLITE_KEYSTORE_BASE64`, `SATELLITE_STORE_PASSWORD`, `SATELLITE_KEY_ALIAS`, `SATELLITE_KEY_PASSWORD`.
Never use the vehicle's platform key. For local builds, replace the base64 secret with
`SATELLITE_KEYSTORE`, the absolute path to this dedicated keystore, then run `:app:assembleRelease`.
Without all four settings, release packaging fails explicitly. No release secrets have been
provisioned. Debug-signed test APKs are available as GitHub prereleases; these are separate from
the production signing pipeline.

## Structure and contract

One `app` module, with the `pairing`, `transport`, `navigation`, and `capture` packages:

- `pairing`: strict profile validation, SHA-256 fingerprint of the DER certificate, AES-GCM, and AndroidKeyStore.
- `transport`: a single WSS socket bound to the local network, authentication before sending, sequences,
  a queue containing only the latest observation, monotonic age tracking, and delayed reconnection.
- `navigation`: an Android listener and separate Maps/ABRP adapters, explicit source selection,
  and a stop when the source is lost or guidance is unusable. Active system notifications are
  observed again every second; no timer makes old cached content appear fresh.
- `capture`: a consent-driven MediaProjection foreground service and pinned native hardware WebRTC
  sender; one LAN video track, continuous authorized waiting across viewer visibility changes,
  no automatic restart after capture ends or remote input.

The [v1 contract](protocol/v1/PROTOCOL_V1.md) and its fixtures are copied unchanged from
`Kiroha/byd-dashcast` at commit `2ec7d544044838ca5de365adf954d695779dd172`.
Hashes are in [upstream.json](protocol/v1/upstream.json). Any change must pass both apps'
compatibility tests. The app version evolves independently.
The optional [local pairing transfer](protocol/pairing/v1/PAIRING_V1.md) has separate fixtures and
a separate receiver pin. It transfers the existing profile using a temporary code and authenticated
encryption; the normal WSS certificate pin remains mandatory.
Parser provenance is documented in [PARSER_PROVENANCE.md](docs/PARSER_PROVENANCE.md).

Maps image recognition is adapted separately from receiver commit
`3565221625ef0b3b0e039d7662abff0f5f39aca6`. It emits portable v1 maneuver names, never vehicle icon
IDs. The image can identify roundabout circulation, but the same notification must explicitly
provide an exit from 1 to 10. Merge glyphs and ambiguous matches remain unsupported under v1.
Only the existing portable guidance fields are sent; notification images stay on the box.
Android 8 uses text parsing only.

The satellite contains no OEM output, CAN dependency, ADB, vehicle API, or platform key.
Support logs never receive tokens, SDP, notifications, or route text.

## Second milestone

User-consented MediaProjection, app launch on the external box and a native WebRTC sender are
available experimentally. DashCast 1.9.46-beta uses the same pinned native WebRTC SDK for hardware H.264 reception;
capture/encoding remain on the companion. No system WebView replacement is required. Start at 960×540 / 15 fps / 1.5 Mbps and measure car decoding/load alongside
Overdrive before claiming a CPU/GPU reduction. See [setup and performance gates](docs/EXTERNAL_VIDEO.md)
and [native dependency pin/ABI impact](docs/WEBRTC_DEPENDENCY.md).
Capture does not create an independent invisible display or authorize input injection.
Sharing can start before the DashCast viewer opens, allowing CarPlay and DashCast to use the same
car screen. A hidden viewer pauses video within the ongoing authorized sharing session; returning to
DashCast resumes with a fresh peer. Video always requires fresh consent after its capture session stops.

Android references used: [connectedDevice services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device),
[listener lifecycle](https://developer.android.com/reference/android/service/notification/NotificationListenerService),
[socket bound to a Network](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).

## Manual updates

From version 0.6.0-dev, open **Updates** to check GitHub, download a verified compatible
APK and confirm installation in Android. Install this first OTA-capable version manually
without uninstalling. See [update behavior and trust](docs/UPDATES.md).
