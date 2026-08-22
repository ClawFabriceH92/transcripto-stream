package com.transcripto.stream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.start(this)
        enableEdgeToEdge()
        // Uniquement au premier lancement réel : une rotation (savedInstanceState) ou un
        // retour par les Récents (LAUNCHED_FROM_HISTORY) ne doit pas relancer le micro.
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
        if (intent?.action == ACTION_RECORD &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) {
            recordRequest.value = true
        }
    }
}
