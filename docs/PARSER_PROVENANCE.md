# Navigation parser provenance and limits

`navigation/NavTextParsers.kt` adapts the pure distance, digit normalization, duration, road-name
and roundabout-exit logic from DashCast's
`app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.kt` at commit
`48e8f30344d513967e7d065de1ef369c92a89b23`. Explicit EN/FR/DE maneuver phrases and Maps resource names
in `NavigationAdapters.kt` are a conservative subset adapted from that file. Upstream is
Copyright (c) 2026 Cedric Carre, MIT; the complete notice is retained in `PARSER_UPSTREAM_LICENSE.txt`.
The original implementation comments describe the distance/time patterns and noise rules as
following OpenBYD notification/smali behavior. That provenance is retained here; no OEM code,
CAN constants, HUD controller, platform service or proprietary binary is copied into this app.

Relevant history:

- `5e83ff69585ce244c711e019dee849cb75f5aa14`: Kotlin port of MapNotificationListenerService.
- `539d29e3d3de68c921b7eef1f4dc5c60c4f8b3e6`: nullable roundabout exit parsing fix.
- `9f0a8513be4405d725a1ef62d882ae368a118cc3`: navigation recovery and Maps diagnostics.
- `48e8f30344d513967e7d065de1ef369c92a89b23`: pinned satellite receiver contract.

The companion changes behavior intentionally: it returns portable names instead of OEM IDs,
does not assign handedness to a generic roundabout/U-turn/merge/exit, rejects conflicting
instructions, requires maneuver distance from an instruction or distance-only field, bounds numeric
values to protocol v1, converts Arabic `كم` to kilometres, and normalizes digits before duration
parsing. ETA remains unknown in this milestone. A route-summary distance cannot fill a missing
maneuver distance. Only explicit EN/FR/DE instructions are currently recognized; additional languages
need source fixtures. Regex/unit/digit and guidance-policy cases derive from the upstream
`NavTextParsersCharacterizationTest`, `ArabicNavParsingTest` and `NavGuidancePolicyTest`; the tests here
exercise the extracted conversion itself, source disappearance, unavailable reads and permissions.

Maps resource-name lookup supports resource icons only. A bitmap/URI image, a generic icon, an
unrecognized maneuver, or a roundabout without observed circulation plus exit is unsupported.
No pixel matching, OCR, screen scraping or default turn is implemented.
Android 8.0/8.1 use text parsing only because public Icon resource accessors require Android 9.

ABRP is a separate text adapter for `com.iternio.abrpapp`, the package published on
[Google Play](https://play.google.com/store/apps/details?id=com.iternio.abrpapp).
This identifies the source, not a claim that an installed ABRP version exposes usable guidance.
Maps resource mappings are not applied to ABRP. Its support remains conditional on real notification
observations and must be verified on the Carlinkit.

Every liveness sample calls Android's current active-notification API after listener connection.
The sampler keeps no previous guidance snapshot. A failed read, grant revocation, source removal,
or unsupported state emits a stop. A successful sample stamps elapsed realtime **before** the OS
read, preserving read/parse/queue age; a transport reconnect must obtain another real sample.
Notification text and route details are never written to support logs or storage.

Android API references:
[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
and [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager).
