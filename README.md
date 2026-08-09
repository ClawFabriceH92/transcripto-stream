# Transcripto Stream

Application Android **minimale** de transcription vocale **en temps réel**, 100 % locale (whisper.cpp embarqué, aucun réseau).

> Repart de zéro depuis transcripto-local : un seul écran, un bouton micro, le texte arrive en direct pendant la parole.

## Fonctionnement

- Capture audio continue **PCM 16 kHz mono** (AudioRecord)
- **Fenêtre glissante de 4 s** transcrite toutes les ~1,5 s par whisper.cpp (modèle Tiny embarqué)
- **Déduplication** du chevauchement entre fenêtres → le texte s'accumule sans doublons
- Délai d'affichage : ~4 s (le temps de la fenêtre) — évolution prévue vers un vrai streaming VAD

## Architecture

```
app/src/main/
├── cpp/whisper_jni.cpp          # JNI : transcription d'un buffer PCM (pas de fichier)
├── assets/models/ggml-tiny.bin  # Modèle Whisper Tiny (~75 Mo, gitignoré)
├── jniLibs/arm64-v8a/           # libwhisper.so + libggml*.so + libc++_shared.so (gitignorés)
└── java/com/transcripto/stream/
    ├── MainActivity.kt
    ├── audio/PcmAudioRecorder.kt   # AudioRecord → ring buffer
    ├── stt/WhisperStreamEngine.kt  # Pont JNI
    └── ui/StreamViewModel.kt       # Boucle glissante + dédup
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
