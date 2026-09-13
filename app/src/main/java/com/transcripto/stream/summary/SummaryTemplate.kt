package com.transcripto.stream.summary

/**
 * Rubrique d'une synthèse locale : titre + motif des phrases à y ranger.
 * [priority] : ordre d'attribution des phrases (plus petit = servi en premier) — les
 * rubriques précises passent avant les larges ; l'ordre d'affichage reste celui du gabarit.
 */
data class LocalSection(val title: String, val pattern: Regex, val max: Int = 6, val priority: Int = 5)

/**
 * Gabarit de synthèse selon le type de mission. Il pilote à la fois les rubriques
 * de l'extraction locale et la consigne envoyée à Claude. L'identifiant est
 * stocké dans le « .meta » de l'enregistrement.
 */
data class SummaryTemplate(
    val id: String,
    val label: String,
    val description: String,
    /** Consigne de rédaction (rubriques et règles propres à la mission), pour l'IA. */
    val aiTask: String,
    /** Rubriques de l'extraction locale, dans l'ordre d'affichage. */
    val localSections: List<LocalSection>,
    /** Dictée : mise au propre du texte plutôt qu'extraction de points. */
    val dictation: Boolean = false,
)

/** Catalogue des gabarits — pur Kotlin, testable en JVM. */
object SummaryTemplates {

    const val DEFAULT_ID = "reunion"

    private fun re(pattern: String) = Regex("(?i)\\b(?:$pattern)")

    private val DECISION = LocalSection(
        "Décisions",
        re("décid|décision|valid[ée]|validons|convenu|d'accord pour|accord sur|retenu|on part sur|acté|tranch|entérin|approuv|adopt"),
        priority = 3,
    )
    private val ACTION = LocalSection(
        "Actions à mener",
        re(
            "il faut|il faudra|nous devons|on doit|on va |on devra|à faire|action|tâche|prévoir|planifi|envoyer|" +
                "transmettre|relancer|vérifier|préparer|finaliser|rédiger|contacter|rappeler|organiser|rendez-vous|" +
                "échéance|deadline|avant le|d'ici|au plus tard|prochaine étape|à confirmer|à valider|livrable|" +
                "s'occupe|se charge|prend en charge|à envoyer|à transmettre|à signer"
        ),
        priority = 4,
    )
    private val VIGILANCE = LocalSection(
        "Points de vigilance",
        re("attention|risque|vigilance|problème|inquiét|alerte|litige|retard|non conforme|anomalie|écart|réserve|doute|à vérifier|bloqu"),
        max = 4,
        priority = 4,
    )

    val REUNION = SummaryTemplate(
        id = DEFAULT_ID,
        label = "Réunion",
        description = "Points clés, décisions, actions, chiffres cités, vigilance.",
        aiTask = """
            Rédige une synthèse de réunion avec exactement ces rubriques dans cet ordre (omets celles qui seraient vides) :
            ## Contexte — 2 à 3 phrases : objet, participants s'ils sont identifiables, tonalité.
            ## Points clés — puces.
            ## Décisions — puces.
            ## Actions à mener — puces « qui — quoi — échéance » (« non précisé » si absent).
            ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
            ## Points de vigilance — risques, désaccords, questions restées ouvertes.
        """.trimIndent(),
        localSections = listOf(DECISION, ACTION, VIGILANCE),
    )

