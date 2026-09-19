package dev.anicloud.sovereign.prototype

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Narrow platform state consumed by the LIBRARIAN-01 health adapter.
 *
 * Endpoint probes are deliberately injected: ConnectivityManager can describe
 * transports, validation, VPN, and metering, but it cannot truthfully claim that
 * a particular NAS or Cortex service answered an authenticated bounded probe.
 */
data class LibrarianNetworkSnapshot(
    val lanReachable: Boolean? = null,
    val nasReachable: Boolean? = null,
    val cortexReachable: Boolean? = null,
    val wifiInternetValidated: Boolean? = null,
    val cellularInternetAvailable: Boolean? = null,
    val activeWan: String = "unknown",
    val metered: Boolean? = null,
    val secureOverlay: Boolean? = null,
)

data class LibrarianEndpointSnapshot(
    val nasReachable: Boolean? = null,
    val cortexReachable: Boolean? = null,
)

fun interface LibrarianEndpointProbe {
    /** Return cached results from bounded authenticated probes; never block this call on I/O. */
    fun cachedSnapshot(): LibrarianEndpointSnapshot
}

object UnknownLibrarianEndpointProbe : LibrarianEndpointProbe {
    override fun cachedSnapshot(): LibrarianEndpointSnapshot = LibrarianEndpointSnapshot()
}

class AndroidLibrarianNetworkSentinel(
    context: Context,
    private val endpointProbe: LibrarianEndpointProbe = UnknownLibrarianEndpointProbe,
) {
    private val connectivity = context.applicationContext.getSystemService(
        ConnectivityManager::class.java,
    )

    fun snapshot(): LibrarianNetworkSnapshot {
        val endpoints = endpointProbe.cachedSnapshot()
        return try {
            val networks = connectivity.allNetworks.mapNotNull(connectivity::getNetworkCapabilities)
            val active = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            val wifi = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            val ethernet = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            val wifiValidated = networks.any {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val cellularValidated = networks.any {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val activeWan = when {
                active == null -> "none"
                active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "overlay"
                else -> "other"
            }
            LibrarianNetworkSnapshot(
                lanReachable = wifi || ethernet,
                nasReachable = endpoints.nasReachable,
                cortexReachable = endpoints.cortexReachable,
                wifiInternetValidated = wifiValidated,
                cellularInternetAvailable = cellularValidated,
                activeWan = activeWan,
                metered = connectivity.isActiveNetworkMetered,
                secureOverlay = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            )
        } catch (_: SecurityException) {
            LibrarianNetworkSnapshot(
                nasReachable = endpoints.nasReachable,
                cortexReachable = endpoints.cortexReachable,
            )
        }
    }
}
