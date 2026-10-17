# Baseline verification — October 9, 2026

## IPv4 hotspot and pairing diagnostics — October 11, 2026

Satellite 0.5.0-dev (version code 6) uses local IPv4 for pairing and WSS, while retaining
the reviewed v1 profile parser and pinned contract fixtures. The matching receiver is
DashCast 1.9.14-beta (version code 653). No pairing-secret or certificate migration is required.

Regressions failed before the changes for an actual receiver **Pair device** click with
a failed certificate/profile provider, a gateway that had changed since the saved profile,
and an attempted IPv6 endpoint. The same scenarios pass after the changes. Earlier pairing
lifecycle fixtures started with an already prepared listener, so they did not exercise the
asynchronous preparation failure that silently dismissed the dialog.

Coverage includes IPv4-only automatic/manual pairing, legacy mixed profiles, mapped IPv6
literal exclusion, explicit missing-IPv4 status, pinned TLS to a LAN-provided gateway not
listed in the old profile, and rejection of an incorrect gateway certificate before hello.
A new callback-ordering test starts without a LAN, delivers **onAvailable** while capability
getters are absent, then receives Wi-Fi capabilities and completes the production pinned
TLS/hello/welcome path. That callback test was added with its correction; it was not separately
executed as a failing test before the change.

Receiver coverage includes certificate/no-address/bind/foreground-start failures, explicit
retry, pending preparation across CarPlay, original code/deadline retention, generation-owned
receiver cleanup and sampling while a listener close callback is blocked. The final targeted
receiver run passes 40 tests in four suites. Independent final verification passes **138
companion tests in 22 suites** and **989 receiver tests in 185 suites**, with no failures,
errors or skipped tests. Companion debug lint and receiver release lint report zero issues.
Companion debug assembly and signed receiver release assembly pass. Both protocol pins verify.
`graphify update .` completes in both repositories; existing partial Groovy/AST extraction
warnings do not prevent the Android builds from validating source syntax.

User screenshots confirm notification access is granted on Android 15 and WebView 153.0.8010.36.
They still show connection timeouts and no active navigation. The exact vehicle preparation
error, physical guidance and reconnection remain unverified; see [vehicle validation](CARLINKIT_VALIDATION.md).

## Connection and identity correction — October 10, 2026

Application 0.4.0-dev (version code 5), paired with DashCast 1.9.13-beta, corrects a
WebSocket upgrade rejection in both applications. Earlier TLS smoke tests used a
default client draft and missed the explicit empty subprotocol list in the production
transports. New regressions run the actual Satellite transport and LAN socket factory
against real pinned TLS sockets, and the actual receiver listener against a real client.
The affected upgrade tests fail before the fix and pass after it.

Coverage also includes gateway/subnet priority over private cellular addresses, bounded
endpoint cycling, preferred authenticated address, retry diagnostics, ten-second connection
and five-second authentication deadlines, cancellation of the owned sockets, certificate
rejection versus transient TLS errors, device identity in hello, stable installation UUIDs,
and Android restricted-settings guidance. Receiver tests cover the startup deadline and
linkage failures, optional device metadata and authenticated history across revocation.

The complete companion suite contains 131 passing tests in 22 suites with no failures,
errors or skips. Debug lint has zero issues, debug assembly succeeds and both protocol
pins verify. The updated v1 contract is copied from reviewed receiver commit
`2ec7d544044838ca5de365adf954d695779dd172`; navigation fixtures remain unchanged.
Artifact hashes accompany the local build.
The app remains independently signed. Existing pairing profiles need no migration.

The user reports working code pairing and has enabled Android's restricted settings.
Notification-listener access, sustained WSS on the vehicle, physical guidance, clearing
and reconnection still need the [vehicle checklist](CARLINKIT_VALIDATION.md). The original
vehicle Starting state has not been traced to a specific device log.

## Initial baseline

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


## 0.9.4-dev first-frame diagnostics

233 unit/protocol/transport tests passed across 36 suites, with zero failures, errors or skipped tests.
Android debug lint reported zero issues and debug assembly passed. Protocol v1 is pinned, byte for
byte with fixture hashes, to reviewed receiver commit `9a330c867eafef6e5f6e74bf43cefe2497b70a94`;
pairing and navigation fixtures remain unchanged. The tests cover the exact capability matrix,
legacy serialization, immutable pending transfer ownership, maximum complete UTF-8 snapshot size,
missing versus zero counters, keyframe relationships and closed-attempt original sample retention.
Capture lifecycle and encoded-frame supervision remain independent of these observational fields.
Real Tbox/vehicle first-image diagnosis and decoding performance remain pending; these checks do
not establish a successful image or hardware decoder use.


