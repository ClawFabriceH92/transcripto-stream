# Règles R8 de Transcripto Stream (variante release uniquement).
# Principe : tout ce qui est atteint par réflexion ou par du code natif est conservé
# tel quel ; le reste (Compose, coroutines, OkHttp…) vient avec ses propres règles.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# --- JNI : whisper.cpp (libwhisper.so) et ONNX Runtime (Silero VAD) ---
# Les méthodes natives gardent leur nom (le .so les résout par nom Java).
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.transcripto.stream.stt.WhisperStreamEngine { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- SDK Anthropic (synthèse IA) : modèles sérialisés par Jackson via annotations ---
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class kotlin.Metadata { *; }
# Dépendances annexes du SDK, référencées mais absentes sur Android
-dontwarn com.google.errorprone.annotations.**
-dontwarn io.swagger.v3.oas.annotations.**
-dontwarn com.standardwebhooks.**
-dontwarn javax.annotation.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.slf4j.**
# Générateur de schémas JSON embarqué par le SDK (outils) : jamais exécuté sur Android
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedArrayType
-dontwarn java.lang.reflect.AnnotatedTypeVariable
-dontwarn java.lang.reflect.AnnotatedWildcardType

# --- org.json (JSON des segments, .meta, catalogue) : classes de la plateforme, intactes ---
-keep class org.json.** { *; }

# --- Journal des plantages : les traces doivent rester lisibles ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
