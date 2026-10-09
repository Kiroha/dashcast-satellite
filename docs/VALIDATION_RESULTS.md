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
