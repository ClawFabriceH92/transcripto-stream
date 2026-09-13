package com.transcripto.stream

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal local des plantages : sans boutique d'applications, c'est le seul
 * moyen de retrouver la cause exacte d'un incident. Chaque entrée = date,
 * version, thread, trace (tronquée). Consultable, partageable et effaçable
 * depuis Réglages → À propos. Aucune donnée n'est envoyée nulle part.
 */
object CrashLog {

    const val FILE = "crash.log"
    /** Taille maximale du journal, en caractères (≈ 200 à 400 Ko sur disque). */
    private const val MAX_CHARS = 200_000
    /** Tête de trace conservée intégralement ; au-delà, seules les lignes « Caused by » (la cause racine). */
    private const val HEAD_LINES = 30
    private const val SEPARATOR = "=== "

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(app, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    private fun append(context: Context, thread: Thread, t: Throwable) {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
        val lines = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().lines()
        val trace = (lines.take(HEAD_LINES) + lines.drop(HEAD_LINES).filter { it.startsWith("Caused by") })
            .joinToString("\n")
        val entry = buildString {
            append(SEPARATOR).append(stamp).append(" · v").append(version)
                .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" · thread ").append(thread.name).append('\n')
            append(trace).append("\n\n")
        }
        val f = File(context.filesDir, FILE)
        val existing = if (f.exists()) f.readText() else ""
        f.writeText((entry + existing).take(MAX_CHARS))
    }

    fun read(context: Context): String = try {
        val f = File(context.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) {
        ""
    }

    fun count(context: Context): Int = read(context).lines().count { it.startsWith(SEPARATOR) }

    /** Fichier du journal (partage via FileProvider) ou null s'il n'existe pas. */
    fun file(context: Context): File? = File(context.filesDir, FILE).takeIf { it.exists() && it.length() > 0 }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
    }
}
