package com.transcripto.stream

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Tuile « Transcrire » du volet de réglages rapides : ouvre l'app et lance
 * l'enregistrement en un geste, sans chercher l'icône du launcher.
 */
class RecordTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchRecord() }
        } else {
            launchRecord()
        }
    }

    private fun launchRecord() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_RECORD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
