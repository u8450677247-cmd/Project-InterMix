package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CognitionProviderTest {
    private fun provider(
        id: String,
        locality: CognitionLocality,
        toolCalling: Boolean,
        network: Boolean,
        thermal: CognitionThermalCost = CognitionThermalCost.Low,
    ) = DeclaredCognitionProvider(
        id = id,
        capabilities = CognitionCapabilities(
            toolCalling = toolCalling,
            vision = false,
            contextCapacity = 16_384,
            structuredGeneration = true,
            codingStrength = CognitionStrength.Strong,
            reasoningStrength = CognitionStrength.Strong,
            locality = locality,
            privacyClass = when (locality) {
                CognitionLocality.Device -> CognitionPrivacyClass.DevicePrivate
                CognitionLocality.Home -> CognitionPrivacyClass.TrustedHome
                CognitionLocality.Cloud -> CognitionPrivacyClass.External
            },
            marginalCostMicros = 0,
            expectedLatencyMillis = 100,
            networkRequired = network,
            availableBackends = setOf("TEST"),
            thermalCost = thermal,
        ),
    )

    @Test
    fun offlineToolRequestRejectsNetworkAndNonToolProviders() {
        val selected = CognitionRoutingPolicy.select(
            candidates = listOf(
                CognitionCandidate(provider("device.plain", CognitionLocality.Device, false, false), "plain"),
                CognitionCandidate(provider("cloud.tools", CognitionLocality.Cloud, true, true), "cloud"),
                CognitionCandidate(provider("device.tools", CognitionLocality.Device, true, false), "device"),
            ),
            request = CognitionRequest(toolCalling = true, offline = true),
        )

        assertEquals("device", selected?.payload)
    }

    @Test
    fun preferenceCannotOverridePrivacyOrThermalRequirements() {
        val cloud = provider("cloud.frontier", CognitionLocality.Cloud, true, true)
        val hot = provider(
            "device.hot",
            CognitionLocality.Device,
            true,
            false,
            CognitionThermalCost.High,
        )
        val selected = CognitionRoutingPolicy.select(
            candidates = listOf(CognitionCandidate(cloud, "cloud"), CognitionCandidate(hot, "hot")),
            request = CognitionRequest(
                toolCalling = true,
                maximumPrivacyClass = CognitionPrivacyClass.DevicePrivate,
                maximumThermalCost = CognitionThermalCost.Moderate,
                preferredProviderIds = listOf("cloud.frontier", "device.hot"),
            ),
        )

        assertNull(selected)
    }
}
