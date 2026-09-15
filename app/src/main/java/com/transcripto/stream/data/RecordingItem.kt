package com.transcripto.stream.data

import java.io.File

/** Un enregistrement listé : WAV, .enc chiffré, ou .txt seul (transcription Google). */
data class RecordingItem(
    val file: File,
    val baseName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAt: Long,
    val encrypted: Boolean,
    val hasAudio: Boolean,
    val transcript: String,
    val dossier: String = "",
    val speakerNames: Map<Int, String> = emptyMap(),
    /** Identifiant du gabarit de synthèse (vide = Réunion). */
    val template: String = "",
    val chapters: List<Chapter> = emptyList(),
    /** Segments horodatés non synchronisés avec le texte corrigé (relancer « Transcrire »). */
    val segmentsStale: Boolean = false,
    /** Actions suivies (issues de la synthèse ou saisies). */
    val actions: List<ActionItem> = emptyList(),
) {
    val openActionCount: Int get() = actions.count { !it.done }
}

/** Une action ouverte d'un dossier, avec l'enregistrement qui la porte. */
data class DossierAction(val file: File, val baseName: String, val action: ActionItem)

/** Résultat d'une correction manuelle de la transcription. */
data class EditOutcome(
    /** Faux si le scellement demandé a échoué (texte conservé en clair). */
    val sealed: Boolean,
    /** Les segments n'ont pas pu être réalignés sur le texte corrigé. */
    val segmentsStale: Boolean,
)

/** Liste des enregistrements et espace total occupé par le dossier (WAV + textes). */
data class RecordingListing(val items: List<RecordingItem>, val totalBytes: Long)

/** Issue d'un renommage (dialog de fin d'enregistrement, liste). */
sealed interface RenameResult {
    /** Renommé : [dest] est le nouveau fichier principal (mêmes frères renommés). */
    data class Renamed(val dest: File) : RenameResult
    /** Nom vide, interdit ou identique : rien à faire. */
    data object Unchanged : RenameResult
    /** Un enregistrement porte déjà ce nom (tous types confondus). */
    data object Clash : RenameResult
    /** Le système de fichiers a refusé le renommage. */
    data object Failed : RenameResult
}
