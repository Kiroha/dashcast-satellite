# Native WebRTC dependency

Reviewed on October 17, 2026: `io.github.webrtc-sdk:android:150.7871.01`, from Maven Central.
The maintained fork documents this exact coordinate in its [official README](https://github.com/webrtc-sdk/android).
DashSat 0.9.9-dev and DashCast 1.9.46-beta use the same app-local native SDK. The system WebView
is unchanged. M150 is the publisher latest release; later M144 maintenance remains an older branch.

AAR SHA-256: `0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`.
`verifyWebRtcArchive` runs before build and rejects another version or archive. No dynamic version
or dependency repository is added. Upgrading requires review of Java/native APIs, notice files,
Android capture policy and both endpoint checks.

Native `.so` uncompressed sizes in the reviewed archive:

| ABI | Bytes |
| --- | ---: |
| armeabi-v7a | 6,809,404 |
| arm64-v8a | 12,287,312 |
| x86 | 12,818,648 |
| x86_64 | 16,166,352 |

All four ABIs are retained for device compatibility and emulator checks. APK compression and
packaging differ from these numbers; record the actual release APK size. This is a substantial
size increase. DashCast now also ships the ARMv7/ARM64 libraries for hardware-only native reception.

The fork's MIT license and its aggregate upstream/third-party notices are bundled under
`app/src/main/assets/webrtc-sdk-license.txt` and `webrtc-third-party-notices.md`, sourced from
[the publisher's license](https://github.com/webrtc-sdk/android/blob/v150.7871.01/LICENSE) and
[upstream notices](https://github.com/webrtc-sdk/android/blob/v150.7871.01/Licenses/WEBRTC.md).

Inspection of the exact AAR confirms that `ScreenCapturerAndroid` resizes its VirtualDisplay
in place on API 31+, but ignores its requested FPS and lacks the required content visibility/static
frame policy. DashSat uses its own small capturer with one consent, one VirtualDisplay, in-place
resizing, a SurfaceTextureHelper and explicit lifecycle cleanup. Its EGL texture is never converted
to a CPU bitmap in the app. The native hardware encoder remains responsible for any internal
codec conversion; hardware compatibility still requires testing on the box.

The exact SDK contains H265 Java MediaCodec selection branches, but this does not establish native
negotiation, usable hardware on both devices or end-to-end rendering. Current endpoint factories
advertise only guarded H.264 Baseline 3.1. DashSat installs no receive decoder or software fallback.
Actual receiver hardware initialization and completed renders are reported by DashCast; sustained
vehicle CPU/GPU/thermal benefits still require a controlled local-app comparison.
