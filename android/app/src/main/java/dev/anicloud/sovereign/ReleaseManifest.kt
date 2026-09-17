package dev.anicloud.sovereign.prototype

import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Locale

const val ReleaseManifestSchema = 1
const val ReleaseChannel = "community"
const val MaxReleaseManifestBytes = 64 * 1024
const val MaxReleaseEnvelopeBytes = 96 * 1024
const val MaxReleaseApkBytes = 512L * 1024L * 1024L

data class ReleaseManifest(
    val schema: Int,
    val channel: String,
    val releaseId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkPath: String,
    val apkBytes: Long,
    val apkSha256: String,
    val apkSignerSha256: String,
    val sourceCommit: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val notBefore: Instant,
    val rolloutBasisPoints: Int,
    val minCurrentVersionCode: Long,
) {
    fun eligible(currentVersionCode: Long, cohortBasisPoints: Int, now: Instant): Boolean =
        versionCode > currentVersionCode &&
            currentVersionCode >= minCurrentVersionCode &&
            cohortBasisPoints in 0..9_999 &&
            cohortBasisPoints < rolloutBasisPoints &&
            !now.isBefore(notBefore) &&
            !now.isAfter(expiresAt)
}

/** Untrusted single-file transport wrapper; trust begins only after the inner signature verifies. */
object ReleaseEnvelope {
    private val expectedFields = setOf("schema", "manifest_b64", "signature_b64")

    fun decode(envelopeBytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(envelopeBytes.isNotEmpty() && envelopeBytes.size <= MaxReleaseEnvelopeBytes) {
            "Release envelope size is invalid."
        }
        val payload = JSONObject(envelopeBytes.toString(Charsets.UTF_8))
        val fields = mutableSetOf<String>()
        val keys = payload.keys()
        while (keys.hasNext()) fields += keys.next()
        require(fields == expectedFields && payload.requireInt("schema") == 1) {
            "Release envelope fields are invalid."
        }
        val encodedManifest = payload.requireString("manifest_b64", MaxReleaseEnvelopeBytes)
        val encodedSignature = payload.requireString("signature_b64", 128)
        val manifest = runCatching { Base64.getDecoder().decode(encodedManifest) }
            .getOrElse { throw IllegalArgumentException("Invalid release manifest encoding.") }
        require(manifest.isNotEmpty() && manifest.size <= MaxReleaseManifestBytes) {
            "Release manifest size is invalid."
        }
        require(encodedSignature.length == 88) { "Release signature size is invalid." }
        return manifest to encodedSignature.toByteArray(Charsets.US_ASCII)
    }
}

/** The detached signature authenticates the exact manifest bytes before manifest JSON is trusted. */
object ReleaseManifestVerifier {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val commitPattern = Regex("^[0-9a-f]{40,64}$")
    private val releaseIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
    private val relativePathPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,239}$")

    fun verifyAndParse(
        manifestBytes: ByteArray,
        detachedSignatureBase64: ByteArray,
        publicKeyBase64: String,
        expectedPackageName: String,
        expectedChannel: String = ReleaseChannel,
        now: Instant = Instant.now(),
        enforceFreshness: Boolean = true,
    ): ReleaseManifest {
        require(manifestBytes.isNotEmpty() && manifestBytes.size <= MaxReleaseManifestBytes) {
            "Release manifest size is invalid."
        }
        val publicKeyBytes = decodeBase64(publicKeyBase64, "manifest public key")
        val signatureBytes = decodeBase64(
            detachedSignatureBase64.toString(Charsets.US_ASCII),
            "manifest signature",
        )
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(publicKeyBytes),
        )
        val verified = Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(manifestBytes)
            verify(signatureBytes)
        }
        require(verified) { "Release manifest signature is invalid." }
        return parse(manifestBytes, expectedPackageName, expectedChannel, now, enforceFreshness)
    }

    private fun parse(
        manifestBytes: ByteArray,
        expectedPackageName: String,
        expectedChannel: String,
        now: Instant,
        enforceFreshness: Boolean,
    ): ReleaseManifest {
        val payload = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        val schema = payload.requireInt("schema")
        val channel = payload.requireString("channel", 32)
        val releaseId = payload.requireString("release_id", 96)
        val packageName = payload.requireString("package_name", 160)
        val versionCode = payload.requireLong("version_code")
        val versionName = payload.requireString("version_name", 96)
        val apkPath = payload.requireString("apk_path", 240)
        val apkBytes = payload.requireLong("apk_bytes")
        val apkSha256 = payload.requireString("apk_sha256", 64).lowercase(Locale.ROOT)
        val signerSha256 = payload.requireString("apk_signer_sha256", 64).lowercase(Locale.ROOT)
        val sourceCommit = payload.requireString("source_commit", 64).lowercase(Locale.ROOT)
        val issuedAt = payload.requireInstant("issued_at")
        val expiresAt = payload.requireInstant("expires_at")
        val notBefore = payload.requireInstant("not_before")
        val rollout = payload.requireInt("rollout_basis_points")
        val minCurrent = payload.requireLong("min_current_version_code")

        require(schema == ReleaseManifestSchema) { "Unsupported release manifest schema." }
        require(channel == expectedChannel) { "Unexpected release channel." }
        require(releaseIdPattern.matches(releaseId)) { "Invalid release identifier." }
        require(packageName == expectedPackageName) { "Release package does not match this app." }
        require(versionCode > 0L && minCurrent >= 0L) { "Invalid release version range." }
        require(versionName.isNotBlank()) { "Release version name is empty." }
        require(isSafeRelativePath(apkPath)) { "Unsafe release APK path." }
        require(apkBytes in 1..MaxReleaseApkBytes) { "Release APK size is invalid." }
        require(sha256Pattern.matches(apkSha256)) { "Invalid release APK SHA-256." }
        require(sha256Pattern.matches(signerSha256)) { "Invalid release signer SHA-256." }
        require(commitPattern.matches(sourceCommit)) { "Invalid release source commit." }
        require(rollout in 0..10_000) { "Invalid release rollout." }
        require(!expiresAt.isBefore(issuedAt) && !notBefore.isAfter(expiresAt)) {
            "Invalid release validity window."
        }
        if (enforceFreshness) {
            require(!issuedAt.isAfter(now.plusSeconds(15 * 60L))) { "Release manifest is from the future." }
            require(!now.isAfter(expiresAt)) { "Release manifest has expired." }
        }

        return ReleaseManifest(
            schema = schema,
            channel = channel,
            releaseId = releaseId,
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            apkPath = apkPath,
            apkBytes = apkBytes,
            apkSha256 = apkSha256,
            apkSignerSha256 = signerSha256,
            sourceCommit = sourceCommit,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            notBefore = notBefore,
            rolloutBasisPoints = rollout,
            minCurrentVersionCode = minCurrent,
        )
    }

    private fun isSafeRelativePath(path: String): Boolean =
        relativePathPattern.matches(path) &&
            !path.startsWith('/') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun decodeBase64(raw: String, label: String): ByteArray {
        val compact = raw.filterNot(Char::isWhitespace)
        require(compact.isNotEmpty() && compact.length <= 4_096) { "Invalid $label." }
        return runCatching { Base64.getDecoder().decode(compact) }
            .getOrElse { throw IllegalArgumentException("Invalid $label.") }
    }
}

