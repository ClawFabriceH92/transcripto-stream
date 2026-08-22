package com.transcripto.stream.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Téléchargement (DownloadManager) + installation (FileProvider) de l'APK.
 * Nécessite la permission système "installer des apps inconnues" (REQUEST_INSTALL_PACKAGES).
 */
object AutoUpdater {

    private const val PREFS = "transcripto-stream_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val APK_NAME = "transcripto-stream-update.apk"

    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)

    fun lastDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun canRequestInstalls(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        // SecurityException si REQUEST_INSTALL_PACKAGES manque (ou OEM restrictif) :
        // répondre « non autorisé » plutôt que de faire planter l'appelant.
        false
    }

    /** Ouvre l'écran système qui autorise l'installation par cette app. */
    fun openInstallSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Téléphones exotiques : fallback sur les réglages généraux
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** Lance le téléchargement en arrière-plan (notification native DownloadManager). */
    fun download(context: Context, url: String): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        apkFile(context).delete() // nettoyage d'un éventuel ancien fichier
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Transcripto Stream — mise à jour")
            .setDescription("Téléchargement de la nouvelle version…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apkFile(context)))
            .setAllowedOverMetered(true)
        val id = dm.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return true
    }

    /** Vérifie si le téléchargement [id] est terminé avec succès. */
    fun isDownloadComplete(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return try {
            dm.getUriForDownloadedFile(id) != null
        } catch (_: Exception) {
            false
        }
    }

    /** Installe l'APK déjà téléchargé. Retourne false si permission manquante ou fichier absent. */
    fun installDownloaded(context: Context): Boolean {
        if (!canRequestInstalls(context)) return false
        val file = apkFile(context)
        if (!file.exists() || file.length() == 0L) return false
        return installApk(context, file)
    }

    private fun installApk(context: Context, file: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
