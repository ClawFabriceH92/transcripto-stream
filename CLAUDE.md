# Transcripto Stream — guide du dépôt pour les sessions Claude

Application Android (Kotlin, Jetpack Compose) de transcription en temps réel pour un
cabinet d'expertise comptable / commissariat aux comptes : moteur Google
(SpeechRecognizer) ou whisper.cpp local (JNI), enregistrements WAV, synthèses
locales ou par Claude, exports Word/PDF, chiffrement au repos. Interface et
messages en français.

## Ce qu'il faut savoir avant de toucher au code

- **Pas de SDK Android dans l'environnement de session** (`dl.google.com` bloqué) :
  la CI GitHub Actions (`.github/workflows/ci.yml`) est la seule compilation.
  Elle enchaîne `assembleDebug`, `testDebugUnitTest` et `assembleRelease` (R8).
  En cas d'échec, un extrait des erreurs est publié en **commentaire de commit**,
  lisible anonymement : `GET /repos/{owner}/{repo}/commits/{sha}/comments`.
  État des runs : `GET /repos/{owner}/{repo}/actions/runs?branch=<branche>&per_page=3`.
- **Vérification locale avant de pousser** : les classes pures (paquets `data`,
  `summary`, `export`, `audio` hors Android, `stt/ModelCatalog`, `stt/VoiceCommands`)
  se compilent et se testent en JVM avec `kotlinc` 2.0.21 + JUnit 4.13.2 +
  `org.json` + `kotlinx-coroutines-core-jvm` + les jars du SDK Anthropic. Il faut
  deux stubs : `stt/SegmentData` + `StreamResult` (copie des data classes de
  `WhisperStreamEngine.kt`) et `android.util.Log`. Vérifier le code de retour de
  JUnit (`${PIPESTATUS[0]}`), pas la sortie filtrée.
- **Équilibre des accolades** : sans compilateur Android, passer un vérificateur
  d'accolades sur chaque fichier `.kt` modifié avant de pousser.
- **Jamais** désactiver la vérification TLS ni contourner le proxy.

## Architecture (après la refonte v0.11.0)

- `ui/StreamViewModel.kt` — navigation, verrouillage, réglages, flux d'état,
  capture micro et finalisation d'un enregistrement. Il **délègue** à :
  - `data/RecordingRepository` — dossier `recordings/` : liste, `.meta`, renommage,
    suppression, rétention, écriture du `.txt`, synthèse, durée, sources de l'index.
  - `audio/LiveTranscriber` — ring buffer, VAD (`FrameVad` : Silero), fenêtres,
    boucle de transcription, fusion du texte, marqueurs.
  - `audio/PlaybackController`, `audio/AudioFocusGuard`, `audio/AudioTransfer`.
  - `data/BackupManager` — archive `.tsbk` (zip AES-GCM par phrase de passe).
  - `summary/AiAssistant` — synthèse, questions, chapitres ; appels Claude via
    `summary/ClaudeCall.kt` (`ClaudeRequest`, `ClaudeTransport`, `SdkClaudeTransport`).
  - `stt/ModelManager` — modèle Whisper actif, catalogue, téléchargements.
  - `export/DocumentExporter`, `export/ShareComposer`.
- Interfaces d'injection pour les tests : `KeyProvider` (clé des textes scellés),
  `WavCipher` (chiffrement des WAV), `WindowTranscriber`, `FrameVad`, `ClaudeTransport`,
  `LiveSettings`, `AiSettings`.

## Conventions de fichiers

- Un enregistrement = `base.wav` (clair), `base.wav.enc` (chiffré AndroidKeyStore)
  ou `base.txt` seul (moteur Google), plus les frères `.txt`, `.srt`, `.json`
  (segments), `.md` (synthèse), `.meta` (JSON : intervenants, dossier, gabarit,
  chapitres, drapeau `stale`).
- `.txt` = en-tête (`Transcripto Stream`, `Date`, `Durée`, `Chiffré`, `SHA-256 (PCM)`)
  puis `----` puis le texte. Le texte garde les libellés génériques
  `[Intervenant N]` ; les noms sont appliqués à l'affichage (`SpeakerNames`).
- Horodatages : `TranscriptExporter.formatHms` partout (`mm:ss`, `hh:mm:ss` au-delà
  d'une heure).
- Toute lecture/écriture de texte passe par `TextVault` (scellement `TSV1` si le
  chiffrement des textes est actif). Les copies en clair vont dans `cacheDir/exports`
  et sont purgées au démarrage.

## Cycle de livraison

1. Implémenter → tests JVM locaux → revue adversariale (agent) → corriger.
2. Commit sur la branche de travail, push, CI verte.
3. Version : `versionCode`/`versionName` dans `app/build.gradle.kts`, notes dans
   `RELEASE_NOTES.md`, README à jour.
4. Publication : fusion `main` (fast-forward) puis tag `vX.Y.Z` poussé — le workflow
   `release.yml` construit l'APK signé et publie la release GitHub
   (`https://github.com/ClawFabriceH92/transcripto-stream/releases/download/vX.Y.Z/transcripto-stream-vX.Y.Z.apk`).
