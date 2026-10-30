package com.transcripto.stream.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Les deux passages système dont la mise à jour a besoin : autoriser
 * l'installation d'apps inconnues, puis ouvrir l'installeur sur le fichier
 * téléchargé. Le téléchargement lui-même est fait par [UpdateManager].
 */
object AutoUpdater {

    /** L'app a le droit d'installer un APK (permission « installer des apps inconnues »). */
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
            // Téléphones exotiques : repli sur les réglages généraux
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** Ouvre l'installeur Android sur l'APK téléchargé (partage via FileProvider). */
    fun install(context: Context, file: File) {
        if (!file.exists() || file.length() == 0L) return
        runCatching {
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
        }
    }
}
