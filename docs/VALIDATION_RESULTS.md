# Baseline verification — October 9, 2026

Application 0.1.0-dev, protocol v1 pinned to receiver commit
`48e8f30344d513967e7d065de1ef369c92a89b23`.

Commands passed locally:

```sh
python3 tools/verify_protocol.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug
```

- 48 tests, no failures, errors, or skipped tests.
- Debug and release lint: no issues. The wrapper upgrade suggestion is disabled because build
  versions are pinned; the certificate pinning trust manager has a documented local suppression.
- Real TLS tests: the correct DER certificate is accepted; an incorrect certificate is rejected
  before authentication.
- Real hello/welcome exchange using Java-WebSocket over TLS 1.2, with a single TLS handshake
  before the token is sent.
- Receiver fixtures and hashes verified; sequence numbers, source age, bounded queue, and
  reconnect backoff covered.
- Listener tested with Robolectric on API 35 (Android 15) and API 26: observation, notification
  removal, access revocation, failed reads, and authorized rebind requests.
- Lifecycle tests: stale session callbacks ignored, missing profile handling, and opt-in boot recovery.
- Release packaging without its four signing variables: expected rejection confirmed.
- APK signature verified: standard Android Debug certificate; no TLS fixtures or keys packaged.
- `graphify update .` completed and the Kotlin graph is current. The extractor reports partial
  parsing of the Groovy DSL in `app/build.gradle`; Gradle builds and validates that file successfully.

The initial French debug APK was 1,077,689 bytes, with SHA-256
`858798546f7e4e5c6506fe624aa6652fce9cdc1386b47c2ffde5ea936dfbe783`.
This checksum is historical and does not identify subsequent English builds.

## English app and documentation verification — October 9, 2026

The commands above passed again after translating the default UI, notification text, README,
and validation documents into English: 48 tests passed, with no debug or release lint issues.
All 49 string resource identifiers and formatting placeholders were preserved. Multilingual
navigation recognition patterns and source-language test fixtures remain unchanged.

English debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 1,051,292 bytes.
SHA-256: `343b058952b096a2685714fdc59f672afc2feebb294a67b0e4e3d2ccab00e0ea`.

## Hardware and publication status

