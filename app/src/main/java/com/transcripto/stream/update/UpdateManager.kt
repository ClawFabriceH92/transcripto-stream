package com.transcripto.stream.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Boucle de mise à jour automatique (pattern validé Vigie, générique).
 * - Vérification immédiate au lancement
 * - Puis créneau quotidien à 14h00 (boucle légère toutes les 30 s)
 * - Téléchargement auto si permission "installer des apps inconnues" OK,
 *   sinon notification avec action vers l'écran d'autorisation.
 * - Activable/désactivable via SharedPreferences ("autoUpdate", défaut true).
 */
object UpdateManager {

    private const val PREFS = "transcripto-streamupdate"
    private const val KEY_AUTO = "autoUpdate"
    private const val CHANNEL_ID = "com.transcripto.stream.updates"
    private const val TAG = "Transcripto StreamUpdate"

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun autoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    /** À appeler une fois depuis le onCreate de l'activité principale. */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        ensureChannel(appContext)
        scope.launch {
            // Vérification immédiate au lancement
            if (autoUpdateEnabled(appContext)) checkOnce(appContext)
            while (isActive) {
                val now = Calendar.getInstance()
                if (autoUpdateEnabled(appContext) &&
                    now.get(Calendar.HOUR_OF_DAY) == 14 && now.get(Calendar.MINUTE) == 0
                ) {
                    checkOnce(appContext)
                    delay(61_000) // évite les doubles déclenchements dans la même minute
                } else {
                    delay(30_000)
                }
            }
        }
    }

    /** Vérifie GitHub Releases et télécharge si une MAJ existe. Peut être appelé par un bouton "Vérifier maintenant". */
    fun checkNow(context: Context) {
        checkOnce(context.applicationContext)
    }

    /**
     * Vérification manuelle avec retour utilisateur (bouton « Vérifier maintenant »
     * des Réglages) : retourne le message à afficher en snackbar.
     */
    suspend fun checkNowAndReport(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val info = UpdateChecker.latestWithApk()
            ?: return@withContext "Vérification impossible (réseau indisponible ?)"
        val current = currentVersion(appContext)
        when {
            UpdateChecker.compareVersions(info.versionName, current) <= 0 ->
                "Vous êtes à jour (v$current)"
            AutoUpdater.canRequestInstalls(appContext) -> {
                AutoUpdater.download(appContext, info.downloadUrl)
                "v${info.versionName} disponible — téléchargement lancé, installation automatique à la fin"
            }
            else -> {
                notifyPermissionNeeded(appContext, info)
                "v${info.versionName} disponible — autorise d'abord l'installation d'apps inconnues"
            }
        }
    }

    private fun checkOnce(context: Context) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() } ?: return@launch
            val current = currentVersion(context)
            if (UpdateChecker.compareVersions(info.versionName, current) <= 0) return@launch
            if (AutoUpdater.canRequestInstalls(context)) {
                AutoUpdater.download(context, info.downloadUrl)
            } else {
                notifyPermissionNeeded(context, info)
            }
        }
    }

    private fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mises à jour",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Mises à jour automatiques de Transcripto Stream" }
        )
    }

    /** Notification : MAJ dispo mais il faut d'abord autoriser l'installation. */
    private fun notifyPermissionNeeded(context: Context, info: UpdateInfo) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("⬆ Mise à jour Transcripto Stream v${info.versionName} disponible")
                .setContentText("Touchez pour autoriser l'installation, puis la mise à jour s'installera automatiquement.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notification)
        } catch (_: Exception) {
            // Notification impossible → on laisse tomber silencieusement
        }
    }
}
