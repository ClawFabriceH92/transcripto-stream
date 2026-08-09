# Transcripto Stream

Application Android **minimale** de transcription vocale **en temps réel**, 100 % locale (whisper.cpp embarqué, aucun réseau).

> Repart de zéro depuis transcripto-local : un seul écran, un bouton micro, le texte arrive en direct pendant la parole.

## Fonctionnement

- Capture audio continue **PCM 16 kHz mono** (AudioRecord)
- **Fenêtre glissante avec avance réelle** : on transcrit tout le nouvel audio (chevauchement 1 s), pas une fenêtre fixe — le texte avance même avec un modèle lent
- **Conservation audio** : chaque enregistrement est écrit en WAV (`filesDir/recordings/`) et reste disponible après l'arrêt
- **Transcription différée** : bouton « Transcrire l'audio enregistré » → whisper traite le fichier complet (meilleure qualité que le live)
- **Déduplication** du chevauchement entre fenêtres → le texte s'accumule sans doublons
- Compteur de transcriptions + dernière fenêtre brute affichés (debug)

## Architecture

```
app/src/main/
├── cpp/whisper_jni.cpp          # JNI : transcription d'un buffer PCM (pas de fichier)
├── assets/models/ggml-base.bin  # Modèle Whisper Base (~142 Mo, gitignoré)
├── jniLibs/arm64-v8a/           # libwhisper.so + libggml*.so + libomp + libc++_shared (gitignorés)
└── java/com/transcripto/stream/
    ├── MainActivity.kt
    ├── audio/PcmAudioRecorder.kt   # AudioRecord → ring buffer
    ├── audio/WavFileWriter.kt      # PCM → WAV conservé
    ├── stt/WhisperStreamEngine.kt  # Pont JNI
    └── ui/StreamViewModel.kt       # Fenêtre glissante + dédup + transcription différée
        StreamScreen.kt             # Écran unique Compose
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

## Binaires non versionnés

- Le modèle `ggml-tiny.bin` et les `.so` sont dans `.gitignore` : un clone frais ne peut pas builder l'APK directement (comme transcripto-local). Les releases GitHub contiennent l'APK complet.

## Roadmap

- [x] PoC : transcription en temps réel (fenêtre glissante)
- [ ] VAD stream (silero) pour un vrai temps réel
- [ ] Sauvegarde/export des transcriptions
- [ ] Comptage de temps par intervenant (CAC/audit)
