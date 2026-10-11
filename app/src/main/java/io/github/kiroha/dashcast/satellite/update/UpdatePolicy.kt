package io.github.kiroha.dashcast.satellite.update

import org.json.JSONArray
import java.net.URI

/** Release names and APK names deliberately identify the application channel. */
data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long, val dev: Boolean) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^v?([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})(-dev)?$").matchEntire(value) ?: return null
            return ReleaseVersion(match.groupValues[1].toLong(), match.groupValues[2].toLong(),
                match.groupValues[3].toLong(), match.groupValues[4].isNotEmpty())
        }
    }
}

data class UpdateAsset(val name: String, val url: String, val size: Long, val digest: String?)
data class UpdateRelease(val tag: String, val version: ReleaseVersion, val notes: String,
    val apk: UpdateAsset, val checksums: UpdateAsset?)

object UpdatePolicy {
    const val REPOSITORY = "Kiroha/dashcast-satellite"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    fun trustedAssetUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/download/") &&
            !uri.rawPath.contains("..") && !uri.rawPath.contains('%')
    }.getOrDefault(false)

    fun select(json: String, installedVersion: String, debug: Boolean): UpdateRelease? {
        val installed = ReleaseVersion.parse(installedVersion) ?: error("unsupported_version")
        val releases = JSONArray(json)
        require(releases.length() <= 100)
        return (0 until releases.length()).mapNotNull { i ->
            val item = releases.getJSONObject(i)
            if (item.optBoolean("draft")) return@mapNotNull null
            val tag = item.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: return@mapNotNull null
            if (version.dev != debug || (!debug && item.optBoolean("prerelease")) || version <= installed)
                return@mapNotNull null
            val prefixes = listOf("DashSat", "DashCast-Satellite")
            val expectedApks = prefixes.map {
                "$it-v${tag.removePrefix("v")}-${if (debug) "debug" else "release"}.apk"
            }
            val expectedChecksums = prefixes.map { "$it-v${tag.removePrefix("v")}-SHA256SUMS.txt" }
            val assets = item.optJSONArray("assets") ?: return@mapNotNull null
            val parsed = (0 until assets.length()).mapNotNull { n ->
                val asset = assets.getJSONObject(n)
                val url = asset.optString("browser_download_url")
                if (!trustedAssetUrl(url)) return@mapNotNull null
                val size = asset.optLong("size", -1)
                val digest = asset.optString("digest").takeIf { it.matches(Regex("sha256:[a-fA-F0-9]{64}")) }
                    ?.substringAfter(':')?.lowercase()
                UpdateAsset(asset.optString("name"), url, size, digest)
            }
            val apk = parsed.singleOrNull { it.name in expectedApks && it.size in 1..MAX_APK_BYTES }
                ?: return@mapNotNull null
            val checksumAssets = parsed.filter { it.name in expectedChecksums && it.size in 1..65536 }
            if (checksumAssets.size > 1) return@mapNotNull null
            val sums = checksumAssets.singleOrNull()
            if (apk.digest == null && sums == null) return@mapNotNull null
            UpdateRelease(tag, version, item.optString("body").take(12000), apk, sums)
        }.maxByOrNull { it.version }
    }

    fun checksum(text: String, assetName: String): String {
        val matches = text.lineSequence().mapNotNull { line ->
            val m = Regex("^([a-fA-F0-9]{64})  \\*?(.+)$").matchEntire(line.trimEnd()) ?: return@mapNotNull null
            if (m.groupValues[2] == assetName) m.groupValues[1].lowercase() else null
        }.toList()
        require(matches.size == 1) { "invalid_checksum" }
        return matches.single()
    }
}

data class ApkIdentity(val packageName: String, val versionName: String, val versionCode: Long,
    val minSdk: Int, val signers: Set<String>, val debug: Boolean)
object ApkPolicy {
    fun validate(installed: ApkIdentity, archive: ApkIdentity, release: UpdateRelease, sdk: Int) {
        require(archive.packageName == installed.packageName) { "wrong_package" }
        require(archive.versionName == release.tag.removePrefix("v")) { "wrong_version" }
        require(archive.versionCode > installed.versionCode) { "downgrade" }
        require(archive.minSdk <= sdk) { "unsupported_android" }
        require(archive.signers.isNotEmpty() && archive.signers == installed.signers) { "wrong_signature" }
        require(archive.debug == installed.debug) { "wrong_channel" }
    }
}