## 0.9.5-dev bounded hardware startup recovery

251 tests across 41 suites passed without failures, errors or skipped tests. Debug lint reported zero
issues and debug assembly passed. The pinned WebRTC archive and protocol fixtures remain unchanged.
The new tests exercise all public encoder/factory entry points, native KEY/EMPTY hints, connected-only
finite scheduling, frame gaps and clock regressions, release/reinit/late old factory callbacks, actual
VideoLink ready epochs, source loss before worker cleanup, relay handoff without frame retention and
cleanup after each partial allocation/throwing teardown. Explicit forwarding preserves Java default
method overrides, including rates, native-encoder handling and alignment metadata.

The same projection/display/texture helper supplies per-attempt sources; no extra capture consent or
periodic resync loop is introduced. JVM and static checks do not establish that Tbox hardware honors
a sync request, that WebView decodes an image, or that the vehicle saves CPU/GPU. Those remain
physical validation gates using the existing manual sender/receiver diagnostics.


## 0.9.6-dev actual encoded H264 diagnostics

264 tests across 42 suites passed with zero failures, errors or skips. Debug lint reports zero
issues and debug assembly passes. A preceding fresh focused run reran all 30 tasks, including
actual pinned TLS transport capability tests. Protocol v1 and six accepted/61 rejected diagnostic
fixtures are pinned byte-for-byte to reviewed receiver commit
`b187fa12fe700af0ef3490e32925eb81559cfb6c`; pairing/navigation fixtures and the pinned WebRTC
archive are unchanged.

The checks cover direct read-only buffer window/mark/content preservation, actual header scalar
extraction and unknown/truncated/forbidden/reserved/short/empty NAL cases, leading/trailing zero
padding, callback/count/time/byte budgets, synchronous init output, native callback identity and
exception delivery, failed init/reinit/retirement fencing (including retirement during final
publication eligibility), historical scalar copies, independent observation/sample ages, exact
capability downgrade/reconnect behavior and the full maximum-size schema-2 manual snapshot.
The collector does not hold policy/history locks while parsing; JSON is built after copying the
bounded history under its monitor. Encoder/codec/capture/receiver behavior is otherwise unchanged.

These are source/JVM/static checks. Real Tbox encoder bytes, the vehicle's first decoded image,
latency, thermal behavior and CPU/GPU savings require parked-device validation. Final APK metadata,
signing and independent checks are recorded with the frozen release artifacts.

## 0.9.7-dev explicit hardware H264 Baseline

The investigated field snapshot received H264 RTP without a decoded image: negotiated profile bytes
were `42e01f`, while all sampled actual SPS headers were `64001f` (High). This establishes a profile
mismatch, not a proof that no other decoder issue remains.

The exact `io.github.webrtc-sdk:android:150.7871.01` AAR SHA-256 remains
`0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`.
`javap -c -p org.webrtc.HardwareVideoEncoder` on its `classes.jar` shows that the Baseline branch of
`initEncodeInternal` at bytecode offset 310 skips profile/level writes; only the High branch at
288–304 writes them. The initialization exception table catches IllegalArgumentException and
IllegalStateException. `getImplementationName` directly returns the chosen codec name before init.
The uninitialized release path has no output thread, initialized shader or allocated YUV textures.
This is shipped-artifact evidence, not an assumption based only on an upstream HEAD source file.

The adapter keeps the original SDK hardware encoder and candidate choice. Tests reach its actual
Java initialization with an injected MediaCodecWrapper and exercise a size-triggered internal
restart. They check explicit profile/level, CBR, bitrate, framerate, color format, keyframe interval,
configuration refusal and exact native-wrapper cleanup. Pure policy and callback tests cover
Constrained Baseline equivalent patterns, lower negotiated levels/asymmetry, capability selection,
bounded malformed prefixes, buffer identity, late incompatible output after diagnostic expiry,
initialization generations and queued failures crossing viewer/source/control ownership changes.

283 tests across 45 suites pass with zero failures, errors or skips. Debug lint reports zero issues
and debug assembly passes. Protocol and pairing pin verification passes; diagnostic budgets and
native library bytes remain unchanged. The real Activity regression checks the persistent error,
Start disabled through cleanup, fresh consent after cleanup, control/guidance independence and the
existing safe wire-state fallback. Independent source/artifact checks are recorded before
publication. Static/JVM checks do not
establish that the Tbox honors the profile, that the vehicle decodes an image or that CPU/GPU usage
is lower. A parked-device trial with actual SPS and decoded-image observations remains required.
