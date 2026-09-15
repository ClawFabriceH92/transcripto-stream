package com.transcripto.stream

import android.app.Application
import com.transcripto.stream.data.CryptoManager
import com.transcripto.stream.data.TextVault

/**
 * Point d'entrée de l'application : installe le journal local des plantages et
 * branche la clé AndroidKeyStore sur le coffre des textes (avant tout accès aux fichiers).
 */
class TranscriptoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        TextVault.keyProvider = CryptoManager
    }
}
