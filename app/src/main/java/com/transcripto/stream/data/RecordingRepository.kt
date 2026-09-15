package com.transcripto.stream.data

import android.util.Log
import com.transcripto.stream.export.TranscriptExporter
import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.stt.StreamResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dépôt des enregistrements : un dossier plat où chaque enregistrement est un
 * fichier principal (« base.wav », « base.wav.enc » ou « base.txt » seul) entouré
 * de fichiers frères (.txt, .srt, .json, .md, .meta). Toutes les E/S texte passent
 * par [TextVault] (chiffrement au repos). Méthodes bloquantes : à appeler hors du
 * thread principal. Aucune dépendance Android hors journalisation — testable en JVM.
 */
class RecordingRepository(private val root: File) {

    companion object {
        private const val TAG = "RecordingRepo"
        /** Horodatage « [mm:ss] » (ou « [h:mm:ss] ») tel qu'écrit par [formatClock]. */
        val CLOCK_TAG = Regex("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\] ")
        val HASH_LINE = Regex("SHA-256 \\(PCM\\) : ([0-9a-f]{64})")
        private const val HEADER_END = "----\n"
        private val TXT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        /** Transcription seule d'un contenu .txt (après l'en-tête). */
        fun bodyOf(content: String): String = content.substringAfter(HEADER_END).trim()

        /** Horodatage du .txt : « mm:ss », ou « hh:mm:ss » au-delà d'une heure — le même partout. */
        fun formatClock(ms: Long): String = TranscriptExporter.formatHms(ms)

        private val SPEAKER_TAG = Regex("\\[Intervenant \\d+\\]\\s*")
        private val PAUSE_TAG = Regex("\\s*\\[pause \\d+s\\]\\s*")
        private const val STATS_START = "--- Temps de parole"

        /**
         * Texte de chaque segment relu dans un .txt corrigé à la main : possible seulement si le
         * texte porte exactement un horodatage par segment (correction ligne à ligne) ; null sinon.
         */
        fun realign(body: String, segments: List<StoredSegment>): List<StoredSegment>? {
            if (segments.isEmpty()) return null
            val text = body.substringBefore(STATS_START)
            val tags = CLOCK_TAG.findAll(text).toList()
            if (tags.size != segments.size) return null
            val out = ArrayList<StoredSegment>(segments.size)
            for ((i, m) in tags.withIndex()) {
                val end = if (i + 1 < tags.size) tags[i + 1].range.first else text.length
                val chunk = text.substring(m.range.last + 1, end)
                    .replace(SPEAKER_TAG, "")
                    .replace(PAUSE_TAG, " ")
                    .trim()
                if (chunk.isEmpty()) return null
                out += segments[i].copy(text = chunk)
            }
            return out
        }

        /** Base unique : suffixe « (2) », « (3) »… tant que [taken] contient le nom. */
        fun uniqueBase(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var i = 2
            while ("$base ($i)" in taken) i++
            return "$base ($i)"
        }
    }

    /** Dossier des enregistrements, créé au besoin. */
    val dir: File
        get() = root.apply { mkdirs() }

    // ---- Fichiers d'un enregistrement ----

