package io.github.kiroha.dashcast.satellite.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Uses Android's ordinary Internet routing; never binds the process or touches the guidance socket. */
interface UpdateBackend {
    fun latest(): UpdateRelease?
    fun download(release: UpdateRelease, progress: (Int) -> Unit): File
    fun validate(file: File, release: UpdateRelease)
    fun cancel()
}

class UpdateClient(private val context: Context) : UpdateBackend {
    private val directory = File(context.cacheDir, "updates/${java.util.UUID.randomUUID()}")
    private var verifiedDigest: String? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    override fun cancel() { cancelled.set(true); connection?.disconnect() }
    private fun checkCancelled() { check(!cancelled.get()) { "cancelled" } }

    private fun fetch(url: String, max: Long, consume: (ByteArray, Int) -> Unit): Long {
        var current = URL(url)
        val deadline = SystemClock.elapsedRealtime() + 10 * 60_000
        for (redirect in 0..5) {
            checkCancelled()
            require(current.protocol == "https" && current.port == -1 && current.userInfo == null &&
                current.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val request = current.openConnection() as HttpURLConnection
            connection = request
            request.instanceFollowRedirects = false
            request.connectTimeout = 15_000
            request.readTimeout = 20_000
            request.setRequestProperty("Accept", if (current.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            request.setRequestProperty("User-Agent", "DashSat-Updates")
            try {
                val code = request.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5)
                    current = URL(current, request.getHeaderField("Location") ?: error("invalid_redirect"))
                    continue
                }
                require(code == 200) { "network_unavailable" }
                require(request.contentLengthLong <= max) { "response_too_large" }
                var total = 0L
                request.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkCancelled()
                        check(SystemClock.elapsedRealtime() <= deadline) { "download_timeout" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= max) { "response_too_large" }
                        consume(buffer, count)
                    }
                }
                checkCancelled()
                return total
            } finally { request.disconnect(); connection = null }
        }
        error("invalid_redirect")
    }
    private fun text(url: String, max: Long): String {
        val output = java.io.ByteArrayOutputStream()
        fetch(url, max) { bytes, count -> output.write(bytes, 0, count) }
        return output.toString(Charsets.UTF_8.name())
    }
    fun installed(): ApkIdentity = identity(context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS))
    override fun latest(): UpdateRelease? {
        val installed = installed()
        var best: UpdateRelease? = null
        // Bounded pagination includes prereleases; /releases/latest excludes them.
        for (page in 1..5) {
            val json = text("https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases?per_page=100&page=$page", 2 * 1024 * 1024)
            val candidate = UpdatePolicy.select(json, installed.versionName, installed.debug)
            if (candidate != null && (best == null || candidate.version > best.version)) best = candidate
            if (org.json.JSONArray(json).length() < 100) break
        }
        return best
    }
    override fun download(release: UpdateRelease, progress: (Int) -> Unit): File {
        require(directory.exists() || directory.mkdirs()) { "storage_failed" }
        val temporary = File(directory, "download.part")
        val result = File(directory, "satellite-update.apk")
        result.delete()
        try {
            val expected = release.apk.digest ?: UpdatePolicy.checksum(
                text(requireNotNull(release.checksums).url, 65536), release.apk.name)
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            temporary.outputStream().use { output ->
                fetch(release.apk.url, release.apk.size) { bytes, count ->
                    output.write(bytes, 0, count)
                    hash.update(bytes, 0, count)
                    received += count
                    progress((received * 100 / release.apk.size).toInt())
                }
            }
            require(received == release.apk.size && hex(hash.digest()) == expected) { "integrity_failed" }
            verifiedDigest = expected
            validate(temporary, release)
            checkCancelled()
            require(temporary.renameTo(result)) { "storage_failed" }
            return result
        } finally { temporary.delete() }
    }
    override fun validate(file: File, release: UpdateRelease) {
        checkCancelled()
        val expected = release.apk.digest ?: verifiedDigest ?: error("missing_checksum")
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= release.apk.size)
                hash.update(buffer, 0, count)
            }
        }
        require(size == release.apk.size && hex(hash.digest()) == expected) { "integrity_failed" }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, SIGNATURE_FLAGS)
            ?: error("invalid_apk")
        ApkPolicy.validate(installed(), identity(archive), release, Build.VERSION.SDK_INT)
    }
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else @Suppress("DEPRECATION") info.signatures
        val signers = signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return ApkIdentity(info.packageName, requireNotNull(info.versionName), code,
            info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, signers,
            ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }
    companion object {
        private val SIGNATURE_FLAGS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
