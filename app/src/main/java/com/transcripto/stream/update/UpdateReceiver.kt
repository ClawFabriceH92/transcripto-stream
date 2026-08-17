package com.transcripto.stream.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reçoit la fin du téléchargement DownloadManager et installe l'APK
 * si c'est bien celui attendu (id mémorisé) et que la permission
 * "installer des apps inconnues" est accordée.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val expected = AutoUpdater.lastDownloadId(context)
        if (received != expected) return
        if (!AutoUpdater.isDownloadComplete(context, received)) {
            Log.w(TAG, "Téléchargement de mise à jour en échec (id=$received)")
            return
        }
        if (!AutoUpdater.installDownloaded(context)) {
            Log.w(TAG, "Installation impossible (permission ou fichier manquant)")
        }
    }

    private companion object {
        const val TAG = "TranscriptostreamUpdate"
    }
}
