# Native sender dependency

Reviewed on October 11, 2026: `io.github.webrtc-sdk:android:150.7871.01`, from Maven Central.
The maintained fork documents this exact coordinate in its [official README](https://github.com/webrtc-sdk/android).
The companion alone ships the AAR. DashCast retains its installed WebView receiver.

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
companion-only size increase. The vehicle APK gains no native WebRTC codecs from this feature.

The fork's MIT license and its aggregate upstream/third-party notices are bundled under
`app/src/main/assets/webrtc-sdk-license.txt` and `webrtc-third-party-notices.md`, sourced from
[the publisher's license](https://github.com/webrtc-sdk/android/blob/main/LICENSE) and
[upstream notices](https://github.com/webrtc-sdk/android/blob/main/Licenses/WEBRTC.md).

Inspection of the exact AAR confirms that `ScreenCapturerAndroid` resizes its VirtualDisplay
in place on API 31+, but ignores its requested FPS and lacks the required content visibility/static
frame policy. DashSat uses its own small capturer with one consent, one VirtualDisplay, in-place
resizing, a SurfaceTextureHelper and explicit lifecycle cleanup. Its EGL texture is never converted
to a CPU bitmap in the app. The native hardware encoder remains responsible for any internal
codec conversion; hardware compatibility still requires testing on the box.
