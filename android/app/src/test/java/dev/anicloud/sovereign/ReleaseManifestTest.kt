package dev.anicloud.sovereign.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

class ReleaseManifestTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")

    @Test
    fun signedManifestAuthenticatesBeforeEligibility() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val bytes = manifestBytes()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }
        val manifest = ReleaseManifestVerifier.verifyAndParse(
            manifestBytes = bytes,
            detachedSignatureBase64 = Base64.getEncoder().encode(signature),
            publicKeyBase64 = Base64.getEncoder().encodeToString(keys.public.encoded),
            expectedPackageName = "dev.anicloud.sovereign.prototype",
            now = now,
        )

        assertEquals(23L, manifest.versionCode)
        assertTrue(manifest.eligible(currentVersionCode = 22, cohortBasisPoints = 2_499, now = now))
        assertFalse(manifest.eligible(currentVersionCode = 22, cohortBasisPoints = 2_500, now = now))
        assertFalse(manifest.eligible(currentVersionCode = 23, cohortBasisPoints = 0, now = now))
    }

    @Test
    fun oneFileEnvelopePreservesExactSignedBytes() {
        val manifest = manifestBytes()
        val signature = ByteArray(64) { it.toByte() }
        val envelope = JSONObject()
            .put("schema", 1)
            .put("manifest_b64", Base64.getEncoder().encodeToString(manifest))
            .put("signature_b64", Base64.getEncoder().encodeToString(signature))
            .toString()
            .toByteArray()

        val decoded = ReleaseEnvelope.decode(envelope)

        assertTrue(decoded.first.contentEquals(manifest))
        assertTrue(Base64.getDecoder().decode(decoded.second).contentEquals(signature))
    }

    @Test(expected = IllegalArgumentException::class)
    fun changedManifestCannotReuseTheSignature() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val original = manifestBytes()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(original)
            sign()
        }
        val changed = original.toString(Charsets.UTF_8)
            .replace("\"version_code\":23", "\"version_code\":24")
            .toByteArray()

        ReleaseManifestVerifier.verifyAndParse(
            manifestBytes = changed,
            detachedSignatureBase64 = Base64.getEncoder().encode(signature),
            publicKeyBase64 = Base64.getEncoder().encodeToString(keys.public.encoded),
            expectedPackageName = "dev.anicloud.sovereign.prototype",
            now = now,
        )
    }

    @Test
    fun relativeReleasePathsStayUnderThePinnedHttpsOrigin() {
        val origin = ReleaseOriginPolicy.validateOrigin("https://updates.example.test/intermix")
        assertEquals(
            "https://updates.example.test/intermix/apk/release.apk",
            ReleaseOriginPolicy.resolve(origin, "apk/release.apk").toString(),
        )
        assertTrue(runCatching { ReleaseOriginPolicy.resolve(origin, "../escape.apk") }.isFailure)
        assertTrue(runCatching { ReleaseOriginPolicy.validateOrigin("http://updates.example.test") }.isFailure)
        assertTrue(runCatching { ReleaseOriginPolicy.validateOrigin("https://192.0.2.10/intermix") }.isFailure)
        assertTrue(runCatching { ReleaseOriginPolicy.validateOrigin("https://updates.example.test:8443") }.isFailure)
        assertTrue(runCatching {
            ReleaseOriginPolicy.validateOrigin("https://updates.example.test/intermix%2fescape")
        }.isFailure)
    }

    private fun manifestBytes(): ByteArray = JSONObject()
        .put("schema", 1)
        .put("channel", "community")
        .put("release_id", "community-23")
        .put("package_name", "dev.anicloud.sovereign.prototype")
        .put("version_code", 23)
        .put("version_name", "0.9.0")
        .put("apk_path", "apk/abc.apk")
        .put("apk_bytes", 47_000_000)
        .put("apk_sha256", "a".repeat(64))
        .put("apk_signer_sha256", "b".repeat(64))
        .put("source_commit", "c".repeat(40))
        .put("issued_at", "2026-09-17T10:00:00Z")
        .put("expires_at", "2026-09-24T10:00:00Z")
        .put("not_before", "2026-09-17T11:00:00Z")
        .put("rollout_basis_points", 2_500)
        .put("min_current_version_code", 20)
        .toString()
        .toByteArray()
}
