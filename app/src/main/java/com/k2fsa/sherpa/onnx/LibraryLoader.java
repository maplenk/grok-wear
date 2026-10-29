// Changed for Grok Wear: sherpa-onnx's desktop loader unpacks the library from its jar. On Android the
// library ships in the APK, so this only loads it, once.

package com.k2fsa.sherpa.onnx;

public class LibraryLoader {
    private static boolean loaded;

    public static synchronized void maybeLoad() {
        if (loaded) {
            return;
        }
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }
}
