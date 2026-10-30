package com.transcripto.stream.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export CSV des actions suivies d'un dossier.
 *
 * Un fichier pour le tableur : séparateur « ; » (Excel français), BOM UTF-8 pour
 * que les accents passent, une ligne par action avec son enregistrement
 * d'origine, son responsable, son échéance et son état. Pur, testable sur JVM.
 */
object ActionsCsv {

    data class LigneAction(
        val dossier: String,
        val enregistrement: String,
        val dateEnregistrement: String,
        val action: String,
        val responsable: String,
        val echeance: String,
        val statut: String,
    )

    private val ENTETES = listOf(
        "Dossier",
        "Enregistrement",
        "Date de l'enregistrement",
        "Action",
        "Responsable",
        "Échéance",
        "Statut",
    )

    /** Date lisible (jj/mm/aaaa) d'un enregistrement, à partir de son horodatage. */
    fun dateLisible(horodatage: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(horodatage))

    /** Contenu CSV complet (entête « sep=; » incluse). */
    fun construire(lignes: List<LigneAction>): String {
        val sb = StringBuilder()
        sb.append("sep=;\n")
        sb.append(ENTETES.joinToString(";") { cellule(it) }).append('\n')
        for (ligne in lignes) {
            sb.append(
                listOf(
                    ligne.dossier,
                    ligne.enregistrement,
                    ligne.dateEnregistrement,
                    ligne.action,
                    ligne.responsable,
                    ligne.echeance,
                    ligne.statut,
                ).joinToString(";") { cellule(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    /** Octets à écrire : BOM UTF-8 puis le contenu (Excel reconnaît l'encodage). */
    fun octets(lignes: List<LigneAction>): ByteArray {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return bom + construire(lignes).toByteArray(Charsets.UTF_8)
    }

    /** Échappement CSV : guillemets doublés, cellule citée si elle contient un séparateur. */
    fun cellule(valeur: String): String {
        val propre = valeur.replace("\r", " ").replace("\n", " ").trim()
        return if (propre.contains(';') || propre.contains('"')) {
            "\"" + propre.replace("\"", "\"\"") + "\""
        } else {
            propre
        }
    }
}
