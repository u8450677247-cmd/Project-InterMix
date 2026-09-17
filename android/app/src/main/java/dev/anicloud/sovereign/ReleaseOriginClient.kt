package dev.anicloud.sovereign.prototype

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class ReleaseTrustConfig(
    val origin: URI,
    val manifestPublicKeyBase64: String,
    val apkSignerSha256: String,
) {
    companion object {
        fun configuredOrNull(): ReleaseTrustConfig? {
            val origin = BuildConfig.RELEASE_ORIGIN_URL.trim()
            val publicKey = BuildConfig.RELEASE_MANIFEST_ED25519_PUBLIC_KEY_B64.trim()
            val signer = BuildConfig.RELEASE_APK_CERT_SHA256.trim().lowercase(Locale.ROOT)
            if (origin.isEmpty() && publicKey.isEmpty() && signer.isEmpty()) return null
            require(origin.isNotEmpty() && publicKey.isNotEmpty() && signer.matches(Regex("^[0-9a-f]{64}$"))) {
                "Release updater trust configuration is incomplete."
            }
            return ReleaseTrustConfig(
                origin = ReleaseOriginPolicy.validateOrigin(origin),
                manifestPublicKeyBase64 = publicKey,
                apkSignerSha256 = signer,
            )
        }
    }
}

class ReleaseOriginClient(private val config: ReleaseTrustConfig) {
    fun fetchManifest(): Pair<ByteArray, ByteArray> {
        val envelope = fetchSmall(
            ReleaseOriginPolicy.resolve(config.origin, "channels/$ReleaseChannel/release.json"),
            MaxReleaseEnvelopeBytes,
        )
        return ReleaseEnvelope.decode(envelope)
    }

    fun downloadApk(
        manifest: ReleaseManifest,
        partial: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        partial.parentFile?.mkdirs()
        require(partial.parentFile?.isDirectory == true) { "Update download directory is unavailable." }
        var offset = partial.takeIf(File::isFile)?.length() ?: 0L
        if (offset > manifest.apkBytes) {
            require(partial.delete()) { "Oversized partial update could not be reset." }
            offset = 0L
        }
        if (offset == manifest.apkBytes) {
            onProgress(offset)
            return
        }

        val uri = ReleaseOriginPolicy.resolve(config.origin, manifest.apkPath)
        val connection = open(uri)
        try {
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            val response = connection.responseCode
            val append = when {
                response == HttpURLConnection.HTTP_PARTIAL && offset > 0L -> {
                    val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                    require(contentRange.startsWith("bytes $offset-")) {
                        "Release origin returned an invalid resume range."
                    }
                    true
                }
                response == HttpURLConnection.HTTP_OK -> false
                response in 300..399 -> throw IOException("Release origin redirects are not accepted.")
                else -> throw IOException("Release APK request failed with HTTP $response.")
            }
            if (!append) offset = 0L
            FileOutputStream(partial, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = offset
                    var lastPublished = total
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= manifest.apkBytes) { "Release APK exceeded its signed size." }
                        output.write(buffer, 0, read)
                        if (total - lastPublished >= 512 * 1024L) {
                            onProgress(total)
                            lastPublished = total
                        }
                    }
                    output.fd.sync()
                    require(total == manifest.apkBytes) {
                        "Release APK download is incomplete ($total/${manifest.apkBytes})."
                    }
                    onProgress(total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchSmall(uri: URI, maximumBytes: Int): ByteArray {
        val connection = open(uri)
        try {
            val response = connection.responseCode
            if (response in 300..399) throw IOException("Release origin redirects are not accepted.")
            if (response != HttpURLConnection.HTTP_OK) {
                throw IOException("Release manifest request failed with HTTP $response.")
            }
            val declared = connection.contentLengthLong
            require(declared <= maximumBytes || declared < 0L) { "Release response is too large." }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= maximumBytes) { "Release response exceeded its size limit." }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(uri: URI): HttpsURLConnection =
        (uri.toURL().openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            // No device identifier, cohort, model, project, or installed version leaves the app.
            setRequestProperty("User-Agent", "Intermix-Updater/1")
        }
}

data class VerifiedReleaseApk(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
)

class ReleaseApkVerifier(private val context: Context) {
    fun verify(file: File, manifest: ReleaseManifest, pinnedSignerSha256: String): VerifiedReleaseApk {
        require(file.isFile && file.length() == manifest.apkBytes) { "Release APK size does not match." }
        require(sha256File(file) == manifest.apkSha256) { "Release APK SHA-256 does not match." }
        require(manifest.apkSignerSha256 == pinnedSignerSha256) {
            "Manifest signer is not the signer pinned by this app."
        }
        val archive = archivePackageInfo(file)
            ?: throw SecurityException("Android could not parse the release APK.")
        require(archive.packageName == context.packageName) { "Release APK package name does not match." }
        require(archive.longVersionCode == manifest.versionCode) { "Release APK version code does not match." }
        val archiveSigners = signerFingerprints(archive)
        require(archiveSigners == setOf(pinnedSignerSha256)) { "Release APK signing identity does not match." }
        val installed = installedPackageInfo()
        require(signerFingerprints(installed).contains(pinnedSignerSha256)) {
            "Installed app does not use the pinned release signing identity."
        }
        return VerifiedReleaseApk(archive.packageName, archive.longVersionCode, pinnedSignerSha256)
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun signerFingerprints(info: PackageInfo): Set<String> {
        val signingInfo = requireNotNull(info.signingInfo) { "APK signing information is unavailable." }
        return signingInfo.apkContentsSigners.map { it.toByteArray().sha256Hex() }.toSet()
    }
}
