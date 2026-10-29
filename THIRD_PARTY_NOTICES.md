# Third-party notices

The app bundles these open-source libraries. Each is used under its own license.

| Library | Version | License |
|---|---|---|
| [OkHttp](https://github.com/square/okhttp) | 4.12.0 | Apache License 2.0 |
| [Okio](https://github.com/square/okio) (via OkHttp) | 3.x | Apache License 2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) (via OkHttp) | 1.9.x | Apache License 2.0 |
| [ZXing core](https://github.com/zxing/zxing) | 3.5.3 | Apache License 2.0 |
| [AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core) | 1.16.0 | Apache License 2.0 |
| [AndroidX Wear Ongoing](https://developer.android.com/jetpack/androidx/releases/wear) | 1.0.0 | Apache License 2.0 |

The “Hello Grok” wake word engine (`app/src/main/cpp`, built into `libgrokwake.so`) includes:

| Code | Version | License |
|---|---|---|
| [Home Assistant Companion for Android](https://github.com/home-assistant/android) `microwakeword` module (engine and JNI code, adapted) | main, 2026 | Apache License 2.0 |
| [TensorFlow Lite Micro](https://github.com/tensorflow/tflite-micro) and its audio frontend | 2747abd5 | Apache License 2.0 |
| [KISS FFT](https://github.com/mborgerding/kissfft) | 131.2.0 | BSD 3-Clause |
| [FlatBuffers](https://github.com/google/flatbuffers) | 23.5.26 | Apache License 2.0 |
| [gemmlowp](https://github.com/google/gemmlowp) | fda83bdc | Apache License 2.0 |
| [ruy](https://github.com/google/ruy) | 54774a7a | Apache License 2.0 |

The second “Hello Grok” check (`HelloCheck`) uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.8 (Apache License 2.0): its Java API classes in `app/src/main/java/com/k2fsa/sherpa/onnx` (unchanged except where marked “Changed for Grok Wear”) and `libsherpa-onnx-jni.so` in `app/src/main/jniLibs`, built from source with text-to-speech and speaker diarization turned off (`BUILD_SHARED_LIBS=OFF SHERPA_ONNX_ENABLE_TTS=OFF SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF ./build-android-armv7-eabi.sh`, and `build-android-arm64-v8a.sh`, with NDK 29.0.13113456). That library includes:

| Code | Version | License |
|---|---|---|
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (static library, with the components in its ThirdPartyNotices) | 1.28.2 | MIT License |
| [kaldi-native-fbank](https://github.com/csukuangfj/kaldi-native-fbank) | 1.22.3 | Apache License 2.0 |
| [kaldi-decoder](https://github.com/k2-fsa/kaldi-decoder) and [kaldifst](https://github.com/k2-fsa/kaldifst) | 0.3.0 | Apache License 2.0 |
| [OpenFst](https://www.openfst.org) ([k2-fsa fork](https://github.com/csukuangfj/openfst)) | 1.8.5 | Apache License 2.0 |
| [simple-sentencepiece](https://github.com/pkufool/simple-sentencepiece) | 0.7 | Apache License 2.0 |
| [KISS FFT](https://github.com/mborgerding/kissfft) | as fetched by kaldi-native-fbank | BSD 3-Clause |
| [Eigen](https://eigen.tuxfamily.org) (headers) | 5.0.1 | Mozilla Public License 2.0 |
| [JSON for Modern C++](https://github.com/nlohmann/json) (headers) | 3.12.0 | MIT License |

The keyword model in `app/src/main/assets/kws` is sherpa-onnx's `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (Apache License 2.0), trained by the k2-fsa project on [GigaSpeech](https://github.com/SpeechColab/GigaSpeech).

The engine runs a model trained with [microWakeWord](https://github.com/OHF-Voice/micro-wake-word) (Apache License 2.0); the model itself is licensed for non-commercial use only, as described in [NOTICE.md](NOTICE.md).

The repository also contains the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) (`gradle/wrapper/gradle-wrapper.jar`, Apache License 2.0).

Test-only dependencies are not shipped in the app: [JUnit 4](https://junit.org/junit4/) (Eclipse Public License 1.0) and [JSON-java](https://github.com/stleary/JSON-java) (Public Domain).

The full text of the Apache License 2.0 is available at <https://www.apache.org/licenses/LICENSE-2.0>.

For the Bot avatar design data, which is not open source, see [NOTICE.md](NOTICE.md).
