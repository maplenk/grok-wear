package si.zya.grokwear;

import android.content.Context;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * The on-device "Hello Grok" detector: microWakeWord's streaming model on TFLite Micro (native, grokwake).
 * Feed 16 kHz mono PCM in order; audio never leaves this object. Not thread-safe: one listening thread.
 * Quiet audio is skipped in native code (QuietGate), so in a quiet room the engine runs a fraction of the time.
 */
final class WakeWord implements AutoCloseable {
    static final int SAMPLE_RATE=16000;
    /** The model and its tuning, as microWakeWord's model manifest describes them. */
    static final String MODEL="wakeword/hello_grok.tflite";
    static final int STEP_MS=10,WINDOW=5;
    /** Less strict than the model's own 0.9, as HelloCheck confirms each detection; 0.9 when that check can't run. */
    static final float CUTOFF=0.7f,CUTOFF_ALONE=0.9f;
    /**
     * Detections are ignored this long after listening starts, while the model has heard little. Short, as
     * listening starts when the screen does: a 1.5 s wait missed nearly every phrase said in the first 0.5 s.
     */
    static final int WARMUP_MS=500;
    static{System.loadLibrary("grokwake");}
    private long handle;

    private WakeWord(long handle){this.handle=handle;}
    /** Loads the bundled model; throws if it can't run on this watch. */
    static WakeWord open(Context context)throws Exception{
        ByteBuffer model;
        try(android.content.res.AssetFileDescriptor fd=context.getAssets().openFd(MODEL);FileInputStream in=fd.createInputStream()){
            // Direct and in native order, as the engine reads it without copying first.
            model=in.getChannel().map(FileChannel.MapMode.READ_ONLY,fd.getStartOffset(),fd.getDeclaredLength());}
        return create(model,CUTOFF,WINDOW);
    }
    /** For tests: a model from a buffer with its own tuning. */
    static WakeWord create(ByteBuffer model,float cutoff,int window){
        if(!model.isDirect()){ByteBuffer direct=ByteBuffer.allocateDirect(model.remaining());direct.put(model.duplicate());direct.flip();model=direct;}
        long handle=nativeCreate(model,SAMPLE_RATE,STEP_MS,cutoff,window,WARMUP_MS);
        if(handle==0)throw new IllegalStateException("Wake word model could not load");
        return new WakeWord(handle);
    }
    /** True when the wake word ends in these samples. After a detection it stays quiet for about 3 s of sound. */
    boolean hears(short[] samples,int count){return handle!=0&&nativeProcessAudio(handle,samples,count);}
    /** Starts over, as after a gap in the audio. */
    void reset(){if(handle!=0)nativeReset(handle);}
    /** Highest model probability (0–1) since the last call; for logs and tuning, never the decision. */
    float peak(){return handle==0?0:nativePeak(handle)/255f;}
    /** Share of the audio since the last call that wasn't quiet and so went through the model, in percent; for logs. */
    int heardPercent(){return handle==0?0:nativeHeardPercent(handle);}
    @Override public void close(){long h=handle;handle=0;if(h!=0)nativeDestroy(h);}

    private static native long nativeCreate(ByteBuffer model,int sampleRate,int stepMs,float cutoff,int window,int warmupMs);
    private static native boolean nativeProcessAudio(long handle,short[] samples,int count);
    private static native void nativeReset(long handle);
    private static native int nativePeak(long handle);
    private static native int nativeHeardPercent(long handle);
    private static native void nativeDestroy(long handle);
}
