package si.zya.grokwear;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.Arrays;

/**
 * Listens for "Hello Grok" on its own thread, entirely on the watch. The wake word model proposes, then
 * HelloCheck confirms on the last two seconds and the moment after, held only in memory for that check.
 * Once confirmed it stops detecting and keeps up to six seconds from the read it was heard in, for
 * HelloGrok.takeCarry; nothing earlier is kept.
 */
final class HotwordListener implements HelloGrok.Carry {
    interface Callback{void heard(float peak);void failed();}
    /** 160 ms reads: about six wakeups a second, and a detection still lands within a fraction of a second. */
    private static final int READ=WakeWord.SAMPLE_RATE*160/1000,CARRY=WakeWord.SAMPLE_RATE*6;
    private static final int BEFORE=WakeWord.SAMPLE_RATE*HelloCheck.BEFORE_MS/1000,AFTER=WakeWord.SAMPLE_RATE*HelloCheck.AFTER_MS/1000;
    private final Context context;private final Callback callback;
    private final Object lock=new Object();
    private final short[] carried=new short[CARRY];private int carriedLength;private boolean carrying;
    private volatile boolean running;private volatile AudioRecord record;private Thread thread;

    HotwordListener(Context context,Callback callback){this.context=context.getApplicationContext();this.callback=callback;}
    boolean running(){return running;}
    boolean carrying(){synchronized(lock){return carrying;}}
    synchronized void start(){
        if(running)return;running=true;synchronized(lock){carrying=false;carriedLength=0;}
        thread=new Thread(this::run,"grok-hello");thread.start();
    }
    /** Frees the microphone; the thread releases it and the model as soon as its read returns. */
    synchronized void stop(){
        running=false;Thread t=thread;thread=null;AudioRecord r=record;
        if(r!=null)try{r.stop();}catch(RuntimeException ignored){}
        if(t!=null)t.interrupt();
        synchronized(lock){carrying=false;carriedLength=0;Arrays.fill(carried,(short)0);}
    }
    @Override public short[] take(){
        short[] out;synchronized(lock){if(!carrying)return null;out=Arrays.copyOf(carried,carriedLength);}
        stop();return out;
    }
    /** Debug builds only: files/hello-fixture.pcm (16 kHz mono PCM16, pushed with run-as) is used once, then deleted. */
    private short[] debugFixture(){
        if(!BuildConfig.DEBUG)return null;java.io.File file=new java.io.File(context.getFilesDir(),"hello-fixture.pcm");
        if(!file.isFile()||file.length()>WakeWord.SAMPLE_RATE*2*60)return null;
        try{byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());short[] out=new short[bytes.length/2];
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);return out;}
        catch(java.io.IOException unreadable){return null;}finally{file.delete();}
    }
    private void run(){
        AudioRecord r=null;WakeWord word=null;short[] buffer=new short[READ],candidate=new short[READ+AFTER+READ];int failedReads=0;
        try{
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone not allowed");
            word=WakeWord.open(context);
            Thread loading=new Thread(()->HelloCheck.load(context),"grok-hello-check");loading.start(); // Ready long before it's needed.
            int min=AudioRecord.getMinBufferSize(WakeWord.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(WakeWord.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min,WakeWord.SAMPLE_RATE*2*2)).build(); // Two seconds: reads wait while the check runs.
            if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
            record=r;if(!running)return;r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone busy");
            short[] fixture=debugFixture();int fixtureAt=0;
            // The last two seconds, oldest at historyAt; and a proposal from the wake word model waiting for the check.
            short[] history=new short[BEFORE],before=null;int historyAt=0,candidateLength=0,detectionRead=0;float candidatePeak=0;
            while(running){
                int n=r.read(buffer,0,READ,AudioRecord.READ_BLOCKING);
                if(!running)break;
                // Debug builds on the emulator: a test clip stands in for the microphone, then silence.
                if(fixture!=null&&n>0){boolean ending=fixtureAt<fixture.length;for(int i=0;i<n;i++)buffer[i]=fixtureAt<fixture.length?fixture[fixtureAt++]:0;
                    if(ending&&fixtureAt>=fixture.length)android.util.Log.i("GrokWearHello","fixture_done samples="+fixture.length+" peak="+Math.round(word.peak()*100)+" heard="+word.heardPercent()+"%");}
                // A watch call or another app can hold the microphone for a moment; only a long outage stops listening.
                if(n<=0){if(++failedReads>=25)throw new IllegalStateException("Microphone stopped");Thread.sleep(160);continue;}
                failedReads=0;
                synchronized(lock){if(carrying){int room=CARRY-carriedLength;if(room>0){int keep=Math.min(room,n);System.arraycopy(buffer,0,carried,carriedLength,keep);carriedLength+=keep;}continue;}}
                for(int i=0;i<n;i++){history[historyAt]=buffer[i];historyAt=(historyAt+1)%BEFORE;}
                // Just after a detection the model is cooling off, but it still hears every read, so a turned-down proposal leaves no gap.
                boolean hit=word.hears(buffer,n);
                if(before!=null){
                    int keep=Math.min(n,candidate.length-candidateLength);System.arraycopy(buffer,0,candidate,candidateLength,keep);candidateLength+=keep;
                    if(candidateLength<detectionRead+AFTER)continue;
                    if(confirmed(before,candidate,detectionRead,candidatePeak)){
                        synchronized(lock){carrying=true;System.arraycopy(candidate,0,carried,0,candidateLength);carriedLength=candidateLength;}
                        callback.heard(candidatePeak);}
                    Arrays.fill(before,(short)0);before=null;continue;
                }
                // The detector fires a moment after the phrase ends, so the rest of this read is already the question.
                if(hit){candidatePeak=word.peak();before=new short[BEFORE];System.arraycopy(history,historyAt,before,0,BEFORE-historyAt);System.arraycopy(history,0,before,BEFORE-historyAt,historyAt);
                    System.arraycopy(buffer,0,candidate,0,n);candidateLength=n;detectionRead=n;}
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Exception failure){if(running){running=false;callback.failed();}}
        finally{
            if(record==r)record=null;Arrays.fill(buffer,(short)0);Arrays.fill(candidate,(short)0); // A restart may already own the field.
            if(r!=null){try{r.stop();}catch(RuntimeException ignored){}r.release();}
            if(word!=null){android.util.Log.i("GrokWearHello","listen_heard="+word.heardPercent()+"% peak="+Math.round(word.peak()*100));word.close();}
            HelloCheck.releaseLater();
        }
    }

    /** HelloCheck on the two seconds up to the detection and the moment after; if it can't run here, only a clear detection counts. */
    private boolean confirmed(short[] before,short[] candidate,int detectionRead,float peak){
        short[] heard=Arrays.copyOf(before,BEFORE+AFTER);System.arraycopy(candidate,detectionRead,heard,BEFORE,AFTER);
        long at=android.os.SystemClock.elapsedRealtime();String said=HelloCheck.hears(context,heard,heard.length,peak);Arrays.fill(heard,(short)0);
        boolean ok=said!=null?!said.equals("no"):peak>=WakeWord.CUTOFF_ALONE;
        android.util.Log.i("GrokWearHello","check="+(said==null?"unavailable":said)+" peak="+Math.round(peak*100)+" ms="+(android.os.SystemClock.elapsedRealtime()-at));
        return ok;
    }
}