    /**
     * .txt d'un enregistrement, avec repli sur l'ancienne convention v0.2.x
     * (« base.wav.enc » accompagné d'un « base.wav.txt »).
     */
    fun transcriptFileFor(audioFile: File): File {
        val txt = RecordingNames.txtSibling(audioFile)
        if (txt.exists()) return txt
        val legacy = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".txt")
        return if (legacy.exists()) legacy else txt
    }

    /** Contenu complet du .txt (en-tête compris), ou null s'il est absent ou illisible. */
    fun readTranscript(file: File): String? = try {
        val txt = transcriptFileFor(file)
        if (txt.exists()) TextVault.read(txt) else null
    } catch (e: Exception) {
        null
    }

    /** Transcription seule (sans en-tête), libellés génériques ; vide si absente ou illisible. */
    fun transcriptBody(file: File): String = readTranscript(file)?.let { bodyOf(it) } ?: ""

    /** Lecture du fichier .meta (I/O légère : quelques centaines d'octets). */
    fun readMeta(file: File): RecordingMeta = try {
        val f = RecordingNames.metaSibling(file)
        if (f.exists()) MetaCodec.fromJson(TextVault.read(f)) else RecordingMeta()
    } catch (e: Exception) {
        RecordingMeta()
    }

    fun writeMeta(file: File, meta: RecordingMeta) {
        val f = RecordingNames.metaSibling(file)
        try {
            if (meta.isEmpty) f.delete() else TextVault.write(f, MetaCodec.toJson(meta))
        } catch (e: Exception) {
            Log.e(TAG, "writeMeta: ${e.message}")
        }
    }

    /** Relit puis réécrit le .meta transformé — la relecture évite d'écraser une saisie concurrente. */
    fun updateMeta(file: File, transform: (RecordingMeta) -> RecordingMeta) {
        writeMeta(file, transform(readMeta(file)))
    }

    /** Nomme (ou dé-nomme si vide) un intervenant ; le .txt garde « [Intervenant N] ». */
    fun setSpeakerName(file: File, speaker: Int, name: String) = updateMeta(file) { meta ->
        val speakers = meta.speakers.toMutableMap()
        if (name.isBlank()) speakers.remove(speaker) else speakers[speaker] = name.trim()
        meta.copy(speakers = speakers)
    }

    /**
     * Synthèse existante (Markdown, noms d'intervenants appliqués) ou null. Une synthèse
     * rédigée avant le nommage suit ainsi les noms choisis ensuite.
     */
    fun readSummary(file: File): String? = try {
        val md = RecordingNames.mdSibling(file)
        if (md.exists()) SpeakerNames.apply(TextVault.read(md), readMeta(file).speakers) else null
    } catch (e: Exception) {
        null
    }

    fun writeSummary(file: File, markdown: String) {
        TextVault.write(RecordingNames.mdSibling(file), markdown)
    }

    /** Segments horodatés du .json (vide s'il est absent ou illisible). */
    fun readSegments(file: File): List<StoredSegment> {
        val json = RecordingNames.jsonSibling(file)
        if (!json.exists()) return emptyList()
        return try {
            SegmentsCodec.fromJson(TextVault.read(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun hasSegments(file: File): Boolean = RecordingNames.jsonSibling(file).exists()

    /** Segments du .json ; lève si le fichier est illisible (clé perdue, fichier altéré) — vide s'il est absent. */
    fun readSegmentsStrict(file: File): List<StoredSegment> {
        val json = RecordingNames.jsonSibling(file)
        if (!json.exists()) return emptyList()
        return SegmentsCodec.fromJson(TextVault.read(json))
    }

    /** Écrit les sous-titres et les segments issus d'une transcription différée (vides = ignorés). */
    fun writeSidecars(file: File, srt: String, segmentsJson: String) {
        if (srt.isNotBlank()) {
            try {
                TextVault.write(RecordingNames.srtSibling(file), srt)
            } catch (e: Exception) {
                Log.e(TAG, "écriture SRT : ${e.message}")
            }
        }
        if (segmentsJson.isNotBlank()) {
            try {
                TextVault.write(RecordingNames.jsonSibling(file), segmentsJson)
                // Segments tout juste régénérés depuis l'audio : plus rien de désynchronisé
                val meta = readMeta(file)
                if (meta.segmentsStale) writeMeta(file, meta.copy(segmentsStale = false))
            } catch (e: Exception) {
                Log.e(TAG, "écriture segments : ${e.message}")
            }
        }
    }

    /**
     * Durée en ms sans déchiffrement (la liste ne déchiffre plus chaque .enc à chaque
     * rafraîchissement) : header WAV en clair ; taille moins l'overhead AES-GCM
     * (IV 12 + tag 16) et le header WAV (44) pour les .enc ; en-tête « Durée : »
     * du .txt pour les entrées texte seul.
     */
    fun durationMsOf(file: File): Long = when {
        file.name.endsWith(".enc") ->
            ((file.length() - 12 - 16 - 44).coerceAtLeast(0) * 1000L) / 32000L
        file.name.endsWith(".txt") ->
            try {
                TranscriptExporter.parseDurationMs(TextVault.read(file))
            } catch (e: Exception) {
                0L
            }
        else -> readWavDuration(file)
    }

    private fun readWavDuration(file: File): Long {
        return try {
            val raf = file.inputStream().use { inp ->
                val header = ByteArray(44)
                val n = inp.read(header)
                if (n < 44) return 0L
                header
            }
            val dataSize = (raf[40].toInt() and 0xFF) or
                ((raf[41].toInt() and 0xFF) shl 8) or
                ((raf[42].toInt() and 0xFF) shl 16) or
                ((raf[43].toInt() and 0xFF) shl 24)
            (dataSize.toLong() * 1000L) / 32000L
        } catch (e: Exception) {
            0L
        }
    }

    // ---- Écriture du .txt ----

    /**
     * Écrit (ou écrase) le .txt d'un enregistrement : métadonnées + transcription.
     * Retourne false si le scellement demandé a échoué (texte conservé en clair).
     */
    fun writeTranscriptFile(audioFile: File, text: String, durationMs: Long, sha256: String? = null): Boolean {
        try {
            val txt = RecordingNames.txtSibling(audioFile)
            // L'empreinte PCM n'est calculée qu'à l'enregistrement : on la préserve
            // quand le .txt est réécrit (transcription différée, édition manuelle).
            val hash = sha256 ?: try {
                if (txt.exists()) HASH_LINE.find(TextVault.read(txt))?.groupValues?.get(1) else null
            } catch (e: Exception) {
                null // ancien .txt illisible (clé perdue, fichier altéré) : on réécrit sans empreinte
            }
            val date = if (audioFile.exists()) audioFile.lastModified() else System.currentTimeMillis()
            val sb = StringBuilder()
            sb.append("Transcripto Stream\n")
            sb.append("Date : ").append(TXT_DATE_FORMAT.format(Date(date))).append("\n")
            sb.append("Durée : ").append(TranscriptExporter.formatHms(durationMs)).append("\n")
            if (audioFile.extension == "enc") sb.append("Chiffré : oui\n")
            if (hash != null) sb.append("SHA-256 (PCM) : ").append(hash).append("\n")
            sb.append(HEADER_END).append("\n")
            sb.append(text)
            return TextVault.write(txt, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "writeTranscriptFile: ${e.message}")
            return true
        }
    }

    /**
     * Transcription corrigée à la main (écran principal) : noms ramenés aux libellés
     * génériques, .txt réécrit ; les segments (.json) et le .srt suivent ligne à ligne
     * quand le texte garde un horodatage par segment, sinon ils sont marqués
     * désynchronisés (la fiche invite à relancer « Transcrire »).
     */
    fun saveEditedTranscript(file: File, displayedText: String): EditOutcome {
        val meta = readMeta(file)
        val raw = SpeakerNames.unapply(displayedText, meta.speakers)
        val sealed = writeTranscriptFile(file, raw, durationMsOf(file))
        val segs = readSegments(file)
        if (segs.isEmpty()) return EditOutcome(sealed, segmentsStale = false)
        val realigned = realign(raw, segs)
        if (realigned == null) {
            if (!meta.segmentsStale) writeMeta(file, meta.copy(segmentsStale = true))
            return EditOutcome(sealed, segmentsStale = true)
        }
        try {
            TextVault.write(RecordingNames.jsonSibling(file), SegmentsCodec.toJsonStored(realigned))
            val srt = TranscriptExporter.buildSrt(realigned.map { SegmentData(it.text, it.startMs, it.endMs) })
            if (srt.isNotBlank()) TextVault.write(RecordingNames.srtSibling(file), srt)
            if (meta.segmentsStale) writeMeta(file, meta.copy(segmentsStale = false))
        } catch (e: Exception) {
            Log.e(TAG, "saveEditedTranscript: ${e.message}")
        }
        return EditOutcome(sealed, segmentsStale = false)
    }

    /**
     * Reconstruction avec horodatage [mm:ss], attribution [Intervenant N],
     * pauses > 1,2 s, et bloc « temps de parole » par intervenant en fin de
     * transcription (réunions, entretiens d'audit).
     */
    fun buildSpeakerMarkedTranscript(res: StreamResult, speakerIds: List<Int>, timestamps: Boolean): String {
        val segments = res.segments
        if (segments.isEmpty()) return res.fullText.trim()
        val sb = StringBuilder()
        var currentSpeaker = 0
        for ((i, seg) in segments.withIndex()) {
            if (seg.text.isBlank()) continue
            if (i > 0) {
                val gap = seg.startMs - segments[i - 1].endMs
                if (gap > 1200) {
                    sb.append(" [pause ${gap / 1000}s] ")
                }
            }
            if (speakerIds[i] != currentSpeaker) {
                currentSpeaker = speakerIds[i]
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("[Intervenant $currentSpeaker] ")
            }
            if (timestamps) {
                sb.append("[").append(formatClock(seg.startMs)).append("] ")
            }
            sb.append(seg.text.trim()).append(" ")
        }
        // Stats sur les seuls segments affichés (les blancs sautés plus haut fausseraient les %)
        val kept = segments.indices.filter { segments[it].text.isNotBlank() }
        val stats = TranscriptExporter.buildSpeakingStats(
            kept.map { segments[it] },
            kept.map { speakerIds[it] },
        )
        if (stats.isNotBlank()) {
            sb.append("\n\n").append(stats)
        }
        return sb.toString().trim()
    }

    /** Nombre d'occurrences littérales de [needle] dans [text] (au plus 2 : seule l'unicité importe). */
    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var i = text.indexOf(needle)
        while (i >= 0 && count < 2) {
            count++
            i = text.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Correction d'un passage : met à jour les segments (.json), puis le .txt
     * (remplacement ciblé quand l'ancien passage s'y trouve une seule fois — les
     * corrections manuelles antérieures sont préservées — sinon reconstruction
     * complète, en gardant l'horodatage tel qu'il était) et le .srt.
     * [defaultTimestamps] : réglage courant, utilisé si le .txt est vide.
     * Retourne null si la correction est impossible (segments introuvables, index hors bornes).
     */
    fun updateSegmentText(file: File, index: Int, newText: String, defaultTimestamps: Boolean): EditOutcome? = try {
        val json = RecordingNames.jsonSibling(file)
        if (!json.exists()) {
            null
        } else {
            val segs = SegmentsCodec.fromJson(TextVault.read(json)).toMutableList()
            val cleaned = newText.trim()
            if (index !in segs.indices || cleaned.isEmpty()) {
                null
            } else {
                val oldText = segs[index].text.trim()
                segs[index] = segs[index].copy(text = cleaned)
                TextVault.write(json, SegmentsCodec.toJsonStored(segs))
                val data = segs.map { SegmentData(it.text, it.startMs, it.endMs) }
                val body = transcriptBody(file)
                val text = if (oldText.isNotEmpty() && countOccurrences(body, oldText) == 1) {
                    body.replaceFirst(oldText, cleaned)
                } else {
                    buildSpeakerMarkedTranscript(
                        StreamResult(fullText = segs.joinToString(" ") { it.text }, segments = data),
                        segs.map { it.speaker },
                        timestamps = if (body.isEmpty()) defaultTimestamps else CLOCK_TAG.containsMatchIn(body),
                    )
                }
                val sealed = writeTranscriptFile(file, text, durationMsOf(file))
                val srt = TranscriptExporter.buildSrt(data)
                if (srt.isNotBlank()) TextVault.write(RecordingNames.srtSibling(file), srt)
                EditOutcome(sealed, segmentsStale = false)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "updateSegmentText: ${e.message}")
        null
    }

    // ---- Liste ----

    /** Tous les enregistrements (audio + texte seul), du plus récent au plus ancien. */
    fun list(): RecordingListing {
        val files = dir.listFiles()?.toList() ?: emptyList()
        val audio = files.filter { RecordingNames.isAudio(it.name) }
        val audioBases = audio.map { RecordingNames.baseName(it.name) }.toSet()
        // .txt hérités v0.2.x (« base.wav.txt » à côté de « base.wav.enc »)
        val legacyTxtNames = audio.map { it.nameWithoutExtension + ".txt" }.toSet()
        // Entrées « texte seul » : .txt sans audio associé (transcriptions Google)
        val textOnly = files.filter {
            RecordingNames.isTextOnly(it.name) &&
                RecordingNames.baseName(it.name) !in audioBases &&
                it.name !in legacyTxtNames
        }
        val list = (audio + textOnly).map { f -> item(f) }.sortedByDescending { it.modifiedAt }
        return RecordingListing(list, files.sumOf { it.length() })
    }

    /** Élément de liste pour un fichier (lit le .txt une seule fois — aperçu et durée — et le .meta). */
    fun item(f: File): RecordingItem {
        // Texte scellé illisible (clé KeyStore perdue) : aperçu vide, la liste reste utilisable
        val content = readTranscript(f)
        val transcript = content?.let { bodyOf(it) }?.take(200) ?: ""
        val meta = readMeta(f)
        return RecordingItem(
            file = f,
            baseName = RecordingNames.baseName(f.name),
            sizeBytes = f.length(),
            durationMs = if (f.name.endsWith(".txt")) content?.let { TranscriptExporter.parseDurationMs(it) } ?: 0L else durationMsOf(f),
            modifiedAt = f.lastModified(),
            encrypted = f.name.endsWith(".enc"),
            hasAudio = RecordingNames.isAudio(f.name),
            transcript = SpeakerNames.apply(transcript, meta.speakers),
            dossier = meta.dossier,
            speakerNames = meta.speakers,
            template = meta.template,
            chapters = meta.chapters,
            segmentsStale = meta.segmentsStale,
            actions = meta.actions,
        )
    }

    /**
     * Actions encore ouvertes des autres enregistrements du même [dossier] (base du fichier →
     * action), du plus récent au plus ancien — contexte des synthèses IA et fiche dossier.
     */
    fun openActionsInDossier(dossier: String, except: File? = null): List<DossierAction> {
        val d = dossier.trim()
        if (d.isEmpty()) return emptyList()
        val files = (dir.listFiles() ?: return emptyList())
            .filter { it.isFile && (RecordingNames.isAudio(it.name) || RecordingNames.isTextOnly(it.name)) }
            .filter { except == null || RecordingNames.baseName(it.name) != RecordingNames.baseName(except.name) }
            .sortedByDescending { it.lastModified() }
        val out = ArrayList<DossierAction>()
        val seenBases = HashSet<String>()
        for (f in files) {
            val base = RecordingNames.baseName(f.name)
            if (!seenBases.add(base)) continue // « a.wav » et « a.txt » partagent le .meta
            val meta = readMeta(f)
            if (!meta.dossier.equals(d, ignoreCase = true)) continue
            meta.openActions.forEach { out += DossierAction(f, base, it) }
        }
        return out
    }

    /**
     * Renomme un dossier (ou le fusionne dans [newName] s'il existe déjà) : réécrit le
     * .meta de chaque enregistrement rattaché à [oldName]. Retourne le nombre modifié.
     */
    fun renameDossier(oldName: String, newName: String): Int {
        val old = oldName.trim()
        val target = newName.trim()
        if (old.isEmpty() || target.isEmpty() || old == target) return 0
        var count = 0
        val seenBases = HashSet<String>()
        for (f in dir.listFiles() ?: return 0) {
            if (!f.isFile || !(RecordingNames.isAudio(f.name) || RecordingNames.isTextOnly(f.name))) continue
            if (!seenBases.add(RecordingNames.baseName(f.name))) continue
            val meta = readMeta(f)
            if (!meta.dossier.equals(old, ignoreCase = true)) continue
            writeMeta(f, meta.copy(dossier = target))
            count++
        }
        return count
    }

    /** Bases déjà prises dans le dossier (tous types confondus). */
    fun takenBases(): Set<String> =
        dir.listFiles()?.map { RecordingNames.baseName(it.name) }?.toSet() ?: emptySet()

    /** Base unique pour un nouveau fichier (import, restauration) : ni collision locale, ni avec [alsoTaken]. */
    fun uniqueBase(base: String, alsoTaken: Collection<String> = emptyList()): String =
        RecordingRepository.uniqueBase(base, takenBases() + alsoTaken)

    // ---- Renommage / suppression / rétention ----

    /**
     * Renommage (dialog de fin d'enregistrement + liste) : conserve le suffixe
     * (.wav / .wav.enc / .txt) et renomme les fichiers frères.
     */
    fun rename(f: File, newName: String): RenameResult {
        val name = RecordingNames.sanitize(newName)
        if (name.isEmpty() || name == RecordingNames.baseName(f.name)) return RenameResult.Unchanged
        val dest = RecordingNames.renamed(f, name)
        // Collision sur le nom de base, tous types confondus (.wav, .wav.enc, .txt seul) :
        // un renameTo POSIX écraserait silencieusement la cible homonyme.
        val clash = f.parentFile?.listFiles()?.any { other ->
            other != f && RecordingNames.baseName(other.name) == name
        } == true
        if (clash || dest.exists()) return RenameResult.Clash
        val oldTxt = RecordingNames.txtSibling(f)
        val oldSrt = RecordingNames.srtSibling(f)
        val oldJson = RecordingNames.jsonSibling(f)
        val oldMd = RecordingNames.mdSibling(f)
        val oldMeta = RecordingNames.metaSibling(f)
        if (!f.renameTo(dest)) return RenameResult.Failed
        if (oldTxt.exists()) oldTxt.renameTo(RecordingNames.txtSibling(dest))
        if (oldSrt.exists()) oldSrt.renameTo(RecordingNames.srtSibling(dest))
        if (oldJson.exists()) oldJson.renameTo(RecordingNames.jsonSibling(dest))
        if (oldMd.exists()) oldMd.renameTo(RecordingNames.mdSibling(dest))
        if (oldMeta.exists()) oldMeta.renameTo(RecordingNames.metaSibling(dest))
        return RenameResult.Renamed(dest)
    }

    /** Supprime un enregistrement et ses fichiers frères (.txt, .srt, .json, .md, .meta + .txt hérité v0.2.x). */
    fun delete(f: File) {
        f.delete()
        RecordingNames.txtSibling(f).delete()
        RecordingNames.srtSibling(f).delete()
        RecordingNames.jsonSibling(f).delete()
        RecordingNames.mdSibling(f).delete()
        RecordingNames.metaSibling(f).delete()
        // Ancienne convention (v0.2.x) : « base.wav.enc » avait parfois « base.wav.txt »
        File(f.parentFile, f.nameWithoutExtension + ".txt").delete()
    }

    /**
     * Rétention RGPD : supprime les enregistrements plus vieux que [days] jours
     * (0 = désactivé) et purge les synthèses/métadonnées orphelines. Retourne le
     * nombre d'enregistrements supprimés.
     */
    fun cleanupExpired(days: Int, now: Long = System.currentTimeMillis()): Int {
        if (days <= 0) return 0
        val cutoff = now - days * 86_400_000L
        val files = dir.listFiles()?.toList() ?: emptyList()
        val audioBases = files.filter { RecordingNames.isAudio(it.name) }
            .map { RecordingNames.baseName(it.name) }
            .toSet()
        // Synthèses / métadonnées orphelines (sans audio ni .txt du même nom) : purgées
        files.filter { it.name.endsWith(".md") || it.name.endsWith(".meta") }.forEach { md ->
            val base = RecordingNames.baseName(md.name)
            val hasOwner = files.any {
                it != md && !it.name.endsWith(".md") && !it.name.endsWith(".meta") &&
                    RecordingNames.baseName(it.name) == base &&
                    (RecordingNames.isAudio(it.name) || RecordingNames.isTextOnly(it.name))
            }
            if (!hasOwner) md.delete()
        }
        var removed = 0
        files.forEach { f ->
            val expired = f.lastModified() < cutoff
            if (!expired) return@forEach
            if (RecordingNames.isAudio(f.name)) {
                delete(f)
                removed++
            } else if (
                RecordingNames.isTextOnly(f.name) &&
                RecordingNames.baseName(f.name) !in audioBases
            ) {
                // Entrée texte seul (Google) : soumise à la même rétention
                f.delete()
                RecordingNames.srtSibling(f).delete()
                RecordingNames.mdSibling(f).delete()
                RecordingNames.metaSibling(f).delete()
                removed++
            }
        }
        return removed
    }

    // ---- Index de recherche ----

    /** Segments à indexer : ceux du .json, sinon les lignes du .txt (texte seul). */
    fun segmentsForIndex(file: File): List<StoredSegment> {
        val segs = readSegments(file)
        if (segs.isNotEmpty()) return segs
        val txt = transcriptFileFor(file)
        if (!txt.exists()) return emptyList()
        return SearchIndex.segmentsFromText(TextVault.read(txt).substringAfter(HEADER_END))
    }

    /** Date du contenu textuel d'un enregistrement (max des fichiers texte). */
    fun contentStamp(file: File): Long =
        maxOf(transcriptFileFor(file).lastModified(), RecordingNames.jsonSibling(file).lastModified())

    fun indexSource(item: RecordingItem): IndexSource = IndexSource(
        file = item.file,
        contentStamp = contentStamp(item.file),
        baseName = item.baseName,
        dossier = item.dossier,
        hasAudio = item.hasAudio,
        speakerNames = item.speakerNames,
        modifiedAt = item.modifiedAt,
    )
}