At the time of the initial local verification, the GitHub workflows had not run remotely.
The user subsequently created [Kiroha/dashcast-satellite](https://github.com/Kiroha/dashcast-satellite)
and it was configured as the project origin. The English debug APK was subsequently published as
the `v0.1.0-dev` GitHub prerelease. No production signing secrets have been provisioned.
Physical testing on the Carlinkit Tbox Ultra 1 running Android 15 and in the vehicle is still pending
under the [device validation checklist](CARLINKIT_VALIDATION.md), including the real AndroidKeyStore,
hotspot/SIM topology, overnight and ignition recovery, and HUD/cluster rendering.
Video is not implemented in this baseline.

## Protocol v1 review — October 9, 2026

The receiver's `docs/satellite/PROTOCOL_V1.md` and `navigation-fixtures.json` remain byte-for-byte
identical to the copies under `protocol/v1`. The receiver's satellite implementation has not
changed since the pinned commit. The historical contract header saying that no companion APK
exists is now outdated; it is retained unchanged to preserve the reviewed contract and hashes.

The implemented guidance path matches v1: DER certificate pinning before authentication,
Keystore-backed token storage excluded from backup, a LAN-bound WSS connection, hello/welcome,
control heartbeats, portable maneuvers, shared update/stop sequence numbers, and source age
checked at dispatch. Genuine active notifications are read every second. Video capture and
WebRTC signalling are still outside this milestone.

The review identified three implementation defects addressed in application 0.1.1-dev:

- Stopping during socket creation could leave a late socket and WebSocket writer alive.
  A cancellable factory now owns the raw and TLS sockets throughout creation and handoff.
- Grouped source distances could be interpreted as a fractional or trailing number, including
  false zero-distance guidance. Grouped spaces are parsed as a whole number; ambiguous punctuation
  is rejected instead of guessing the source locale.
- A source-stop observation older than 1500 ms was discarded with no retry while source status
  stayed unchanged. Fresh inactive observations now retry the stop with their actual observation
  time, without making cached guidance fresh.

Receiver checks passed: 24 satellite JVM tests (protocol, server and video hub) and 10 JavaScript
viewer tests. These are automated contract and lifecycle checks, not a physical end-to-end test.

Companion checks passed: 56 tests, no failures/errors/skips; debug and release lint report no
issues; debug assembly succeeded. The new regressions cover real Java-WebSocket cancellation
during TLS socket creation, late socket ownership, grouped/ambiguous distances, and fresh source
reads retrying an expired stop. Protocol hashes remain unchanged and the code graph was updated.

Application 0.1.1-dev (version code 2) uses the same Android Debug signing certificate as 0.1.0-dev,
so the test APK can update the earlier debug installation. APK SHA-256:
`4061548c38db27de05d40131b5dfae64fa73731af5493de237f697667ed5590f`.
This remains a guidance-only test build; the physical validation checklist is still pending.

## Six-digit code pairing — October 9, 2026

Application 0.2.0-dev (version code 3) adds pairing without transferring a file. Display a six-digit
code in DashCast, switch to the Tbox display, and enter it in **Pair with DashCast**. The car keeps
the pairing window available for two minutes while its settings screen is in the background.
Switching back to check the code preserves unfinished input only in the existing companion screen;
it is excluded from saved state, autofill, screenshots and keyboard learning.

The local exchange uses J-PAKE with mutual key confirmation before transferring the encrypted
profile. Normal guidance still uses the existing pinned WSS connection and protected token store.

- Companion: 79 unit tests passed, including wrong-code rejection, replay rejection, bounded
  framing, cancellation, gateway selection without Internet and pairing-screen lifecycle.
- Receiver: 919 unit tests passed, release lint and debug/release assembly succeeded. Both
  companion lint variants report no issues. The receiver release APK passed its release asset
  scanner, including signature, package/version and non-debuggable checks.
- Android SDK tools verified the companion APK version and its existing Android Debug signature.
  APK SHA-256: `4f413e49d5c9c25df44635a46d1e3aba0002c3a528ff13c155f66fbabb312082`.

Physical validation remains pending on the Carlinkit Tbox Ultra 1 running Android 15, connected to
the vehicle's TetherFuseNet hotspot. These automated checks do not establish successful pairing,
SIM routing, guidance rendering or recovery on that hardware. Video remains unimplemented.

## Maps notification image mapping — October 10, 2026

Application 0.3.0-dev (version code 4) ports the audited Maps image recognizer and reference corpus
from local DashCast commit `3565221625ef0b3b0e039d7662abff0f5f39aca6`. Satellite translates accepted
results to portable guidance-v1 names; the receiver's output/OEM implementation stays in DashCast.
Guidance and optional pairing contracts, fixtures and upstream pins remain unchanged.

Commands passed locally:

```sh
python3 tools/verify_protocol.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug
graphify update .
```

- 110 tests passed, no failures, errors or skipped tests. Both lint reports contain zero issues.
- All 33 non-merge corpus glyphs produce their expected portable maneuvers at 54 pixels and
  resampled sizes 48, 64, 72, 96 and 108 pixels. Five merge glyphs remain unsupported at every
  tested size. All 586 recognition masks are retained, including 84 rejecting merge references.
- Corpus and original field-capture checksums are verified. The import tool reproduces all 44
  generated files byte-for-byte from the pinned Git commits without a Maps APK.
- Native bitmap/listener tests cover image-only direction changes, fresh observation timestamps,
  selected-notification priority, unknown replacement images, removal, revocation, listener loss,
  ABRP isolation and Android 8 text-only fallback.
- Parser/wire tests cover conflicting evidence, missing maneuver distance, roundabout circulation
  plus explicit exit, and rejection of absent, fractional, grouped, out-of-range or contradictory
  exit numbers. No image angle supplies an ordinal exit.
- APK metadata and signature verified: `io.github.kiroha.dashcast.satellite.debug`, 0.3.0-dev (4),
  same independent Android Debug certificate as 0.2.0-dev. Test images, TLS fixtures and source
  files are absent from the APK. No new runtime permission or dependency was introduced.

APK: 3,761,485 bytes; SHA-256:
`70d9851e3cf1d271c02e2972a5b83ebbb7de1c5386e1525af0be5d223c82ad46`.

Physical validation of the actual Maps version on the Tbox and the vehicle's HUD/cluster is still
pending. Corpus recognition does not prove coverage of every Maps rendering. Under unchanged v1,
merge glyphs and roundabouts without an explicit exit remain unsupported. ABRP remains text-only;
video capture is not implemented.
