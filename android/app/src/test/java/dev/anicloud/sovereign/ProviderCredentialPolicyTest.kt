package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialPolicyTest {
    @Test
    fun providerAllowlistIsStableAndSearchScoped() {
        assertEquals(
            listOf("brave", "tavily", "exa", "serpapi", "tinyfish", "github"),
            ProviderCredential.entries.map { it.wireName },
        )
        assertTrue(ProviderCredential.entries.all { it.displayName.isNotBlank() && it.role.isNotBlank() })
    }

    @Test
    fun secretValidationRejectsBlankMultilineAndOversizedValues() {
        assertTrue(runCatching { ProviderCredentialPolicy.validate("") }.isFailure)
        assertTrue(runCatching { ProviderCredentialPolicy.validate(" leading") }.isFailure)
        assertTrue(runCatching { ProviderCredentialPolicy.validate("line\nbreak") }.isFailure)
        assertTrue(
            runCatching {
                ProviderCredentialPolicy.validate("x".repeat(ProviderCredentialPolicy.MaximumSecretBytes + 1))
            }.isFailure,
        )
    }

    @Test
    fun validSecretIsEncodedWithoutModification() {
        val value = "provider-token_123"
        val encoded = ProviderCredentialPolicy.validate(value)
        assertEquals(value, encoded.toString(Charsets.UTF_8))
        encoded.fill(0)
    }
}
