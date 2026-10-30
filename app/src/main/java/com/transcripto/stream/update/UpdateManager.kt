package com.transcripto.stream.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mise à jour téléchargée par l'application elle-même, et non par
 * DownloadManager.
 *
 * Même mécanique que Note de frais : GitHub sert l'APK depuis un CDN après une
 * chaîne de redirections que DownloadManager ne suit pas toujours, d'où des
 * fichiers tronqués que l'installeur refuse (« problème lors de l'analyse du
 * package »). Ici le fichier est récupéré en direct, sa taille et son en-tête
 * « PK » sont vérifiés, puis l'installation est proposée en un geste.
 */
object UpdateManager {

    sealed interface State {
        data object Idle : State
        data class Available(val version: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class ReadyToInstall(val version: String, val file: File) : State
        data class UpToDate(val version: String) : State
        data class Failed(val detail: String?) : State
    }

    private const val PREFS = "transcripto-stream_update"
    private const val KEY_AUTO = "autoUpdate"
    private const val DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---- Réglage « mise à jour automatique » ----

    fun autoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    /** À appeler une fois depuis MainActivity : vérification au lancement, puis horaire. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            while (true) {
                if (autoUpdateEnabled(appContext)) check(appContext, manual = false)
                delay(15 * 60_000L)
            }
        }
    }

    // ---- Vérification ----

    @Volatile
    private var lastCheckAt = UpdateSchedule.NEVER

    private suspend fun check(context: Context, manual: Boolean) {
        if (job?.isActive == true) return
        val maintenant = System.currentTimeMillis()
        if (!UpdateSchedule.isDue(manual, lastCheckAt, maintenant)) return
        lastCheckAt = maintenant
        val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() }
        if (info == null) {
            // Vérification impossible : ne pas bloquer le créneau suivant
            lastCheckAt = UpdateSchedule.NEVER
            if (manual) _state.value = State.Failed("Vérification impossible (réseau indisponible ?)")
            return
        }
        val installee = versionInstallee(context)
        if (UpdateChecker.compareVersions(info.versionName, installee) <= 0) {
            _state.value = if (manual) State.UpToDate(installee) else State.Idle
            return
        }
        _state.value = State.Available(info.versionName, info.notes.orEmpty(), info.downloadUrl)
        // Une nouvelle version n'impose pas de dialogue : sur un réseau non
        // facturé l'APK est récupéré tout de suite, « Installer » attend ensuite.
        if (!manual) preparerEnSilence(context, info.versionName, info.downloadUrl)
    }

    /** Bouton « Vérifier maintenant » : même boucle, avec un retour utilisateur. */
    suspend fun checkNowAndReport(context: Context): String {
        val appContext = context.applicationContext
        val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() }
            ?: return "Vérification impossible (réseau indisponible ?)"
        val installee = versionInstallee(appContext)
        if (UpdateChecker.compareVersions(info.versionName, installee) <= 0) {
            _state.value = State.UpToDate(installee)
            return "À jour (v$installee)"
        }
        _state.value = State.Available(info.versionName, info.notes.orEmpty(), info.downloadUrl)
        if (!canInstall(appContext)) {
            openInstallSettings(appContext)
            return "v${info.versionName} disponible : autorise d'abord l'installation d'apps inconnues"
        }
        _state.value = State.Downloading(info.versionName, 0f)
        val resultat = runCatching { fetch(appContext, info.versionName, info.downloadUrl) }
        return resultat.fold(
            onSuccess = { fichier ->
                _state.value = State.ReadyToInstall(info.versionName, fichier)
                "v${info.versionName} téléchargée : touche « Installer la mise à jour »"
            },
            onFailure = { erreur ->
                _state.value = State.Failed(erreur.message)
                "Téléchargement impossible : ${erreur.message ?: "erreur réseau"}"
            },
        )
    }

    private suspend fun preparerEnSilence(context: Context, version: String, url: String) {
        val dejaLa = fichierValide(context, version)
        if (dejaLa != null) {
            _state.value = State.ReadyToInstall(version, dejaLa)
            return
        }
        if (!reseauNonFacture(context)) return
        _state.value = State.Downloading(version, 0f)
        val resultat = runCatching { fetch(context, version, url) }
        _state.value = resultat.fold(
            onSuccess = { State.ReadyToInstall(version, it) },
            onFailure = { State.Available(version, "", url) },
        )
    }

    /** Téléchargement en Wi-Fi seulement : tirer 60 Mo de données mobiles sans prévenir n'est pas un service. */
    private fun reseauNonFacture(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val reseau = cm.activeNetwork ?: return false
        val capacites = cm.getNetworkCapabilities(reseau) ?: return false
        capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /** L'APK déjà présent pour [version], s'il s'agit vraiment d'un APK. */
    private fun fichierValide(context: Context, version: String): File? {
        val fichier = File(File(context.filesDir, DIR), "transcripto-stream-v$version.apk")
        if (!fichier.exists() || fichier.length() == 0L) return null
        val entete = ByteArray(2)
        runCatching { fichier.inputStream().use { it.read(entete) } }.getOrNull()
        return if (String(entete) == "PK") fichier else null
    }

    /** Version installée, lue au package (le module n'a pas de BuildConfig). */
    private fun versionInstallee(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Téléchargement demandé explicitement (bouton « Télécharger »). */
    fun download(context: Context) {
        val disponible = _state.value as? State.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Downloading(disponible.version, 0f)
            val resultat = runCatching { fetch(context, disponible.version, disponible.apkUrl) }
            _state.value = resultat.fold(
                onSuccess = { State.ReadyToInstall(disponible.version, it) },
                onFailure = { State.Failed(it.message) },
            )
        }
    }

    private suspend fun fetch(context: Context, version: String, url: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val cible = File(dir, "transcripto-stream-v$version.apk")

            val requete = Request.Builder().url(url).get().build()
            http.newCall(requete).execute().use { reponse ->
                if (!reponse.isSuccessful) error("HTTP ${reponse.code}")
                val corps = reponse.body ?: error("Corps vide")
                val total = corps.contentLength()
                var ecrit = 0L
                corps.byteStream().use { entree ->
                    cible.outputStream().use { sortie ->
                        val tampon = ByteArray(64 * 1024)
                        var lu = entree.read(tampon)
                        while (lu != -1) {
                            sortie.write(tampon, 0, lu)
                            ecrit += lu
                            if (total > 0) {
                                _state.value = State.Downloading(
                                    version,
                                    (ecrit.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                            lu = entree.read(tampon)
                        }
                    }
                }
            }
            if (cible.length() == 0L) error("Téléchargement vide")
            // Un fichier qui ne commence pas par « PK » est une page web, pas un APK.
            val entete = ByteArray(2)
            cible.inputStream().use { it.read(entete) }
            if (String(entete) != "PK") {
                cible.delete()
                error("fichier téléchargé invalide (ce n'est pas un APK)")
            }
            cible
        }

    // ---- Installation ----

    fun install(context: Context) {
        val pret = _state.value as? State.ReadyToInstall ?: return
        AutoUpdater.install(context, pret.file)
    }

    fun canInstall(context: Context): Boolean = AutoUpdater.canRequestInstalls(context)

    fun openInstallSettings(context: Context) = AutoUpdater.openInstallSettings(context)

    fun dismiss() {
        _state.value = State.Idle
    }
}
