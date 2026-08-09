package com.transcripto.stream.data

import android.content.Context
import java.security.MessageDigest

/**
 * Réglages persistés de l'app (SharedPreferences).
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- Langue de transcription : "fr" | "en" | "auto" ----
    var language: String
        get() = prefs.getString("language", "fr") ?: "fr"
        set(v) = prefs.edit().putString("language", v).apply()

    // ---- Gain micro (amplification des échantillons avant transcription) ----
    var micGain: Float
        get() = prefs.getFloat("mic_gain", 1.0f)
        set(v) = prefs.edit().putFloat("mic_gain", v).apply()

    // ---- Vocabulaire personnalisé (termes injectés dans Whisper / hints Google) ----
    var vocabulary: String
        get() = prefs.getString("vocabulary", DEFAULT_VOCAB) ?: DEFAULT_VOCAB
        set(v) = prefs.edit().putString("vocabulary", v).apply()

    // ---- Rétention RGPD : 0 = désactivé, sinon jours de conservation ----
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 0)
        set(v) = prefs.edit().putInt("retention_days", v).apply()

    // ---- PIN (hash SHA-256, vide = désactivé) ----
    var pinHash: String
        get() = prefs.getString("pin_hash", "") ?: ""
        set(v) = prefs.edit().putString("pin_hash", v).apply()

    // ---- Chiffrement des WAV (AES-GCM, clé AndroidKeyStore) ----
    var encryptWav: Boolean
        get() = prefs.getBoolean("encrypt_wav", false)
        set(v) = prefs.edit().putBoolean("encrypt_wav", v).apply()

    // ---- Thème : "system" | "light" | "dark" ----
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    // ---- Horodatage [mm:ss] dans la transcription différée ----
    var useTimestamps: Boolean
        get() = prefs.getBoolean("use_timestamps", true)
        set(v) = prefs.edit().putBoolean("use_timestamps", v).apply()

    // ---- Vitesse de lecture du WAV ----
    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(v) = prefs.edit().putFloat("playback_speed", v).apply()

    val vocabularyList: List<String>
        get() = vocabulary.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setPin(pin: String) {
        pinHash = hashPin(pin)
    }

    fun verifyPin(pin: String): Boolean {
        val h = pinHash
        return h.isNotEmpty() && h == hashPin(pin)
    }

    fun clearPin() {
        pinHash = ""
    }

    companion object {
        val DEFAULT_VOCAB = "CAC, commissaire aux comptes, commissariat aux comptes, exercice, normes, audit, bilan, expert-comptable, comptabilité, résultat, bilan comptable, contrôle légal, mission légale"

        fun hashPin(pin: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
