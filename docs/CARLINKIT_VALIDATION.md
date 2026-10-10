# Vehicle validation

Status: **partial user feedback; guidance display still pending validation**. A build and JVM tests do not prove correct
physical display output or correct operation of AndroidKeyStore storage/TLS on the box.

## Initial device record

| Item | Value / result |
| --- | --- |
| Model reported by the user | Carlinkit Tbox Ultra 1 |
| Reported Android version | Android 15 |
| Exact manufacturer / model shown in the app | QUALCOMM Lito for arm64 (user screenshot) |
| Box build number / firmware | To record |
| Satellite / DashCast APK version | To record |
| Box and vehicle WebView | To record |
| Notification access visible and grantable | Restricted-settings denial observed; user enabled “Allow restricted settings” on October 10. Final listener grant/observation still to verify. |
| Google Maps version and language | To record |
| ABRP version and language | To record |
| Device hosting the hotspot | Vehicle, using TetherFuseNet; Tbox connected as Wi-Fi client (user reported) |
| Usable local network, port 47832 reachable | To verify |
| SIM: Maps/ABRP mobile data usable during local WSS | To verify |
| Power saving / OEM restrictions | To record |

The Satellite screen shows the manufacturer, model, Android/API, and WebView version. Do not attach
pairing files, tokens, screenshots of notifications, or private routes to reports.
To document an unsupported guidance format, provide only a synthetic, anonymized example
reproducing the relevant fields, together with the source app's version and language.

## User report — October 10, 2026

The user reports that code pairing appears to work after the background-dialog fix. DashCast
remains at **Starting receiver** and Satellite at **connecting** for more than one minute;
**Use satellite guidance** is off in the receiver screenshot. Android 15 initially blocks
notification access with the restricted-settings dialog. The user confirmed enabling restricted
settings; this does not by itself confirm the final notification-listener grant.

Real local socket tests reproduced an independent WebSocket upgrade rejection in both apps.
DashCast 1.9.13-beta and Satellite 0.4.0-dev correct that failure, expose device identities and
connection diagnostics, and keep the previous pairing profile compatible. The receiver's exact
startup failure on the vehicle remains unconfirmed without its logs; a readiness deadline now
surfaces stalled startup instead of leaving it indefinitely at Starting.

Retest with both new APKs, then verify the notification-listener grant, enable **Use satellite
guidance**, and start an actual route. Confirm the same DashCast ID on both screens, authenticated
device identity, valid maneuver output, route-stop clearing and Wi-Fi reconnection. These
checks remain pending; no physical guidance success is inferred from pairing or local tests.

## First milestone: guidance

| Test | Expected result | Observed |
| --- | --- | --- |
| Satellite receiver disabled | Local DashCast operation unchanged | Pending |
| Import a valid profile | Authenticated local connection | Pending |
| Pair with DashCast using the code | Hotspot gateway detected; profile saved without a file; pinned WSS connects | Pending |
| Wrong code, then retry | Existing profile unchanged; correct code succeeds while window remains open | Pending |
| Switch DashCast → Tbox on the same vehicle screen | Six-digit code stays valid until its original two-minute deadline | Pending |
| Cancel pairing / expire code / revoke | Temporary endpoint closes; no late save or accepted old code | Pending |
| Gateway unavailable | Manual vehicle address shown in DashCast allows pairing on the same LAN | Pending |
| Revoked profile / different certificate | No guidance accepted; pairing required again | Pending |
| Socket connected, no navigation | Inactive source, no old turn | Pending |
| Maps with explicit guidance | Correct maneuver and distance on the HUD/cluster | Pending |
| Maps with a recognized notification arrow and distance, without a written direction | Portable maneuver sent; correct direction and distance on the HUD/cluster | Pending |
| Maps changes only its notification arrow | Direction changes on the next fresh observation, with no cached-image replay | Pending |
| Recognized roundabout plus an explicit exit 1–10 | Observed circulation and exit transmitted together | Pending |
| Roundabout without an exit, conflicting directions, or merge glyph | Unsupported under v1; previous guidance cleared | Pending |
| Maps with an unrecognized image only / ambiguous guidance | Unsupported state, no invented direction | Pending |
| ABRP | Active only when observable guidance is usable | Pending |
| Switch Maps ↔ ABRP | Previous source stopped, no mixed data | Pending |
| Stop navigation / remove notification | Previous guidance explicitly cleared | Pending |
| Revoke and restore notification access | Stop, then new observations only | Pending |
| Wi-Fi lost, then reconnected | Guidance cleared; resume with a recent observation | Pending |
| Close DashCast UI / satellite UI | Guidance services remain operational | Pending |
| Satellite Stop button | Socket closed, guidance cleared | Pending |
| HUD only / cluster only / both / no output | DashCast selections respected | Pending |
| Change receiver guidance setting | Validate actual effect; reconnect to refresh its status | Pending |
| Reboot box with resume disabled | Resume only after Start | Pending |
| Reboot box with resume enabled | Reconnect if Android allows it; no stale cache | Pending |
| Overnight / vehicle ignition cycles | Correct recovery without duplicate connections | Pending |
| SIM navigation during local connection | Source app's internet connection remains usable | Pending |

Timestamp state changes and observed display output without route content. The receiver expires
guidance after six seconds, including source age; detected loss must trigger a stop sooner.
A navigation notification incorrectly left active by a source app is an observation limitation
to investigate on the device, not proof of actual route progress.

## Second milestone: video (not included in this APK)

After guidance is validated: capture consent, map displayed on the cluster, viewer closure,
capture stop, reconnection, and renewed consent when required. Record the codec, resolution/frame
rate, screen-to-cluster latency, CPU/GPU, temperature, and Overdrive frame drops.
Final acceptance requires the actual vehicle, sources that stop correctly, and normal DashCast
operation preserved when the satellite is disabled.
