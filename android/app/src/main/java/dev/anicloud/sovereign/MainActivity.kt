package dev.anicloud.sovereign.prototype

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.anicloud.sovereign.prototype.ui.AniCloudApp
import dev.anicloud.sovereign.prototype.ui.LockedSurface

private sealed interface AuthenticationState {
    data object Locked : AuthenticationState
    data object Prompting : AuthenticationState
    data object Unlocked : AuthenticationState
    data class Unavailable(val detail: String) : AuthenticationState
}

class MainActivity : ComponentActivity() {
    private val grace = AuthenticationGrace()
    private var authenticationState: AuthenticationState by mutableStateOf(AuthenticationState.Locked)
    private var cancellationSignal: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            when (val state = authenticationState) {
                AuthenticationState.Unlocked -> AniCloudApp(onLock = ::lockNow)
                AuthenticationState.Locked,
                AuthenticationState.Prompting -> LockedSurface(
                    isPrompting = state is AuthenticationState.Prompting,
                    detail = "Biometric or device credential required",
                    onAuthenticate = ::requestAuthentication,
                )

                is AuthenticationState.Unavailable -> LockedSurface(
                    isPrompting = false,
                    detail = state.detail,
                    onAuthenticate = ::requestAuthentication,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (grace.needsAuthentication(SystemClock.elapsedRealtime())) {
            authenticationState = AuthenticationState.Locked
            window.decorView.post(::requestAuthentication)
        } else {
            authenticationState = AuthenticationState.Unlocked
        }
    }

    override fun onStop() {
        grace.markBackgrounded(SystemClock.elapsedRealtime())
        super.onStop()
    }

    override fun onDestroy() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        super.onDestroy()
    }

    private fun lockNow() {
        grace.lock()
        authenticationState = AuthenticationState.Locked
        requestAuthentication()
    }

    private fun requestAuthentication() {
        if (isFinishing || isDestroyed || authenticationState is AuthenticationState.Prompting) return

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = getSystemService(BiometricManager::class.java)
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            authenticationState = AuthenticationState.Unavailable(
                "Set a fingerprint, PIN, or password in Android before opening Intermix.",
            )
            return
        }

        authenticationState = AuthenticationState.Prompting
        cancellationSignal?.cancel()
        cancellationSignal = CancellationSignal()
        BiometricPrompt.Builder(this)
            .setTitle("Unlock AniCloudAI")
            .setSubtitle("Sovereign Core remains inside this device")
            .setAllowedAuthenticators(authenticators)
            .build()
            .authenticate(
                cancellationSignal!!,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        grace.markAuthenticated()
                        authenticationState = AuthenticationState.Unlocked
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authenticationState = AuthenticationState.Unavailable(errString.toString())
                    }
                },
            )
    }
}
