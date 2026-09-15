#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <algorithm>
#include <thread>
#include <whisper.h>

extern "C" {

static struct whisper_context *g_ctx = nullptr;

// Échappe les caractères JSON spéciaux dans un texte brut
static std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 16);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;
        }
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_transcripto_stream_stt_WhisperStreamEngine_nativeLoadModel(
    JNIEnv *env, jobject /*thiz*/, jstring model_path) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    return reinterpret_cast<jlong>(g_ctx);
}

// Nombre flottant en JSON (point décimal, indépendant de la locale)
static std::string json_float(float v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.3f", v);
    return buf;
}

/**
 * Transcrit un buffer PCM brut (int16 LE, mono, 16 kHz) et retourne un JSON :
 * {"full_text":"...","segments":[{"start_ms":..,"end_ms":..,"text":"..","c":0.87,"nsp":0.02}],"language":"fr"}
 * — « c » : probabilité moyenne des jetons du segment (confiance, -1 si inconnue),
 *   « nsp » : probabilité de non-parole du segment.
 *
 * @param initial_prompt vocabulaire personnalisé injecté comme prompt initial (peut être vide)
 *                        — améliore la reconnaissance des termes métier (noms de clients, CAC, etc.)
 */
JNIEXPORT jstring JNICALL
Java_com_transcripto_stream_stt_WhisperStreamEngine_nativeTranscribeBuffer(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray pcm, jstring language,
    jstring initial_prompt) {

    if (!handle) return env->NewStringUTF("{\"error\":\"model not loaded\"}");
    auto *ctx = reinterpret_cast<whisper_context *>(handle);

    jsize nbytes = env->GetArrayLength(pcm);
    if (nbytes <= 0) {
        return env->NewStringUTF("{\"full_text\":\"\",\"segments\":[],\"language\":\"fr\"}");
    }

    std::vector<jbyte> raw(nbytes);
    env->GetByteArrayRegion(pcm, 0, nbytes, raw.data());

    const int nsamples = static_cast<int>(nbytes / 2);
    std::vector<float> samples(nsamples);
    for (int i = 0; i < nsamples; i++) {
        int16_t s = static_cast<int16_t>(
            (static_cast<uint8_t>(raw[2 * i]) & 0xFF) |
            (static_cast<uint8_t>(raw[2 * i + 1]) << 8));
        samples[i] = s / 32768.0f;
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string lang_copy = lang;

    const char *prompt = nullptr;
    std::string prompt_copy;
    if (initial_prompt != nullptr) {
        prompt = env->GetStringUTFChars(initial_prompt, nullptr);
        if (prompt != nullptr) {
            prompt_copy = prompt;
            // "auto" est le mot-clé côté Kotlin pour la détection automatique
            if (prompt_copy == "auto") prompt_copy.clear();
        }
    }
    // Le prompt initial doit rester alloué pendant whisper_full : on passe notre copie
    const char *effective_prompt = (prompt_copy.empty()) ? nullptr : prompt_copy.c_str();

    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    // "auto" (ou "auto-detect") -> nullptr pour la détection automatique de la langue
    wparams.language = (lang_copy == "auto" || lang_copy == "auto-detect") ? nullptr : lang;
    wparams.n_threads = std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency())));
    wparams.initial_prompt = effective_prompt;
    // Direct et différé : pas de montée de température (latence stable), blancs supprimés,
    // seuil de non-parole pour ne pas inventer de texte sur du silence
    wparams.temperature_inc = 0.0f;
    wparams.suppress_blank = true;
    wparams.no_speech_thold = 0.6f;

    std::string result;
    int ret = whisper_full_parallel(ctx, wparams, samples.data(), nsamples, 1);
    if (ret != 0) {
        result = "{\"error\":\"whisper_full failed\"}";
    } else {
        const int n_segments = whisper_full_n_segments(ctx);
        result = "{\"full_text\":\"";
        for (int i = 0; i < n_segments; i++) {
            result += json_escape(whisper_full_get_segment_text(ctx, i));
        }
        result += "\",\"segments\":[";
        for (int i = 0; i < n_segments; i++) {
            if (i > 0) result += ",";
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
            // Confiance : moyenne des probabilités des jetons de texte (jetons spéciaux exclus)
            const int n_tokens = whisper_full_n_tokens(ctx, i);
            double sum_p = 0.0;
            int n_text = 0;
            for (int j = 0; j < n_tokens; j++) {
                const whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
                if (td.id >= whisper_token_eot(ctx)) continue;
                sum_p += td.p;
                n_text++;
            }
            const float confidence = n_text > 0 ? static_cast<float>(sum_p / n_text) : -1.0f;
            const float no_speech = whisper_full_get_segment_no_speech_prob(ctx, i);
            result += "{\"start_ms\":" + std::to_string(t0 * 10) + ",";
            result += "\"end_ms\":" + std::to_string(t1 * 10) + ",";
            result += "\"text\":\"" + json_escape(whisper_full_get_segment_text(ctx, i)) + "\",";
            result += "\"c\":" + json_float(confidence) + ",";
            result += "\"nsp\":" + json_float(no_speech) + "}";
        }
        result += "],\"language\":\"" + lang_copy + "\"}";
    }

    if (prompt != nullptr) env->ReleaseStringUTFChars(initial_prompt, prompt);
    env->ReleaseStringUTFChars(language, lang);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_transcripto_stream_stt_WhisperStreamEngine_nativeUnloadModel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {

    if (handle) {
        whisper_free(reinterpret_cast<whisper_context *>(handle));
    }
}

} // extern "C"
