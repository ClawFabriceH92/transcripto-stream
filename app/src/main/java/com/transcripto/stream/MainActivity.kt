package com.transcripto.stream

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.transcripto.stream.ui.StreamScreen
import com.transcripto.stream.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_RECORD = "com.transcripto.stream.action.RECORD"

        /**
         * Demande de démarrage immédiat (App Shortcut ou tuile de réglages rapides),
         * consommée par StreamScreen une fois les permissions en place.
         */
        val recordRequest = MutableStateFlow(false)

        /**
         * Audio partagé vers l'app (ACTION_SEND/VIEW) : URI à importer,
         * consommée par StreamScreen (après déverrouillage PIN le cas échéant).
         */
        val importRequest = MutableStateFlow<Uri?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.start(this)
        enableEdgeToEdge()
        // Uniquement au premier lancement réel : une rotation (savedInstanceState) ou un
        // retour par les Récents (LAUNCHED_FROM_HISTORY) ne doit pas rejouer l'action.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            StreamScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        when (intent.action) {
            ACTION_RECORD -> recordRequest.value = true
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                if (uri != null) importRequest.value = uri
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) importRequest.value = uri
            }
        }
    }
}
