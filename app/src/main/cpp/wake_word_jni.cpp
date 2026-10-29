// Adapted from Home Assistant Companion for Android (microwakeword module, Apache License 2.0):
// https://github.com/home-assistant/android/tree/main/microwakeword
// Changed for Grok Wear: binds to si.zya.grokwear.WakeWord, reads a slice of a reused buffer, adds nativePeak,
// passes a start-up warm-up, and puts a QuietGate in front of the engine.
#include <jni.h>
#include <memory>

#include "Logging.h"
#include "MicroWakeWordEngine.h"
#include "QuietGate.h"

static constexpr char LOG_TAG[] = "GrokWakeWord";

// One listening session: the engine and the gate that spares it quiet audio.
struct Listener {
    MicroWakeWordEngine engine;
    QuietGate gate;
    Listener(const uint8_t* model, size_t size, int sampleRate, int stepMs, float cutoff, int window, int warmupMs)
        : engine(model, size, sampleRate, stepMs, cutoff, window, warmupMs), gate(sampleRate) {}
};

static jlong nativeCreate(JNIEnv* env, jclass, jobject modelBuffer, jint sampleRate,
                          jint featureStepSizeMs, jfloat probabilityCutoff, jint slidingWindowSize, jint warmupMs) {
    auto* modelData = static_cast<uint8_t*>(env->GetDirectBufferAddress(modelBuffer));
    jlong modelSize = env->GetDirectBufferCapacity(modelBuffer);
    if (modelData == nullptr || modelSize <= 0) {
        LOGE(LOG_TAG, "Model buffer is not a direct ByteBuffer");
        return 0;
    }
    auto listener = std::make_unique<Listener>(modelData, static_cast<size_t>(modelSize),
        static_cast<int>(sampleRate), static_cast<int>(featureStepSizeMs),
        static_cast<float>(probabilityCutoff), static_cast<int>(slidingWindowSize), static_cast<int>(warmupMs));
    if (!listener->engine.isInitialized()) return 0;
    return reinterpret_cast<jlong>(listener.release());
}

static jboolean nativeProcessAudio(JNIEnv* env, jclass, jlong handle, jshortArray samplesArray, jint count) {
    if (handle == 0) return JNI_FALSE;
    jsize length = env->GetArrayLength(samplesArray);
    if (count <= 0 || count > length) return JNI_FALSE;
    // Called every read on the listening thread: no per-call heap allocation.
    jshort* samples = env->GetShortArrayElements(samplesArray, nullptr);
    if (samples == nullptr) return JNI_FALSE;
    auto* listener = reinterpret_cast<Listener*>(handle);
    bool detected = listener->gate.feed(reinterpret_cast<const int16_t*>(samples), static_cast<size_t>(count),
        [listener](const int16_t* heard, size_t n) { return listener->engine.processAudio(heard, n); });
    env->ReleaseShortArrayElements(samplesArray, samples, JNI_ABORT);
    return detected ? JNI_TRUE : JNI_FALSE;
}

static void nativeReset(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    auto* listener = reinterpret_cast<Listener*>(handle);
    listener->engine.reset();
    listener->gate.reset();
}

static jint nativePeak(JNIEnv*, jclass, jlong handle) {
    return handle == 0 ? 0 : reinterpret_cast<Listener*>(handle)->engine.takePeak();
}

static jint nativeHeardPercent(JNIEnv*, jclass, jlong handle) {
    return handle == 0 ? 0 : reinterpret_cast<Listener*>(handle)->gate.takeHeardPercent();
}

static void nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Listener*>(handle);
}

static const JNINativeMethod methods[] = {
    {"nativeCreate", "(Ljava/nio/ByteBuffer;IIFII)J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeProcessAudio", "(J[SI)Z", reinterpret_cast<void*>(nativeProcessAudio)},
    {"nativeReset", "(J)V", reinterpret_cast<void*>(nativeReset)},
    {"nativePeak", "(J)I", reinterpret_cast<void*>(nativePeak)},
    {"nativeHeardPercent", "(J)I", reinterpret_cast<void*>(nativeHeardPercent)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("si/zya/grokwear/WakeWord");
    if (clazz == nullptr || env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE(LOG_TAG, "Could not register WakeWord natives");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