    val CLOTURE = SummaryTemplate(
        id = "cloture",
        label = "Clôture / révision",
        description = "Points de révision par cycle, ajustements, pièces à obtenir, points en suspens.",
        aiTask = """
            Il s'agit d'une réunion de clôture ou de révision des comptes. Rédige une note de révision avec exactement
            ces rubriques dans cet ordre (omets celles qui seraient vides) :
            ## Contexte — entité, exercice, objet de la réunion, participants identifiables.
            ## Points de révision par cycle — puces regroupées par cycle (immobilisations, stocks, clients, fournisseurs,
            trésorerie, provisions, capitaux propres, fiscal, social…) avec les constats.
            ## Ajustements et écritures proposés — puces « compte / nature — montant — justification ».
            ## Documents à obtenir — puces « pièce — de qui — pour quand ».
            ## Points en suspens — questions ouvertes, arbitrages à rendre.
            ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
            ## Points de vigilance — risques d'anomalie significative, désaccords, délais.
        """.trimIndent(),
        localSections = listOf(
            LocalSection(
                "Points de révision",
                re(
                    "immobilisation|amortissement|stock|créance|client|fournisseur|trésorerie|provision|capitaux|emprunt|" +
                        "TVA|paie|impôt|charge|produit|cut-off|FNP|CCA|FAE|AAR|inventaire|rapprochement|lettrage|balance|" +
                        "grand livre|compte de résultat|bilan|résultat|marge|chiffre d'affaires"
                ),
                max = 8,
                priority = 6,
            ),
            LocalSection(
                "Ajustements proposés",
                re("écriture|ajust|régularis|reclass|provisionn|dépréci|à passer|à comptabiliser|extourn|corrig|OD "),
                priority = 2,
            ),
            LocalSection(
                "Documents à obtenir",
                re("pièce|justificatif|facture|relevé|contrat|attestation|à fournir|à obtenir|manqu|à récupérer|à demander|procès-verbal|liasse|déclaration|tableau d'amortissement"),
                priority = 1,
            ),
            DECISION,
            ACTION,
            VIGILANCE,
        ),
    )

    val CONTROLE = SummaryTemplate(
        id = "controle",
        label = "Contrôle interne",
        description = "Procédures décrites, constats, risques, recommandations, tests à mener.",
        aiTask = """
            Il s'agit d'un entretien de prise de connaissance ou d'évaluation du contrôle interne. Rédige une note
            avec exactement ces rubriques dans cet ordre (omets celles qui seraient vides) :
            ## Contexte — entité, processus couvert, interlocuteurs identifiables.
            ## Procédures décrites — puces : qui fait quoi, quand, avec quel outil ou document.
            ## Constats — puces : points forts, faiblesses, absences de contrôle observées.
            ## Risques identifiés — puces « risque — impact — probabilité si évoquée ».
            ## Recommandations — puces concrètes et priorisées.
            ## Tests à réaliser — puces : contrôles à mettre en œuvre pour valider les procédures.
            ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
        """.trimIndent(),
        localSections = listOf(
            LocalSection(
                "Procédures décrites",
                re("procédure|processus|circuit|validation|séparation des tâches|contrôle|signature|autorisation|rapprochement|inventaire|habilitation|accès|workflow|double signature"),
                max = 8,
                priority = 6,
            ),
            LocalSection(
                "Constats",
                re("constat|observ|relev|absence|défaillan|faiblesse|point fort|conforme|non conforme|anomalie|écart|pas de contrôle|personne ne"),
                priority = 3,
            ),
            LocalSection(
                "Risques identifiés",
                re("risque|fraude|erreur|perte|exposition|vulnérab|dépendance|détournement"),
                priority = 2,
            ),
            LocalSection(
                "Recommandations",
                re("recommand|préconis|il conviendrait|devrait|mettre en place|renforcer|formaliser|améliorer|à instaurer"),
                priority = 1,
            ),
            ACTION,
        ),
    )

