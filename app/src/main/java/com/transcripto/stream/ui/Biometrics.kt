package com.transcripto.stream.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Déverrouillage par BiometricPrompt : empreinte, visage ou, à défaut, code de
 * l'appareil. Alternative au PIN de l'app (qui reste disponible derrière).
 */
object Biometrics {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Vrai si l'appareil peut authentifier (capteur enrôlé ou verrouillage d'écran configuré). */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** L'activité hôte (FragmentActivity) derrière un contexte Compose, ou null. */
    fun hostActivity(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Affiche l'invite. [onError] reçoit null quand l'utilisateur a simplement annulé
     * (retour au PIN sans message), un libellé sinon.
     */
    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String?) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val silent = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (silent) null else errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller Transcripto Stream")
            .setSubtitle("Empreinte, visage ou code de l'appareil")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (e: Exception) {
            onError(e.message ?: "Authentification indisponible")
        }
    }
}
