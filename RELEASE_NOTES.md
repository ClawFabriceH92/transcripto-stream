# Transcripto Stream v0.10.0

APK **complet et signé** (binaires whisper.cpp + modèle Base embarqués) : l'app fonctionne dès l'installation — Google immédiatement, Whisper local dès la fin du chargement du modèle.

> Mise à jour directe depuis toute version ≥ v0.2.5 (même signature, données conservées). Les versions suivantes s'installeront automatiquement si « Mise à jour automatique » est active.

## Nouveautés v0.10.0 — recherche dans les passages, chapitres, détection de parole neuronale

- **Recherche instantanée dans tous les passages** : la recherche de la liste trouve désormais les passages eux-mêmes, dans toutes les transcriptions (accents et casse ignorés, tous les termes exigés). Une section « Passages » affiche l'extrait avec les termes en gras, l'horodatage, l'intervenant et le dossier ; toucher un passage ouvre la fiche et cale la lecture dessus. L'index est en mémoire, reconstruit seulement pour les enregistrements modifiés : rien n'est stocké en clair, même avec le chiffrement des textes.
- **Chapitres automatiques** : sur la fiche, « Détecter les chapitres » découpe un long enregistrement en parties titrées — par Claude si l'IA est configurée, sinon sur l'appareil par bascule de vocabulaire (frontières espacées d'au moins deux minutes). Toucher un chapitre lance la lecture et fait défiler la transcription ; les chapitres figurent en sous-titres dans les exports Word et PDF.
- **Détection de parole neuronale (Silero VAD)** en mode Whisper : les phrases sont transcrites dès qu'elles se terminent (au lieu d'un tic fixe d'une seconde), les silences et bruits ne sont plus envoyés au moteur. Modèle ONNX embarqué (2,3 Mo), exécuté sur l'appareil ; si le runtime est indisponible, l'app revient automatiquement à la détection par volume. Désactivable dans Réglages → Reconnaissance.

## v0.9.0 — dossiers, intervenants nommés, exports Word/PDF, sécurité renforcée

### Organisation
- **Intervenants nommés** : sur la fiche, toucher « Intervenant 1 » pour lui donner un nom (« M. Martin (DG) »). Le nom s'applique à l'affichage, au partage, à la synthèse, aux questions à l'IA et aux exports ; la transcription brute garde les libellés génériques (renommable à tout moment).
- **Dossiers / clients** : chaque enregistrement peut être rattaché à un dossier (proposé à la fin de l'enregistrement, modifiable sur la fiche). La liste se filtre par dossier et la recherche porte aussi sur le dossier.
- **Correction d'un passage sur la fiche** : appui long sur un passage → texte corrigé (fichiers `.txt`, `.srt` et segments mis à jour, corrections antérieures préservées) et, au besoin, ajout du terme mal reconnu au vocabulaire personnalisé.

### Synthèse et IA
- **Gabarits de synthèse par type de mission** : Réunion, Clôture / révision (points par cycle, ajustements, pièces à obtenir), Contrôle interne (procédures, constats, risques, recommandations), AG / Conseil (ordre du jour, résolutions et votes), Entretien client (demandes, informations, conseils), Dictée / note (mise au propre du texte). Choix à la fin de l'enregistrement ou sur la fiche ; vaut pour la synthèse locale comme pour celle rédigée par Claude.
- **Questions à l'IA sur un enregistrement** (si la synthèse IA est configurée) : « Quel montant a été évoqué pour la provision ? », « Qui envoie la convention ? » — réponses tirées de la transcription, fil de questions conservé pendant la consultation, préfixe mis en cache côté API pour réduire le coût des questions suivantes.

### Exports
- **Export Word (.docx) et PDF** depuis la fiche ou le menu de la liste, vers l'emplacement de ton choix : page de garde (titre, dossier, date, durée, type de mission, intervenants, temps de parole, empreinte SHA-256), synthèse en rubriques, transcription par intervenant nommé avec horodatages. Sans bibliothèque tierce.

### Sécurité
- **Chiffrement des textes au repos** (Réglages → Stockage) : transcriptions, sous-titres, synthèses et métadonnées scellés (AES-256-GCM, clé AndroidKeyStore) comme les WAV ; conversion immédiate des fichiers existants ; partage et sauvegarde restent lisibles.
- **Déverrouillage biométrique** (empreinte, visage ou code de l'appareil) proposé d'emblée sur l'écran PIN, qui reste utilisable.
- **Verrouillage automatique** après 1, 5 ou 15 minutes en arrière-plan (ou au lancement seulement).

### Fiabilité
- **Journal local des incidents** (Réglages → À propos) : chaque plantage est consigné sur l'appareil (date, version, trace) — consultable, partageable en pièce jointe, effaçable. Rien n'est envoyé.

## v0.8.0 — synthèse de fin d'enregistrement

- **Synthèse proposée à la fin de chaque enregistrement** : une fois le nom choisi, un message propose de générer la synthèse. Elle est aussi disponible sur la carte du dernier enregistrement (« Générer la synthèse ») et sur la fiche de n'importe quel enregistrement (« Regénérer », « Copier »).
- **Synthèse locale, sans envoi de données** (par défaut) : points clés, décisions, actions à mener, points de vigilance, chiffres et dates cités, moments marqués ⭐, répartition de la parole, mots-clés — extraite de la transcription sur l'appareil, instantanément, hors ligne.
- **Synthèse rédigée par l'IA (option)** : dans Réglages → Synthèse, active « Synthèse rédigée par l'IA (Claude) » et renseigne ta clé API Anthropic (chiffrée sur l'appareil). Seul le texte de la transcription est envoyé, jamais l'audio ; choix du modèle (Opus 5 par défaut, Sonnet 5, Haiku 4.5) ; en cas d'erreur ou hors ligne, la synthèse locale prend le relais.
- La synthèse est enregistrée à côté de l'enregistrement (`.md`), **incluse dans le partage** (corps du message + pièce jointe), la sauvegarde chiffrée, le renommage et la suppression.

## v0.7.0 — interface professionnelle

- **Système visuel unifié** : palette Material 3 complète (surfaces tonales claires/sombres), typographie et formes définies une fois pour toute l'app — plus d'émojis en guise d'icônes, plus de tailles de texte au cas par cas.
- **Écran « Transcrire » repensé** : choix du moteur en boutons segmentés (Google · cloud / Whisper · local), carte de session avec état pulsant, chrono lisible (heures affichées au-delà de 60 min) et rappel de confidentialité, zone de transcription en direct aérée, gros bouton d'enregistrement avec halo, commandes Marqueur / Pause / Arrêter en pastilles.
- **Dernier enregistrement** : deux actions principales (Écouter, Transcrire), puce de vitesse à cycle (1× → 1,5× → 2× → 0,5×), aperçu de la transcription, menu pour copier / partager / corriger / supprimer, et accès direct à la fiche.
- **Liste** : recherche en pilule avec effacement, total des durées, en-têtes de jour épinglés au défilement, cartes avec avatar (audio / texte seul), métadonnées en puces (heure, durée, chiffré), états vides guidés (transcrire / importer, aucun résultat).
- **Fiche d'un enregistrement** : lecteur en carte (bouton lecture, position / durée, vitesse, curseur), actions Transcrire / Partager, transcription avec intervenants colorés et passage en cours surligné, bouton retour dans la barre.
- **Réglages** : sections en cartes avec icônes (transcription locale, reconnaissance, comportement, stockage & confidentialité, sauvegarde, sécurité, mises à jour, apparence, à propos), lignes à interrupteur cliquables en entier, sélecteurs segmentés (langue, rétention, thème), état des modèles Whisper explicite (actif / téléchargé / à télécharger).
- **Écran PIN** : marque, points de saisie et pavé numérique tonal.
- Transitions en fondu entre les onglets, bandeaux d'état (import, chargement ou erreur du modèle) homogènes.

## v0.6.0

- **Sauvegarde chiffrée exportable** (Réglages → Sauvegarde) : tous les enregistrements et transcriptions dans une archive protégée par phrase de passe, **restaurable sur un autre appareil** — jusqu'ici, un téléphone perdu = fichiers chiffrés irrécupérables (la clé AndroidKeyStore ne quitte pas l'appareil). Restauration jamais destructive (doublons suffixés).
- **Écran détail avec lecture synchronisée** : toucher un enregistrement dans la liste ouvre sa fiche — **toucher un passage cale l'audio dessus**, le passage en cours de lecture est surligné et suivi, curseur de position, re-transcription et partage sur place. (Les segments interactifs sont générés par « Transcrire » ; re-transcrivez vos anciens enregistrements pour en profiter.)
- **Résilience audio** : un appel entrant ou une app qui prend le micro **met l'enregistrement en pause automatiquement**, avec reprise à la fin ; si la capture meurt, l'enregistrement est arrêté proprement et sauvegardé au lieu d'un chrono qui tourne dans le vide.
- **Mode dictée** (Réglages, désactivé par défaut) : « point », « virgule », « à la ligne », « nouveau paragraphe »… dits à la voix sont convertis en ponctuation.

## v0.5.3

- **Fix du crash de l'écran Réglages** (permission `REQUEST_INSTALL_PACKAGES` manquante pour la vérification d'installation des mises à jour).
- **Écoute silencieuse** (Réglages, activée par défaut) : coupe les bips du système de reconnaissance Google pendant l'écoute ; le volume est rétabli à l'arrêt.
- **Boutons sans retour à la ligne** : « Transcrire », « Partager », « Corriger »… ne se coupent plus en plein mot.
- **Réglages réactifs** : chips, interrupteurs et curseur de gain reflètent immédiatement le choix.

## Nouveautés depuis la v0.2.5

### Transcription
- **Catalogue de modèles Whisper** (Réglages) : Small quantisé (recommandé, nettement meilleur en français), Small, Medium quantisé, Large-v3 Turbo quantisé — téléchargement en arrière-plan avec reprise, activation en un tap, repli automatique sur le modèle embarqué.
- **Re-transcription haute fidélité** : activez un meilleur modèle puis relancez « Transcrire » sur n'importe quel enregistrement.
- Transcription différée enrichie : horodatage, **[Intervenant 1/2]** (détection par la voix), **temps de parole par intervenant**, sous-titres **.srt**.
- **Marqueurs ⚑** pendant l'enregistrement (`[⭐mm:ss]`), **correction manuelle** du transcript, **empreinte SHA-256** du flux audio dans le `.txt` (valeur probante).
- Les transcriptions **Google ne se perdent plus** (entrées « texte seul » dans la liste).

### Import / export
- **Import d'audio externe** : bouton « Importer » ou « Partager vers Transcripto » depuis WhatsApp/Fichiers/dictaphone (m4a, mp3, ogg, amr, flac, wav) — décodage 100 % local puis transcription.
- **Export de l'audio (WAV)** vers l'emplacement de votre choix (Téléchargements, Drive…), déchiffré à la volée ; partage texte + .txt + WAV + .srt.

### Interface
- **Nouvelle icône** professionnelle (adaptative + thémée Android 13+).
- Barre de titre par écran, liste **groupée par jour**, menu ⋮ par enregistrement, tuile « Transcrire » dans les réglages rapides, raccourci d'appui long, thème bleu clair/sombre unifié, accessibilité TalkBack.
- **Google utilisable immédiatement** au lancement, même pendant le chargement du modèle Whisper.

### Fiabilité / sécurité
- Nombreux correctifs : renommage qui faisait disparaître des enregistrements, transcripts des fichiers chiffrés introuvables, permissions enchaînées en un seul appui, confirmations de suppression, PIN masqué, verrous anti-concurrence autour du moteur natif, contrôle d'espace disque, rétention RGPD étendue.
- **Mise à jour automatique** : interrupteur dans les Réglages + « Vérifier maintenant » (la permission INTERNET manquante qui bloquait silencieusement la vérification est corrigée).

---
Build : GitHub Actions (`release.yml`) — compilation vérifiée par CI et revue multi-agents avant fusion.
