# Transcripto Stream

Application Android de transcription vocale **en temps réel**, pensée pour les réunions et entretiens professionnels (audit, CAC), avec un mode **100 % local** (whisper.cpp embarqué, aucun réseau).

## Fonctionnement

- **Deux moteurs de transcription temps réel** :
  - **Google (qualité)** — SpeechRecognizer système, la même qualité que Gboard/assistant Android. Audio envoyé au service du fournisseur (cloud) sauf pack hors-ligne téléchargé. La transcription est **sauvegardée en entrée « texte seul »** (pas l'audio).
  - **Whisper (100% local)** — whisper.cpp embarqué, l'audio ne quitte jamais l'appareil. **Google est disponible immédiatement**, même si le modèle Whisper charge encore ou est en erreur.
- **Catalogue de modèles Whisper** (Réglages) : Base embarqué + modèles téléchargeables à la demande depuis le dépôt ggml officiel — Small quantisé (~190 Mo, recommandé, nettement meilleur en français), Small, Medium quantisé, Large-v3 Turbo quantisé (~574 Mo, la meilleure qualité). Téléchargement en arrière-plan (DownloadManager, reprise après redémarrage), activation en un tap, repli automatique sur le modèle embarqué si un modèle téléchargé est illisible. **Re-transcription haute fidélité** : activer un meilleur modèle puis relancer « Transcrire » sur n'importe quel enregistrement ou import.
- **Conservation audio** (mode Whisper) : chaque enregistrement est écrit en WAV (`filesDir/recordings/`), avec **empreinte SHA-256 du flux PCM** notée dans le `.txt` (valeur probante).
- **Transcription différée** (mode Whisper) : bouton « Transcrire » → whisper traite le fichier complet et produit :
  - horodatage `[mm:ss]` par segment,
  - attribution `[Intervenant 1/2]` (estimation par le pitch de la voix),
  - **bloc « temps de parole » par intervenant** (durée + %),
  - **sous-titres `.srt`** à côté du WAV.
- **Marqueurs à chaud** : bouton ⚑ pendant l'enregistrement → insère `[⭐mm:ss]` dans le texte pour retrouver les moments clés.
- **Correction manuelle** : bouton « Corriger » → édite la transcription, sauvegardée dans le `.txt`.
- **Partage** : texte + `.txt` + WAV (déchiffré à la volée) + `.srt`, depuis l'écran principal **ou directement depuis la liste**.
- **Démarrage en un geste** : tuile « Transcrire » dans les réglages rapides + App Shortcut (appui long sur l'icône).
- **Import d'audio externe** : bouton « Importer » dans la liste, ou « Partager vers Transcripto » depuis WhatsApp/Fichiers/dictaphone — décodage local (m4a, mp3, ogg, amr, flac, wav) vers WAV 16 kHz mono, puis transcription différée comme un enregistrement natif.
- **Export des audios** : « Exporter l'audio (WAV) » vers l'emplacement de ton choix (Téléchargements, Drive, clé USB…), déchiffré à la volée.
- **Écran détail synchronisé** : toucher un passage de la transcription cale l'audio dessus, surlignage du passage lu, curseur de position (segments générés à la transcription différée).
- **Sauvegarde chiffrée exportable** : archive protégée par phrase de passe (PBKDF2 + AES-256-GCM), restaurable sur un autre appareil — les WAV chiffrés y sont inclus en clair dans l'archive (elle-même chiffrée) car la clé AndroidKeyStore ne peut pas voyager.
- **Résilience audio** : pause automatique sur appel entrant (focus audio) avec reprise, arrêt propre et sauvegarde si le micro est perdu.
- **Mode dictée** : ponctuation dite à la voix (« point », « à la ligne »…), activable dans les Réglages.
- **Moteur compilé à la source, relecture assistée** (v0.13.0) : whisper.cpp v1.9.4 en sous-module compilé par le build (plus de binaires figés), **confiance par passage** dans le `.json`, mode **Vérification** sur la fiche (passages sous 60 % teintés, montants et dates soulignés, « Suivant »), marquage « (à vérifier) » dans les exports, réglage « Vérification par défaut ».
- **Dossiers, suivi des actions, import par lots** (v0.12.0) : **actions à mener suivies** (extraites des synthèses, cochables, responsable et échéance, dates françaises reconnues, contexte des actions ouvertes fourni à l'IA), **fiche dossier** (écran dédié : enregistrements, durée cumulée, intervenants, actions ouvertes agrégées, renommer/fusionner, export Word/PDF du dossier avec sommaire), **import par lots** (sélection ou partage multiple, dossier et gabarit communs, notification de progression annulable), **rappel de sauvegarde** (7/30 jours, bandeau sur la liste).
- **Refonte du code, corrections synchronisées, R8** (v0.11.0) : ViewModel découpé en collaborateurs testables (`RecordingRepository`, `LiveTranscriber`, `PlaybackController`, `BackupManager`, `AiAssistant`, `ModelManager`, `DocumentExporter`…), un seul chemin d'appel Claude (`ClaudeTransport`) avec client HTTP partagé, **correction depuis l'écran principal synchronisée avec les passages** (ou signalée non synchronisée), horodatages `hh:mm:ss` homogènes, **R8** en release avec compilation de la release non signée en CI, 111 tests JVM + Robolectric, `CLAUDE.md`.
- **Recherche dans les passages, chapitres, VAD neuronale** (v0.10.0) : **index en mémoire de tous les passages** (section « Passages » dans la liste, extrait en gras, lecture calée sur le passage ; rien n'est persisté en clair), **chapitres automatiques** titrés (bascule de vocabulaire sur l'appareil ou Claude ; navigation sur la fiche, sous-titres dans les exports), **Silero VAD** (ONNX Runtime) pour transcrire chaque phrase dès qu'elle se termine et ignorer silences et bruits, avec repli sur le seuil de volume.
- **Organisation** (v0.9.0) : **intervenants nommés** (toucher l'étiquette sur la fiche ; les noms s'appliquent à l'affichage, au partage, à la synthèse et aux exports, le `.txt` garde « [Intervenant N] »), **dossiers / clients** (proposés à la fin de l'enregistrement, filtres dans la liste), **correction d'un passage** par appui long sur la fiche (`.txt`/`.srt`/segments mis à jour, ajout au vocabulaire).
- **Gabarits de synthèse par mission** (v0.9.0) : Réunion, Clôture / révision, Contrôle interne, AG / Conseil, Entretien client, Dictée / note — rubriques de l'extraction locale et consigne envoyée à Claude adaptées ; **questions à l'IA** sur un enregistrement (transcription + synthèse en contexte mis en cache côté API).
- **Exports Word (.docx) et PDF** (v0.9.0) : page de garde, synthèse, transcription par intervenant nommé avec horodatages, empreinte SHA-256 — OOXML et PdfDocument écrits sans bibliothèque tierce.
- **Chiffrement des textes au repos, biométrie, verrouillage automatique** (v0.9.0) : textes scellés (AES-256-GCM, même clé que les WAV) avec migration au basculement, déverrouillage par empreinte / visage / code de l'appareil, verrouillage après 1/5/15 min en arrière-plan ; **journal local des incidents** consultable et partageable.
- **Synthèse de fin d'enregistrement** (v0.8.0) : proposée dès l'arrêt ; **locale** (extraction de phrases : points clés, décisions, actions, vigilance, chiffres et dates, moments ⭐, répartition de la parole, mots-clés — rien ne sort du téléphone) ou **rédigée par Claude** en option (SDK Anthropic, clé API chiffrée sur l'appareil, texte seul envoyé, choix du modèle). Enregistrée en `.md` à côté du fichier, partagée, sauvegardée, renommée avec lui.
- **Interface professionnelle** (v0.7.0) : système visuel Material 3 unifié (palette tonale claire/sombre, typographie, formes, jeu d'icônes vectorielles), écran « Transcrire » avec carte de session et chrono, liste à en-têtes épinglés et cartes à métadonnées, fiche avec lecteur en carte et intervenants colorés, réglages en sections.
- **Sécurité/RGPD** : PIN (saisie masquée), chiffrement WAV AES-256 (clé AndroidKeyStore), rétention automatique 30/60/90 j, contrôle d'espace disque avant enregistrement.
- **Mises à jour** (Réglages) : mise à jour automatique activable/désactivable (vérification GitHub Releases au lancement + quotidienne, téléchargement et installation automatiques), bouton « Vérifier maintenant », aide à l'autorisation d'installation.

## Architecture

```
app/src/main/
├── cpp/whisper_jni.cpp          # JNI : transcription d'un buffer PCM (pas de fichier)
├── assets/models/ggml-base.bin  # Modèle Whisper Base (~142 Mo, gitignoré)
├── cpp/whisper.cpp/             # Sous-module whisper.cpp v1.9.4 (ggml + whisper, compilés en statique)
├── cpp/CMakeLists.txt           # libwhisper.so = whisper + ggml + JNI (externalNativeBuild)
└── java/com/transcripto/stream/
    ├── MainActivity.kt             # Point d'entrée + actions RECORD (tuile/raccourci) et SEND/VIEW (import)
    ├── RecordTileService.kt        # Tuile de réglages rapides « Transcrire »
    ├── RecordingService.kt         # Foreground service (écran éteint)
    ├── audio/PcmAudioRecorder.kt   # AudioRecord → callback PCM
    ├── audio/LiveTranscriber.kt    # Ring buffer, VAD, fenêtres, boucle, fusion du texte (testé, moteur factice)
    ├── audio/FrameVad.kt           # Interface VAD trame par trame (Silero en production)
    ├── audio/PlaybackController.kt # Lecture MediaPlayer, position, vitesse, temp déchiffré
    ├── audio/AudioFocusGuard.kt    # Focus audio (pause/reprise) + écoute silencieuse
    ├── audio/AudioTransfer.kt      # Import (MediaCodec → WAV) et export SAF d'un audio
    ├── audio/PitchDiarizer.kt      # Diarisation approximative par hauteur de voix (pur)
    ├── audio/PcmDigest.kt          # Empreinte SHA-256 du flux PCM
    ├── audio/WavFileWriter.kt      # PCM → WAV conservé
    ├── audio/AudioImporter.kt      # Import externe : MediaCodec → WAV 16 kHz mono
    ├── audio/PcmResampler.kt       # Downmix + rééchantillonnage linéaire (pur, testé)
    ├── audio/SileroVad.kt          # Silero VAD v5 via ONNX Runtime (modèle dans assets/vad)
    ├── audio/SpeechGate.kt         # Hystérésis début/fin de phrase sur les probabilités VAD (pur, testé)
    ├── CrashLog.kt / TranscriptoApp.kt # Journal local des plantages (Application)
    ├── data/RecordingRepository.kt # Dépôt des enregistrements : liste, .meta, renommage, rétention, .txt (testé)
    ├── data/BackupManager.kt       # Archive .tsbk chiffrée par phrase de passe (testé, WavCipher factice)
    ├── data/RecordingNames.kt      # Conventions de nommage (.wav / .wav.enc / .txt / .srt / .md / .meta)
    ├── data/RecordingMeta.kt       # Métadonnées (.meta) : intervenants nommés, dossier, gabarit (pur, testé)
    ├── data/CryptoManager.kt       # AES-256-GCM (AndroidKeyStore) — KeyProvider + WavCipher
    ├── data/TextSealer.kt / TextVault.kt # Chiffrement des textes au repos (scellement + coffre, testés avec clé injectée)
    ├── data/SearchIndex.kt         # Index en mémoire des passages, recherche sans accents (pur, testé)
    ├── data/SettingsStore.kt       # Réglages (SharedPreferences)
    ├── export/TranscriptExporter.kt # SRT + stats temps de parole (pur, testé)
    ├── export/ExportDocument.kt    # Composition des exports en blocs (pur, testé)
    ├── export/DocxWriter.kt        # Word .docx : OOXML écrit à la main (pur, testé)
    ├── export/PdfWriter.kt         # PDF A4 : PdfDocument + StaticLayout
    ├── export/DocumentExporter.kt  # Assemblage du document Word/PDF d'un enregistrement
    ├── export/ShareComposer.kt     # Intent de partage (.txt, audio, .srt, .md)
    ├── summary/SummaryTemplate.kt  # Gabarits par type de mission (pur, testé)
    ├── summary/LocalSummarizer.kt  # Synthèse locale extractive (pur, testé)
    ├── summary/AiAssistant.kt      # Synthèse, questions, chapitres : Claude ou repli local (testé, transport factice)
    ├── summary/ActionExtractor.kt  # Actions à mener extraites des synthèses, dates françaises (pur, testé)
    ├── summary/ClaudeCall.kt       # ClaudeRequest / ClaudeTransport / SdkClaudeTransport (client HTTP partagé)
    ├── summary/ClaudeSummarizer.kt # Prompt de synthèse IA (opt-in)
    ├── summary/ClaudeQa.kt         # Questions sur un enregistrement (prompt caching)
    ├── summary/ChapterDetector.kt  # Chapitres par bascule de vocabulaire (pur, testé)
    ├── summary/ClaudeChapters.kt   # Chapitrage par Claude (JSON tolérant, testé)
    ├── summary/MarkdownLite.kt     # Markdown minimal : parsing + texte brut (pur, testé)
    ├── stt/WhisperStreamEngine.kt  # Pont JNI
    ├── stt/ModelCatalog.kt         # Modèles Whisper embarqué/téléchargeables
    ├── stt/ModelManager.kt         # Modèle actif, extraction, téléchargements, sélection
    ├── stt/GoogleSpeechEngine.kt   # SpeechRecognizer système
    └── ui/StreamViewModel.kt       # Navigation, verrouillage, réglages, flux d'état, capture ; délègue aux classes ci-dessus
        StreamScreen.kt             # Écran principal Compose (Scaffold, navigation, session, contrôles)
        RecordingListScreen.kt      # Liste/recherche/partage/renommage (en-têtes de jour épinglés)
        DetailScreen.kt             # Fiche : lecteur synchronisé, segments par intervenant, actions
        DossierScreen.kt            # Fiche dossier : enregistrements, actions ouvertes, renommer/fusionner, export
        SettingsScreen.kt           # Réglages en sections
        PinScreen.kt / Biometrics.kt # Verrouillage PIN + BiometricPrompt
        UiComponents.kt             # Composants partagés (cartes, pastilles, puces, états vides…)
        theme/Theme.kt              # Palette M3 complète clair/sombre, typographie, formes
        theme/AppIcons.kt           # Icônes vectorielles (Material Symbols) hors icons-core
```

## Build

### 1. Bibliothèque native (compilée par le build)

`whisper.cpp` est un sous-module git (`app/src/main/cpp/whisper.cpp`, épinglé sur v1.9.4) compilé par Gradle
(`externalNativeBuild`, NDK r27, CMake 3.22) avec le pont JNI dans une seule `libwhisper.so`
(arm64-v8a, socle `armv8-a`, sans OpenMP, pages de 16 Ko). Après un clone :

```bash
git submodule update --init --recursive
rm -rf app/src/main/jniLibs   # anciens binaires pré-compilés d'un clone antérieur : ils feraient doublon
```

Le workflow `.github/workflows/native.yml` compile la bibliothèque seule (artefact `libwhisper.so`) à chaque
changement du code natif ; la CI et la publication la compilent avec l'APK.

### 2. APK

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### CI / Release

- `.github/workflows/ci.yml` : compile l'APK debug et lance les tests unitaires à chaque push (sans les binaires natifs — vérification de compilation).
- `.github/workflows/release.yml` (tag `v*` ou lancement manuel) : construit l'**APK release signé complet** — les `.so` et le modèle `ggml-base.bin` sont extraits de la release v0.2.5 (inchangés depuis) — vérifie la signature (secrets `TRANSCRIPTO_STREAM_KEYSTORE_*`) et publie la release GitHub avec `RELEASE_NOTES.md`. C'est cette release que l'auto-updater de l'app télécharge.

## Binaires non versionnés

- Le modèle `ggml-base.bin` est dans `.gitignore` (142 Mo) : un clone frais compile la bibliothèque native mais ne peut pas produire un APK **fonctionnel en mode Whisper** sans le modèle. Les releases GitHub contiennent l'APK complet ; le workflow de publication reprend le modèle de la release v0.2.5.

## Roadmap

- [x] PoC : transcription en temps réel (fenêtre glissante)
- [x] Sauvegarde/export des transcriptions (.txt, .srt, partage)
- [x] Comptage de temps par intervenant (CAC/audit) — v1 par pitch
- [x] Marqueurs pendant l'enregistrement, édition du transcript, tuile + raccourci
- [x] Import d'audio externe (WhatsApp, dictaphone) vers la transcription différée
- [x] Export des audios (WAV) vers l'emplacement choisi (SAF)
- [x] Catalogue de modèles téléchargeables (small/medium/large-v3-turbo quantisés) + re-transcription haute fidélité
- [x] Sauvegarde chiffrée exportable (migration d'appareil), écran détail synchronisé, résilience audio, mode dictée
- [x] Synthèse de fin d'enregistrement (locale + IA Claude en option)
- [x] Intervenants nommés, dossiers, correction sur la fiche, gabarits de synthèse par mission, questions à l'IA, exports Word/PDF, chiffrement des textes, biométrie, verrouillage automatique, journal des incidents (v0.9.0)
- [x] Recherche instantanée dans tous les passages (index en mémoire, sans base persistée en clair), chapitres automatiques, Silero VAD (v0.10.0)
- [x] Refonte du code en collaborateurs testables, un seul chemin d'appel Claude, R8 en release, tests Robolectric, corrections synchronisées avec les passages (v0.11.0)
- [ ] Chaînes de l'interface externalisées dans `strings.xml` (préparation d'une version anglaise)
- [x] Suivi des actions, fiche dossier, import par lots, rappel de sauvegarde (v0.12.0)
- [x] Compilation native en CI (sous-module whisper.cpp), confiance par passage, relecture assistée (v0.13.0)
- [ ] Diarisation v2 par empreintes de locuteurs (modèle ONNX de locuteur, Fbank, regroupement, mémoire des voix par dossier)
