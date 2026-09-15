# Bibliothèque native de Transcripto Stream : whisper.cpp + ggml (statiques) et le pont
# JNI, liés dans une seule libwhisper.so (nom attendu par System.loadLibrary("whisper")).
# Compilée par le workflow .github/workflows/native.yml (NDK r27, arm64-v8a) ; les
# builds Gradle continuent d'utiliser les .so pré-compilés tant que la chaîne n'est
# pas branchée sur externalNativeBuild (voir CLAUDE.md, vague C).
cmake_minimum_required(VERSION 3.22)
project(transcripto_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp et ggml en statique : pas de libggml*.so ni de libomp à embarquer
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Socle armv8-a : les noyaux ggml choisissent leurs instructions à la compilation, et
    # « armv8.2-a+dotprod+fp16 » planterait (SIGILL) les Cortex-A72/A73 (Snapdragon 835,
    # Kirin 970…) encore sous Android 10/11. Piste de performance : une seconde variante
    # « +dotprod+fp16 » choisie à l'exécution d'après /proc/cpuinfo (asimddp, fphp).
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
set_target_properties(whisper_jni PROPERTIES OUTPUT_NAME "whisper")
target_link_libraries(whisper_jni PRIVATE whisper log)
# Pages mémoire de 16 Ko (Android 15+, exigence Google Play) : alignement des segments ELF
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")
