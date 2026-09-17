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
  `summary`, `export`, `audio` hors Android — dont `Fbank`, `SpeakerClustering`,
  `SpeakerDiarizer`, `VoiceEmbedder` —, `stt/ModelCatalog`, `stt/VoiceCommands`)
  se compilent et se testent en JVM avec `kotlinc` 2.0.21 + JUnit 4.13.2 +
  `org.json` + `kotlinx-coroutines-core-jvm` + les jars du SDK Anthropic. Il faut
  deux stubs : `stt/SegmentData` + `StreamResult` (copie des data classes de
  `WhisperStreamEngine.kt`) et `android.util.Log`. Vérifier le code de retour de
  JUnit (`${PIPESTATUS[0]}`), pas la sortie filtrée.
- **Équilibre des accolades** : sans compilateur Android, passer un vérificateur
  d'accolades sur chaque fichier `.kt` modifié avant de pousser.
- **Jamais** désactiver la vérification TLS ni contourner le proxy.
- **Code natif** : `whisper.cpp` est un sous-module (`app/src/main/cpp/whisper.cpp`, v1.9.4)
  compilé par `externalNativeBuild` (CMake `app/src/main/cpp/CMakeLists.txt`, NDK r27) ;
  le workflow `native.yml` compile la bibliothèque seule et publie `libwhisper.so` en
  artefact. Pas de NDK en session : la CI est la seule compilation native aussi.
  Le pont JNI émet par segment la confiance (`c`, probabilité moyenne des jetons) et la
  probabilité de non-parole (`nsp`). Socle `armv8-a` (pas de dotprod/fp16 : SIGILL sur les
  Cortex-A72/A73) ; piste : seconde variante « +dotprod+fp16 » choisie d'après
  `/proc/cpuinfo`. Un ancien `app/src/main/jniLibs/` local doit être supprimé (doublon).
- **Publication** : le push de tags échoue depuis la session (proxy) ; déclencher
  `release.yml` par `workflow_dispatch` sur `main` (outil GitHub `actions_run_trigger`),
  le workflow crée le tag et la release lui-même.

## État du plan (septembre 2026)

- Vagues A (v0.11.0), B (v0.12.0) et C (v0.13.0 + v0.14.0) faites : A5 (chaînes dans
  `strings.xml`) et C3 (diarisation par empreintes vocales) livrés en v0.14.0.
- C3 : modèle WeSpeaker CAM++ VoxCeleb fp32 (28 Mo) pris sur la release
  `speaker-recongition-models` de sherpa-onnx (GitHub, accessible ; Hugging Face bloqué),
  téléchargé par `ci.yml`/`release.yml` dans `app/src/main/assets/speaker/` (gitignoré) avec
  SHA-256 vérifié. Les variantes int8 dynamique (cos 0,86 vs fp32) et fp16 (échec de chargement
  ORT) ont été rejetées. Validation JVM : `SpeakerEmbedder` compilé avec `onnxruntime-android`
  (`classes.jar`) et exécuté avec le runtime ONNX de bureau ; empreinte identique à la référence
  numpy (cos 1,0000). Le `.meta` porte les empreintes (`voices`, 4 décimales, ~3,5 Ko par
  intervenant).
- Pistes restantes : seconde variante native « +dotprod+fp16 » choisie d'après `/proc/cpuinfo` ;
  test du PDF via `PdfRenderer` sous Robolectric ; validation sur appareil de v0.13.0/v0.14.0.

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
  - `audio/SpeakerDiarizer` + `SpeakerEmbedder` (ONNX, chargé à la première transcription
    différée, fermé dans `onCleared`) + `SpeakerClustering` + `Fbank` — intervenants par
    empreintes vocales dans `transcribeStored`, repli `PitchDiarizer` ; mémoire des voix via
    `RecordingRepository.knownVoices` et `SpeakerDiarizer.recognise` (≥ 0,75).
  - `summary/ActionExtractor` (actions à mener), `data/ReviewMarks` (relecture assistée),
    `ui/DossierScreen` (fiche dossier), import par lots dans le ViewModel + `RecordingService`.
  - `export/DocumentExporter`, `export/ShareComposer`.
- Interfaces d'injection pour les tests : `KeyProvider` (clé des textes scellés),
  `WavCipher` (chiffrement des WAV), `WindowTranscriber`, `FrameVad`, `ClaudeTransport`,
  `LiveSettings`, `AiSettings`, `VoiceEmbedder`.

## Conventions de fichiers

- Un enregistrement = `base.wav` (clair), `base.wav.enc` (chiffré AndroidKeyStore)
  ou `base.txt` seul (moteur Google), plus les frères `.txt`, `.srt`, `.json`
  (segments), `.md` (synthèse), `.meta` (JSON : intervenants, dossier, gabarit,
  chapitres, drapeau `stale`, actions, empreintes `voices`).
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