    val AG = SummaryTemplate(
        id = "ag",
        label = "AG / Conseil",
        description = "Ordre du jour, résolutions et votes, questions des membres, actions.",
        aiTask = """
            Il s'agit d'une assemblée générale, d'un conseil d'administration ou d'un comité. Rédige un compte rendu
            avec exactement ces rubriques dans cet ordre (omets celles qui seraient vides) :
            ## Contexte — organe réuni, entité, date si citée, participants identifiables, quorum si évoqué.
            ## Ordre du jour — puces, dans l'ordre traité.
            ## Résolutions et votes — puces « résolution — résultat du vote (unanimité, majorité, abstentions) ».
            ## Décisions — puces.
            ## Questions des membres et réponses — puces « question — réponse apportée ».
            ## Actions à mener — puces « qui — quoi — échéance ».
            ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
        """.trimIndent(),
        localSections = listOf(
            LocalSection(
                "Ordre du jour",
                re("ordre du jour|point suivant|premier point|deuxième point|rapport de gestion|rapport du commissaire|affectation du résultat|quitus|approbation des comptes|nomination|renouvellement|rémunération|conventions réglementées"),
                priority = 2,
            ),
            LocalSection(
                "Résolutions et votes",
                re("résolution|vote|adopt|approuv|rejet|unanimité|majorité|abstention|quitus|pouvoirs"),
                priority = 1,
            ),
            LocalSection(
                "Questions des membres",
                re("question|souhaite savoir|interrog|demande si|est-ce que|pourquoi|comment"),
                max = 5,
                priority = 3,
            ),
            DECISION,
            ACTION,
        ),
    )

    val ENTRETIEN = SummaryTemplate(
        id = "entretien",
        label = "Entretien client",
        description = "Demandes du client, informations recueillies, conseils donnés, suites.",
        aiTask = """
            Il s'agit d'un entretien ou d'un rendez-vous avec un client. Rédige un compte rendu d'entretien avec
            exactement ces rubriques dans cet ordre (omets celles qui seraient vides) :
            ## Contexte — client, objet du rendez-vous, participants identifiables.
            ## Demandes du client — puces.
            ## Informations recueillies — puces : faits, situation, projets, chiffres communiqués.
            ## Conseils et options présentés — puces, avec les réserves émises.
            ## Actions à mener — puces « qui — quoi — échéance ».
            ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
            ## Points de vigilance — risques, incertitudes, informations à vérifier.
        """.trimIndent(),
        localSections = listOf(
            LocalSection(
                "Demandes du client",
                re("souhait|demand|voudrai|aimerai|besoin|est-ce que|comment faire|peut-on|pouvez-vous"),
                priority = 2,
            ),
            LocalSection(
                "Informations recueillies",
                re("chiffre d'affaires|salarié|effectif|activité|statut|création|projet|investissement|cession|acquisition|banque|emprunt|associé|dividende|rémunération|local|bail|véhicule"),
                priority = 3,
            ),
            LocalSection(
                "Conseils donnés",
                re("conseil|recommand|préconis|il vaut mieux|je vous suggère|option|possibilité|avantage|inconvénient|il faudrait|je vous propose"),
                priority = 1,
            ),
            ACTION,
            VIGILANCE,
        ),
    )

    val DICTEE = SummaryTemplate(
        id = "dictee",
        label = "Dictée / note",
        description = "Mise au propre du texte dicté, paragraphes, points à vérifier.",
        aiTask = """
            Il s'agit d'une dictée (note, courrier, mémo). Ne résume pas : mets le texte au propre avec exactement
            ces rubriques :
            ## Texte — le texte dicté, fidèlement, avec ponctuation et paragraphes corrigés, sans rien ajouter ni
            omettre ; les consignes orales de mise en forme (« à la ligne », « nouveau paragraphe ») sont appliquées et
            non recopiées.
            ## Points à vérifier — puces : passages ambigus, noms propres ou chiffres probablement mal transcrits.
            La longueur suit celle de la dictée (la limite de mots ci-dessous ne s'applique pas au « Texte »).
        """.trimIndent(),
        localSections = emptyList(),
        dictation = true,
    )

    val ALL: List<SummaryTemplate> = listOf(REUNION, CLOTURE, CONTROLE, AG, ENTRETIEN, DICTEE)

    /** Gabarit par identifiant ; « Réunion » si inconnu ou vide. */
    fun byId(id: String?): SummaryTemplate = ALL.firstOrNull { it.id == id } ?: REUNION
}
