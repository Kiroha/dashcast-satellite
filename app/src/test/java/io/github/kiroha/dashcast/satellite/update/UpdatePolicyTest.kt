package io.github.kiroha.dashcast.satellite.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatePolicyTest {
    private val digest = "a".repeat(64)
    private fun item(version: String, draft: Boolean = false, prerelease: Boolean = true,
        url: String = "https://github.com/Kiroha/dashcast-satellite/releases/download/v$version/DashCast-Satellite-v$version-debug.apk",
        hash: String = "sha256:$digest") = """{"tag_name":"v$version","draft":$draft,"prerelease":$prerelease,"assets":[{"name":"DashCast-Satellite-v$version-debug.apk","size":100,"digest":"$hash","browser_download_url":"$url"}]}"""
    private fun brandedItem(version: String, prefix: String = "DashSat", debug: Boolean = true,
        hash: Boolean = true, sumsPrefix: String? = null): String {
        val apkName = "$prefix-v$version-${if (debug) "debug" else "release"}.apk"
        val sumsName = "$sumsPrefix-v$version-SHA256SUMS.txt"
        val sums = if (sumsPrefix == null) "" else
            ",{" + "\"name\":\"$sumsName\",\"size\":100,\"browser_download_url\":\"https://github.com/Kiroha/dashcast-satellite/releases/download/v$version/$sumsName\"}"
        return """{"tag_name":"v$version","draft":false,"prerelease":$debug,"assets":[{"name":"$apkName","size":100,"digest":"${if (hash) "sha256:$digest" else ""}","browser_download_url":"https://github.com/Kiroha/dashcast-satellite/releases/download/v$version/$apkName"}$sums]}"""
    }
    @Test fun `DashSat assets support debug and production channels with exact names`() {
        assertEquals("DashSat-v0.7.0-dev-debug.apk",
            UpdatePolicy.select("[${brandedItem("0.7.0-dev")}]", "0.6.0-dev", true)?.apk?.name)
        assertEquals("DashSat-v0.7.0-release.apk",
            UpdatePolicy.select("[${brandedItem("0.7.0", debug=false)}]", "0.6.0", false)?.apk?.name)
        assertNull(UpdatePolicy.select("[${brandedItem("0.7.0", debug=false)}]", "0.6.0-dev", true))
        assertNull(UpdatePolicy.select("[${brandedItem("0.7.0-dev", prefix="Other")}]", "0.6.0-dev", true))
    }
    @Test fun `both exact checksum prefixes support fallback and legacy releases remain readable`() {
        listOf("DashSat", "DashCast-Satellite").forEach { apkPrefix ->
            listOf("DashSat", "DashCast-Satellite").forEach { sumsPrefix ->
                val release = UpdatePolicy.select("[${brandedItem("0.7.0-dev", prefix=apkPrefix,
                    hash=false, sumsPrefix=sumsPrefix)}]", "0.6.0-dev", true)
                assertEquals("$sumsPrefix-v0.7.0-dev-SHA256SUMS.txt", release?.checksums?.name)
                assertEquals(digest, UpdatePolicy.checksum("$digest  ${release!!.apk.name}", release.apk.name))
            }
        }
        assertNotNull(UpdatePolicy.select("[${item("0.7.0-dev")}]", "0.6.0-dev", true))
        assertNull(UpdatePolicy.select("[${brandedItem("0.7.0-dev", hash=false, sumsPrefix="Other")}]",
            "0.6.0-dev", true))
    }
    @Test fun `duplicate or alternate matching APK and checksum assets are ambiguous`() {
        val branded = org.json.JSONObject(brandedItem("0.7.0-dev", sumsPrefix="DashSat"))
        val legacy = org.json.JSONObject(brandedItem("0.7.0-dev", prefix="DashCast-Satellite",
            sumsPrefix="DashCast-Satellite"))
        listOf(0, 1).forEach { index ->
            val duplicate = org.json.JSONObject(branded.toString())
            duplicate.getJSONArray("assets").put(branded.getJSONArray("assets").getJSONObject(index))
            assertNull(UpdatePolicy.select("[$duplicate]", "0.6.0-dev", true))
            val mixed = org.json.JSONObject(branded.toString())
            mixed.getJSONArray("assets").put(legacy.getJSONArray("assets").getJSONObject(index))
            assertNull(UpdatePolicy.select("[$mixed]", "0.6.0-dev", true))
        }
    }
    @Test fun `numeric order ignores publication order drafts unsupported tags and release channel`() {
        val json = "[${item("0.9.0-dev")},${item("0.10.0-dev")},${item("9.0.0-dev", draft=true)},${item("20.0.0")},${item("0.11.0-beta")}]"
        assertEquals("v0.10.0-dev", UpdatePolicy.select(json,"0.6.0-dev",true)?.tag)
        assertNull(UpdatePolicy.select(json,"0.10.0-dev",true))
        assertNull(UpdatePolicy.select("[${item("0.10.0-dev")}]","0.6.0",false))
    }
    @Test fun `untrusted URLs and missing integrity metadata cannot become updates`() {
        listOf("http://github.com/Kiroha/dashcast-satellite/releases/download/v/x.apk",
            "https://github.com.attacker.test/x.apk", "https://github.com/other/repo/releases/download/v/x.apk",
            "https://user@github.com/Kiroha/dashcast-satellite/releases/download/v/x.apk",
            "https://github.com/Kiroha/dashcast-satellite/releases/download/../x.apk").forEach {
            assertFalse(UpdatePolicy.trustedAssetUrl(it))
            assertNull(UpdatePolicy.select("[${item("0.7.0-dev",url=it)}]","0.6.0-dev",true))
        }
        assertNull(UpdatePolicy.select("[${item("0.7.0-dev",hash="")}]","0.6.0-dev",true))
    }
    @Test fun `checksum must identify exactly one complete matching filename`() {
        assertEquals(digest, UpdatePolicy.checksum("$digest  file.apk\n", "file.apk"))
        listOf("$digest  ../file.apk", "$digest  file.apk\n$digest  file.apk", "bad  file.apk").forEach {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum(it,"file.apk") }
        }
    }
    @Test fun `apk trust includes package version code sdk current signer and channel`() {
        val installed = ApkIdentity("app.debug", "0.6.0-dev",7,26,setOf("trusted"),true)
        val release = requireNotNull(UpdatePolicy.select("[${item("0.7.0-dev")}]","0.6.0-dev",true))
        val apk = installed.copy(versionName="0.7.0-dev",versionCode=8)
        ApkPolicy.validate(installed,apk,release,35)
        listOf(apk.copy(packageName="other"), apk.copy(versionName="0.8.0-dev"),apk.copy(versionCode=7),
            apk.copy(minSdk=36),apk.copy(signers=setOf("attacker")),apk.copy(signers=emptySet()),apk.copy(debug=false)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ApkPolicy.validate(installed,it,release,35) }
        }
    }
}
