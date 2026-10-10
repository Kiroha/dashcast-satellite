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
maneuver distance. Distance numbers accept groups of three separated by spaces, no-break spaces,
or narrow no-break spaces. Ambiguous punctuation forms such as `1,000` and `1.000` are unsupported
without an observed source locale; malformed groups never fall back to their numeric suffix.
Only explicit EN/FR/DE instructions are currently recognized; additional languages
need source fixtures. Regex/unit/digit and guidance-policy cases derive from the upstream
`NavTextParsersCharacterizationTest`, `ArabicNavParsingTest` and `NavGuidancePolicyTest`; the tests here
exercise the extracted conversion itself, source disappearance, unavailable reads and permissions.

## Maps notification images

Application 0.3.0-dev adapts `MapsManeuverImage.kt`, `MapsManeuverReferences.kt` and their bitmap
tests from local DashCast commit `3565221625ef0b3b0e039d7662abff0f5f39aca6`.
The original capture-based left/right matcher was introduced by
`a26fe1ac20b8fd8be272332e7a1f4f92e11f80f9`. Code retains the upstream MIT attribution; the independent
test images retain their source provenance and are not reclassified as original MIT artwork.

The corpus derives from Maps/Morphe `26.33.02.961351034`, APK SHA-256
`5fa52cfb6f6dfe10efb0b2961a1569d07efaa9615f2de8df1de80a8dd87e05e5`.
Upstream audited the notification SVG rendering path and independently rasterized fixtures using
CairoSVG 2.8.2. It includes an earlier non-location arrow capture. Source/PNG hashes are retained
with the test fixtures. Neither the Maps APK nor decompiled source is packaged in Satellite.
`tools/navigation/import_maps_corpus.py` imports the reviewed masks and fixtures from that pinned
Git commit, verifies source hashes, and translates labels without introducing OEM constants.

Only the selected active Maps notification's large bitmap or resource icon is inspected. Input
dimensions are bounded to 8–256 pixels and coverage/emphasis masks to 32×32. The upstream error
threshold (0.22), minimum competing-label margin (0.15), aspect tolerance (0.08), and narrow topology
halo are preserved. URI, adaptive, malformed, coloured, empty and ambiguous images are unsupported;
the listener never opens URI images, retains source bitmaps or logs their contents. Android 8.0/8.1
use text parsing only because public Icon type/resource accessors require Android 9.

Satellite emits portable v1 maneuvers instead of upstream OEM codes. Roundabout images supply
circulation only: a consistent explicit exit 1–10 must be present in that same notification.
An angle is never converted into an exit number. Missing, fractional, contradictory or out-of-range
exits remain unsupported. Merge masks remain rejecting competitors because v1 has no merge maneuver;
visually ambiguous images must not become a turn. Explicit resource, image and text directions must
agree. An unknown image may fall back to independently valid explicit text. Maneuver distance still
comes from an instruction or a distance-only field, never from route-summary text or a road number.

Recognition is bounded notification-glyph matching, not screen capture or OCR. It adds no permission,
runtime dependency on a Maps APK, vehicle API, image upload or change to protocol v1. Corpus tests
validate those reference renderings, not every Maps version or physical display on the Carlinkit.

ABRP is a separate text adapter for `com.iternio.abrpapp`, the package published on
[Google Play](https://play.google.com/store/apps/details?id=com.iternio.abrpapp).
This identifies the source, not a claim that an installed ABRP version exposes usable guidance.
Maps resource mappings are not applied to ABRP. Its support remains conditional on real notification
observations and must be verified on the Carlinkit.

Every liveness sample calls Android's current active-notification API after listener connection.
The sampler keeps no previous guidance snapshot. A failed read, grant revocation, source removal,
or unsupported state emits a stop. A successful sample stamps elapsed realtime **before** the OS
read, preserving read/parse/queue age; a transport reconnect must obtain another real sample.
Fresh inactive observations also forward stops so an earlier stop that expired in the transport
queue can be retried without changing its original observation time.
Notification text and route details are never written to support logs or storage.

Android API references:
[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
and [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager).
