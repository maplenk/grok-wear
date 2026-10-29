package si.zya.grokwear;

import android.content.Context;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/**
 * The second check on "Hello Grok". When the wake word model fires, a small on-device speech model
 * (sherpa-onnx keyword spotting, assets/kws) listens again to the same moment and must hear "hello grog"
 * or "hello grock", which is how it hears "grok". It misses about half of those, so a plain "hello" also
 * counts when the wake word model was sure (HELLO_ALONE). Speech that fools the wake word model rarely holds
 * a "hello", so this turns most false wakes away and lets the wake word model be less strict, which catches
 * more of the real ones. Nothing leaves the watch.
 */
final class HelloCheck {
    private HelloCheck(){}
    /** The audio it hears: before the moment the wake word model fired, and a little after, as that fires early. */
    static final int BEFORE_MS=2000,AFTER_MS=300;
    /** The wake word model's peak at which a plain "hello" is enough: about as many near-misses as its 0.9 alone, and none of the random speech. */
    static final float HELLO_ALONE=0.85f;
    // keywords.txt holds "hello grog" and "hello grock", which every check listens for; the second check adds this.
    private static final String HELLO="▁HE LL O :2.0 #0.1 @HELLO";
    /** Kept this long after listening stops: wrist raises come in runs, and loading takes a moment. */
    private static final long KEEP_MS=5*60_000;
    private static KeywordSpotter spotter;private static boolean unavailable;private static android.os.Handler main;
    private static final Runnable releaseNow=()->new Thread(HelloCheck::release,"grok-hello-check").start();

    /** Loads the model, kept until release; false if it can't run on this watch. Slow: call it off the main thread. */
    static synchronized boolean load(Context context){
        if(main!=null)main.removeCallbacks(releaseNow);
        if(spotter!=null)return true;if(unavailable)return false;
        long at=android.os.SystemClock.elapsedRealtime();
        try{
            OnlineTransducerModelConfig transducer=OnlineTransducerModelConfig.builder().setEncoder("kws/encoder.int8.onnx").setDecoder("kws/decoder.int8.onnx").setJoiner("kws/joiner.int8.onnx").build();
            OnlineModelConfig model=OnlineModelConfig.builder().setTransducer(transducer).setTokens("kws/tokens.txt").setNumThreads(1).setDebug(false).build();
            // Keyword score and threshold as tuned on test clips; each keyword line repeats them.
            KeywordSpotterConfig config=KeywordSpotterConfig.builder().setOnlineModelConfig(model).setKeywordsFile("kws/keywords.txt").setKeywordsScore(2f).setKeywordsThreshold(0.1f).setNumTrailingBlanks(1).build();
            spotter=new KeywordSpotter(context.getAssets(),config);
            android.util.Log.i("GrokWearHello","check_loaded ms="+(android.os.SystemClock.elapsedRealtime()-at));return true;
        }catch(RuntimeException|LinkageError failure){unavailable=true;android.util.Log.w("GrokWearHello","check_unavailable "+failure.getClass().getSimpleName());return false;}
    }
    /** Frees the model's memory (tens of MB) if listening doesn't start again soon; the next load takes it back. */
    static synchronized void releaseLater(){if(main==null)main=new android.os.Handler(android.os.Looper.getMainLooper());main.removeCallbacks(releaseNow);main.postDelayed(releaseNow,KEEP_MS);}
    private static synchronized void release(){if(spotter!=null){spotter.release();spotter=null;}}
    /** What it hears in this 16 kHz audio, given the wake word model's peak: "hello_grok", "hello" or "no"; null if it can't run here. */
    static String hears(Context context,short[] audio,int length,float peak){
        // The watch's voice level is far below the audio the model learned from: bring the loudest moments up to about -20 dBFS.
        float gain=Math.min(100f,0.1f/Math.max(loudest(audio,length),1e-6f));
        float[] samples=new float[length+WakeWord.SAMPLE_RATE/2]; // Trailing silence lets it finish the last word.
        for(int i=0;i<length;i++)samples[i]=Math.max(-1f,Math.min(1f,audio[i]/32768f*gain));
        synchronized(HelloCheck.class){ // Loads here if it isn't yet, and a stopping listener can't release it mid-check.
            if(!load(context))return null;
            // Separately: listening for both at once, "hello" ends first and would hide a "hello grog".
            if(spots(samples,""))return "hello_grok";
            return peak>=HELLO_ALONE&&spots(samples,HELLO)?"hello":"no";
        }
    }
    /** Keywords on top of keywords.txt's, "/"-separated; empty for just those. */
    private static boolean spots(float[] samples,String keywords){
        OnlineStream stream=spotter.createStream(keywords);
        try{
            stream.acceptWaveform(samples,WakeWord.SAMPLE_RATE);stream.inputFinished();
            while(spotter.isReady(stream)){spotter.decode(stream);if(!spotter.getResult(stream).getKeyword().isEmpty())return true;}
            return false;
        }finally{stream.release();}
    }
    /** Mean level (0–1 of full scale) of the 20 loudest 10 ms frames. */
    static float loudest(short[] audio,int length){
        int frame=WakeWord.SAMPLE_RATE/100,frames=length/frame;if(frames==0)return 0;
        float[] levels=new float[frames];
        for(int f=0;f<frames;f++){double sum=0;for(int i=f*frame;i<(f+1)*frame;i++)sum+=(double)audio[i]*audio[i];levels[f]=(float)(Math.sqrt(sum/frame)/32768);}
        java.util.Arrays.sort(levels);int top=Math.min(20,frames);float sum=0;for(int i=frames-top;i<frames;i++)sum+=levels[i];
        return sum/top;
    }
}
