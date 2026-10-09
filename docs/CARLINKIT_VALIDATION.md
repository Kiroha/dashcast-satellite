# Vehicle validation

Status: **pending testing on physical hardware**. A build and JVM tests do not prove correct
physical display output or correct operation of AndroidKeyStore storage/TLS on the box.

## Initial device record

| Item | Value / result |
| --- | --- |
| Model reported by the user | Carlinkit Tbox Ultra 1 |
| Reported Android version | Android 15 |
| Exact manufacturer / model shown in the app | To record |
| Box build number / firmware | To record |
| Satellite / DashCast APK version | To record |
| Box and vehicle WebView | To record |
| Notification access visible and grantable | To verify |
| Google Maps version and language | To record |
| ABRP version and language | To record |
| Device hosting the hotspot | To identify |
| Usable local network, port 47832 reachable | To verify |
| SIM: Maps/ABRP mobile data usable during local WSS | To verify |
| Power saving / OEM restrictions | To record |

The Satellite screen shows the manufacturer, model, Android/API, and WebView version. Do not attach
pairing files, tokens, screenshots of notifications, or private routes to reports.
To document an unsupported guidance format, provide only a synthetic, anonymized example
reproducing the relevant fields, together with the source app's version and language.

## First milestone: guidance

| Test | Expected result | Observed |
| --- | --- | --- |
| Satellite receiver disabled | Local DashCast operation unchanged | Pending |
| Import a valid profile | Authenticated local connection | Pending |
| Revoked profile / different certificate | No guidance accepted; pairing required again | Pending |
| Socket connected, no navigation | Inactive source, no old turn | Pending |
| Maps with explicit guidance | Correct maneuver and distance on the HUD/cluster | Pending |
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
