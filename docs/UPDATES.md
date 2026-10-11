# DashSat updates

The first APK with OTA support is **0.6.0-dev** (version code **7**).
Install it manually without uninstalling. Thereafter open **Updates → Check for updates**.
A newer compatible public GitHub release shows its version and notes. Tap **Download
update / Retry**, then **Install update**. If Android asks to allow this installation
source, grant it in Settings, return and tap **Install update** again. Installation
always requires Android's confirmation. Cancelled installation can be retried manually.
Complete this setup while parked.

## Trust and channels

Metadata comes from the public `Kiroha/dashcast-satellite` GitHub releases API, including
prereleases. At most five pages of 100 releases and 2 MiB per metadata response are read.
Only numeric three-component versions are supported: stable `X.Y.Z` for production and
`X.Y.Z-dev` for debug. Drafts, other channels, unsupported names and missing integrity
metadata are ignored. The updater compares versions numerically, then verifies the APK's
strictly increasing version code. Future signing-key rotation requires a reviewed updater
change: this version requires an exact match to the installed current signer set.

APK and checksum names must exactly match `DashSat-vVERSION-debug.apk` /
`DashSat-vVERSION-release.apk` and `DashSat-vVERSION-SHA256SUMS.txt`, or their
legacy `DashCast-Satellite` equivalents. A release with multiple matching APKs
or checksum assets is ignored. Repository, package IDs and signing keys are unchanged.

Only HTTPS asset URLs under this repository's release download path are accepted.
Redirects are limited to HTTPS on `api.github.com`, `github.com`,
`release-assets.githubusercontent.com` and `objects.githubusercontent.com`, with no
custom port or user information. Redirect count, connection/read timeouts, response
sizes and download duration are bounded. APKs are limited to 128 MiB.

The GitHub SHA-256 asset digest is preferred. For older assets, the exact APK filename
must have one entry in that release's SHA256SUMS asset. Hashes alone do not establish
trust: Android's archive parser verifies APK signing and the current installed signer
set must match. Exact package ID, release version, increasing version code, minimum SDK
and debuggable channel must match too. Hash, length and identity are checked again on
an install attempt, including after returning from permission settings.

Each operation uses a private, unique cache staging folder. Only a verified APK is
shared by a non-exported FileProvider, with a read grant for the install intent. No
pairing files, tokens or notification data are shared. Interrupted downloads are removed;
Android may reclaim cache or kill the process, in which case check/download again.

## Runtime behavior

Checks and downloads are manual and run off the UI thread. Configuration changes retain
only a session with application context, not an Activity. Closing Updates or pressing Back cancels
pending work. Temporarily switching to Settings or another app can retain the operation. Returning from installation-source settings never launches installation
automatically. The package installer handles final signature verification, consent and
replacement. No system privilege, platform key, ADB or unattended install is used.

The updater uses Android's default Internet network. It never binds the process to
DashSat's LAN or stops guidance merely to check/download. A hotspot without Internet
access needs a working Internet path before an update can download; the updater does
not force cellular routing. App installation naturally replaces/restarts DashSat;
the existing boot/package-replaced recovery preference remains in charge.

## Device acceptance still required

On the Tbox Ultra 1 / Android 15, test offline/check retry, normal download, rotation,
Back/cancel during download, blocked and granted unknown-app source settings, cancelled
installer and successful replacement by a newer APK signed with the same Satellite key.
Confirm pairing survives and guidance reconnects according to the user's existing
recovery settings. JVM/UI tests do not establish firmware compatibility or vehicle acceptance.
