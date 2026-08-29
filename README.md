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
- **Sécurité/RGPD** : PIN (saisie masquée), chiffrement WAV AES-256 (clé AndroidKeyStore), rétention automatique 30/60/90 j, contrôle d'espace disque avant enregistrement.
- **Mises à jour** (Réglages) : mise à jour automatique activable/désactivable (vérification GitHub Releases au lancement + quotidienne, téléchargement et installation automatiques), bouton « Vérifier maintenant », aide à l'autorisation d'installation.

## Architecture

```
app/src/main/
├── cpp/whisper_jni.cpp          # JNI : transcription d'un buffer PCM (pas de fichier)
├── assets/models/ggml-base.bin  # Modèle Whisper Base (~142 Mo, gitignoré)
├── jniLibs/arm64-v8a/           # libwhisper.so + libggml*.so + libomp + libc++_shared (gitignorés)
└── java/com/transcripto/stream/
    ├── MainActivity.kt             # Point d'entrée + actions RECORD (tuile/raccourci) et SEND/VIEW (import)
    ├── RecordTileService.kt        # Tuile de réglages rapides « Transcrire »
    ├── RecordingService.kt         # Foreground service (écran éteint)
    ├── audio/PcmAudioRecorder.kt   # AudioRecord → ring buffer
    ├── audio/WavFileWriter.kt      # PCM → WAV conservé
    ├── audio/AudioImporter.kt      # Import externe : MediaCodec → WAV 16 kHz mono
    ├── audio/PcmResampler.kt       # Downmix + rééchantillonnage linéaire (pur, testé)
    ├── data/RecordingNames.kt      # Conventions de nommage (.wav / .wav.enc / .txt / .srt)
    ├── data/CryptoManager.kt       # AES-256-GCM (AndroidKeyStore)
    ├── data/SettingsStore.kt       # Réglages (SharedPreferences)
    ├── export/TranscriptExporter.kt # SRT + stats temps de parole (pur, testé)
    ├── stt/WhisperStreamEngine.kt  # Pont JNI
    ├── stt/ModelCatalog.kt         # Modèles Whisper embarqué/téléchargeables
    ├── stt/GoogleSpeechEngine.kt   # SpeechRecognizer système
    └── ui/StreamViewModel.kt       # Fenêtre glissante + dédup + diarisation + exports
        StreamScreen.kt             # Écran principal Compose (Scaffold + Snackbar)
        RecordingListScreen.kt      # Liste/recherche/partage/renommage
        SettingsScreen.kt           # Réglages
        PinScreen.kt                # Verrouillage PIN
        theme/Theme.kt              # Palette bleue clair/sombre unifiée
```

## Build

### 1. Bibliothèque native (une seule fois, machine avec NDK)

```bash
ANDROID_NDK=/opt/android-sdk/ndk/27.3.13750724 ./scripts/build_native.sh
```

Produit `libwhisper.so` (JNI inclus), `libggml*.so` et `libc++_shared.so` dans `app/src/main/jniLibs/arm64-v8a/`.

> ⚠️ Sur hôte ARM64 (Raspberry Pi), le NDK x86_64 passe par QEMU user-mode — installation requise : `qemu-user qemu-user-binfmt`.

### 2. APK

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### CI / Release

- `.github/workflows/ci.yml` : compile l'APK debug et lance les tests unitaires à chaque push (sans les binaires natifs — vérification de compilation).
- `.github/workflows/release.yml` (tag `v*` ou lancement manuel) : construit l'**APK release signé complet** — les `.so` et le modèle `ggml-base.bin` sont extraits de la release v0.2.5 (inchangés depuis) — vérifie la signature (secrets `TRANSCRIPTO_STREAM_KEYSTORE_*`) et publie la release GitHub avec `RELEASE_NOTES.md`. C'est cette release que l'auto-updater de l'app télécharge.

## Binaires non versionnés

- Le modèle `ggml-base.bin` et les `.so` sont dans `.gitignore` : un clone frais ne peut pas produire un APK **fonctionnel en mode Whisper** directement. Les releases GitHub contiennent l'APK complet.

## Roadmap

- [x] PoC : transcription en temps réel (fenêtre glissante)
- [x] Sauvegarde/export des transcriptions (.txt, .srt, partage)
- [x] Comptage de temps par intervenant (CAC/audit) — v1 par pitch
- [x] Marqueurs pendant l'enregistrement, édition du transcript, tuile + raccourci
- [x] Import d'audio externe (WhatsApp, dictaphone) vers la transcription différée
- [x] Export des audios (WAV) vers l'emplacement choisi (SAF)
- [x] Catalogue de modèles téléchargeables (small/medium/large-v3-turbo quantisés) + re-transcription haute fidélité
- [x] Sauvegarde chiffrée exportable (migration d'appareil), écran détail synchronisé, résilience audio, mode dictée
- [ ] VAD Silero (endpointing par phrases) pour un vrai temps réel
- [ ] Base Room + FTS (segments horodatés persistés, recherche instantanée, tap sur un mot → lecture audio)
- [ ] Export Word (.docx)/PDF structuré (page de garde, sections par intervenant)
- [ ] Sauvegarde chiffrée exportable (migration d'appareil — la clé AndroidKeyStore ne quitte pas le téléphone)
- [ ] Résilience audio : focus audio, appels entrants, préemption micro signalée dans l'UI
- [ ] Diarisation v2 par embeddings de locuteurs (ONNX)
