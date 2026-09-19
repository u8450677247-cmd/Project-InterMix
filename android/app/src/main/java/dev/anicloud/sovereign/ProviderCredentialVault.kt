package dev.anicloud.sovereign.prototype

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Search credentials accepted by the native vault. Values are never model- or UI-readable. */
enum class ProviderCredential(
    val wireName: String,
    val displayName: String,
    val role: String,
) {
    Brave("brave", "Brave Search", "Independent broad web index"),
    Tavily("tavily", "Tavily", "Research-oriented retrieval"),
    Exa("exa", "Exa", "Semantic discovery and page content"),
    SerpApi("serpapi", "SerpAPI", "Broad search-engine fallback"),
    TinyFish("tinyfish", "TinyFish", "Difficult live-page search and fetch"),
    GitHub("github", "GitHub", "Repository and release metadata"),
}

data class ProviderVaultSnapshot(
    val configured: Set<ProviderCredential> = emptySet(),
) {
    val configuredCount: Int get() = configured.size
    val ready: Boolean get() = configured.isNotEmpty()
}

object ProviderCredentialPolicy {
    const val MaximumSecretBytes = 8 * 1024

    fun validate(secret: String): ByteArray {
        require(secret.isNotEmpty()) { "A provider credential is required." }
        require(secret == secret.trim()) { "Remove leading or trailing whitespace." }
        require('\u0000' !in secret && '\n' !in secret && '\r' !in secret) {
            "Provider credentials must be one line."
        }
        val encoded = secret.toByteArray(StandardCharsets.UTF_8)
        if (encoded.size > MaximumSecretBytes) {
            encoded.fill(0)
            throw IllegalArgumentException(
                "Provider credential exceeds $MaximumSecretBytes UTF-8 bytes.",
            )
        }
        return encoded
    }
}

/**
 * Keystore-backed write-only provider credential drop.
 *
 * Android Keystore owns the non-exportable AES key. App-private preferences contain only an IV,
 * authenticated ciphertext, and non-secret configuration timestamps. There is intentionally no
 * public read/decrypt method: the native grounding transport remains disabled until its outbound
 * data review and provider adapters land.
 */
class ProviderCredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun snapshot(): ProviderVaultSnapshot = ProviderVaultSnapshot(
        configured = ProviderCredential.entries
            .filterTo(linkedSetOf()) {
                validEnvelope(preferences.getString(ciphertextKey(it), null))
            },
    )

    @Synchronized
    fun store(provider: ProviderCredential, secret: String) {
        val plaintext = ProviderCredentialPolicy.validate(secret)
        try {
            val cipher = Cipher.getInstance(CipherTransformation)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = listOf(
                EnvelopeVersion,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ).joinToString(EnvelopeSeparator)
            check(
                preferences.edit()
                    .putString(ciphertextKey(provider), envelope)
                    .putLong(updatedKey(provider), System.currentTimeMillis())
                    .commit(),
            ) { "The provider vault could not commit encrypted storage." }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun remove(provider: ProviderCredential) {
        check(
            preferences.edit()
                .remove(ciphertextKey(provider))
                .remove(updatedKey(provider))
                .commit(),
        ) { "The provider vault could not commit credential removal." }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ciphertextKey(provider: ProviderCredential): String =
        "credential_${provider.wireName}_ciphertext"

    private fun updatedKey(provider: ProviderCredential): String =
        "credential_${provider.wireName}_updated_ms"

    private fun validEnvelope(raw: String?): Boolean {
        val parts = raw?.split(EnvelopeSeparator) ?: return false
        if (parts.size != 3 || parts[0] != EnvelopeVersion) return false
        return runCatching {
            Base64.decode(parts[1], Base64.NO_WRAP).size == 12 &&
                Base64.decode(parts[2], Base64.NO_WRAP).size >= 16
        }.getOrDefault(false)
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val KeyAlias = "intermix-provider-vault-v1"
        const val PreferencesName = "intermix_provider_credentials_v1"
        const val EnvelopeVersion = "v1"
        const val EnvelopeSeparator = ":"
    }
}