object ReleaseOriginPolicy {
    fun validateOrigin(raw: String): URI {
        val origin = runCatching { URI(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("Release origin is invalid.")
        }
        require(origin.scheme.equals("https", ignoreCase = true)) { "Release origin must use HTTPS." }
        require(!origin.host.isNullOrBlank() && origin.userInfo == null) { "Release origin host is invalid." }
        require(origin.port == -1 || origin.port == 443) { "Release origin must use the standard HTTPS port." }
        require('%' !in origin.rawPath.orEmpty() && '\\' !in origin.rawPath.orEmpty()) {
            "Release origin path must not use encoded or backslash separators."
        }
        val host = origin.host.lowercase(Locale.ROOT)
        require(
            '.' in host &&
                host != "localhost" &&
                !host.endsWith(".local") &&
                !Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(host) &&
                ':' !in host,
        ) { "Release origin must use a public DNS hostname, not a local or numeric address." }
        require(origin.query == null && origin.fragment == null) { "Release origin cannot contain a query." }
        return ensureDirectory(origin.normalize())
    }

    fun resolve(origin: URI, relativePath: String): URI {
        require(!relativePath.startsWith('/') && '%' !in relativePath && '\\' !in relativePath) {
            "Release path must be a plain relative path."
        }
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Release path traversal is forbidden."
        }
        val base = ensureDirectory(origin.normalize())
        val resolved = base.resolve(relativePath).normalize()
        val basePath = base.rawPath.orEmpty().ifEmpty { "/" }
        val resolvedPath = resolved.rawPath.orEmpty()
        require(
            resolved.scheme.equals(base.scheme, true) &&
                resolved.host.equals(base.host, true) &&
                resolved.port == base.port &&
                resolvedPath.startsWith(basePath),
        ) { "Release path escaped the pinned origin." }
        return resolved
    }

    private fun ensureDirectory(uri: URI): URI {
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (path.endsWith('/')) return if (uri.rawPath == path) uri else URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            path,
            null,
            null,
        )
        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "$path/",
            null,
            null,
        )
    }
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun JSONObject.requireString(key: String, maxLength: Int): String {
    require(has(key) && get(key) is String) { "Missing release field: $key" }
    return getString(key).also { require(it.isNotBlank() && it.length <= maxLength) }
}

private fun JSONObject.requireInt(key: String): Int {
    require(has(key) && get(key) is Number) { "Missing release field: $key" }
    return getInt(key)
}

private fun JSONObject.requireLong(key: String): Long {
    require(has(key) && get(key) is Number) { "Missing release field: $key" }
    return getLong(key)
}

private fun JSONObject.requireInstant(key: String): Instant = runCatching {
    Instant.parse(requireString(key, 40))
}.getOrElse { throw IllegalArgumentException("Invalid release timestamp: $key") }
