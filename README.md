# DashCast Satellite

Repository: [Kiroha/dashcast-satellite](https://github.com/Kiroha/dashcast-satellite).

An independent Android app that observes navigation guidance on the box and sends it to the
DashCast receiver over the local network. First device to validate: **Carlinkit Tbox Ultra 1, Android 15**
(reported by the user; no physical testing performed in this session).

This repository contains the foundation for the first milestone. Vehicle compatibility has not
yet been validated. On Android 9 or newer, Maps notification arrows can be recognized from a bounded,
audited reference set; unknown or ambiguous images remain unsupported. ABRP still requires explicit
text guidance. The second milestone will add video capture.

## Getting started

1. Install the debug APK on the box. It uses the application ID
   `io.github.kiroha.dashcast.satellite.debug` and a standard Android signing key.
2. Connect the box and DashCast to the same local network. Check that the private addresses shown
   in the DashCast profile are reachable from the box. The satellite socket uses a Wi-Fi/Ethernet
   network; the process and other apps keep their default network.
3. In a DashCast version supporting code pairing, enable the satellite receiver and select
   **Pair a device**. Switch to the Tbox interface, select **Pair with DashCast** and enter
   the displayed temporary six-digit code. Satellite tries the Wi-Fi gateway
   and saves the profile automatically. If needed, select **Advanced: enter the car IP address** and use
   an address displayed by DashCast. The pairing window lasts two minutes; no file or camera is needed.
   **Advanced: import a pairing file** remains available for older receivers. Internal storage
   is encrypted and excluded from backups; remove transferred profile files after importing them.
4. Explicitly grant notification access in Android and select **Google Maps** or **ABRP**.
   Reading notifications and displaying notifications require separate permissions. If Android
   says **App was denied access**, use **Open App info for restricted settings** → **⋮** →
   **Allow restricted settings**, then return and enable notification access. This is the
   [Android procedure](https://support.google.com/android/answer/12623953?hl=en); the app cannot
   grant access itself, and the box firmware may restrict the available settings.
5. In DashCast, select **Use satellite guidance** and the desired HUD/cluster outputs.
   In Satellite, tap **Start**, then start actual navigation in the selected source app.
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

The saved profile shows the **DashCast ID** and car addresses. Compare that ID with the
Satellite page in the vehicle. Saving a profile does not establish a live connection.
**This Satellite** shows this installation's device label and random ID; DashCast displays it
after authentication, or explicitly shows its last connection while offline. Older senders
appear as an unknown device. The display name/ID is sender-reported metadata, not an additional
authentication factor or a per-device revocation list.

Satellite shows the address currently tried and a fixed explanation of the last connection
failure. Hotspot gateway and local-subnet addresses are tried before other exported private
addresses, with backoff after the available candidates have been tried. Certificate pinning and
LAN-bound sockets remain mandatory. Install **DashCast 1.9.13-beta and Satellite 0.4.0-dev** to
fix the WebSocket upgrade rejection in both applications; existing profiles remain usable.

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
- `capture`: reserved for the second milestone; no capture code is enabled.

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

After standalone guidance is validated: MediaProjection with user consent, a dedicated foreground
service, and a maintained, versioned native WebRTC sender. One video track, local ICE, WSS v1
signaling, modest resolution/frame rate, and measurements of latency, load, and Overdrive frame drops.
Test the installed receiver WebView before considering a native receiver and its APK/ABI cost.
Capture does not create an independent invisible second display or allow input injection.
Video restart without consent is not promised.

Android references used: [connectedDevice services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device),
[listener lifecycle](https://developer.android.com/reference/android/service/notification/NotificationListenerService),
[socket bound to a Network](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).
