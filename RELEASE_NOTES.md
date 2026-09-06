# Transcripto Stream v0.7.0

APK **complet et signé** (binaires whisper.cpp + modèle Base embarqués) : l'app fonctionne dès l'installation — Google immédiatement, Whisper local dès la fin du chargement du modèle.

> Mise à jour directe depuis toute version ≥ v0.2.5 (même signature, données conservées). Les versions suivantes s'installeront automatiquement si « Mise à jour automatique » est active.

## Nouveautés v0.7.0 — interface professionnelle

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
