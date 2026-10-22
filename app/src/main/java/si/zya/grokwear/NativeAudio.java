package si.zya.grokwear;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory duplex PCM; microphone starts only after the remote session is ready. */
final class NativeAudio implements AutoCloseable {
    interface Listener { void input(byte[] pcm,float level); void output(float level); void drained(); void failure(String safe); }
    private static final int RATE=24000,FRAME=2048;
    private final Context context; private final AudioManager manager; private final Listener listener;
    private final PcmPlaybackBuffer queue=new PcmPlaybackBuffer();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object lock=new Object();
    private AudioRecord record; private volatile AudioTrack track; private AcousticEchoCanceler echo;
    private AudioFocusRequest focus; private Thread capture,playback;
    private final CaptureGate captureGate=new CaptureGate();
    /** A read belongs to one unmuted interval; never accept a result spanning stop/start. */
    static final class CaptureGate {
        private boolean muted=true;private long generation;
        synchronized void mute(boolean value){if(muted!=value){muted=value;generation++;}}
        synchronized long begin(){return muted?-1:generation;}
        synchronized boolean accepts(long ticket){return !muted&&ticket>=0&&ticket==generation;}
        synchronized boolean muted(){return muted;}
    }
    private int oldMode; private boolean changedMode,focusHeld;
    private volatile long framesWritten,framesQueued; private boolean hadOutput;
    NativeAudio(Context context,Listener listener){this.context=context.getApplicationContext();this.manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.listener=listener;}
    void start(boolean initiallyMuted) {
        synchronized(this){userMuted=initiallyMuted;}captureGate.mute(initiallyMuted);
        try {
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                close();listener.failure("Microphone access is required to start voice.");return;
            }
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change->{if(closed.get())return;
                    // Like the official Android client, a short interruption (alarm, timer, assistant)
                    // pauses the call and focus coming back resumes it. A ducking request leaves the
                    // call running; only a permanent loss ends it.
                    if(change==AudioManager.AUDIOFOCUS_GAIN)focusPause(false);
                    else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)focusPause(true);
                    else if(change==AudioManager.AUDIOFOCUS_LOSS)listener.failure("Audio focus was lost. Start a new call to continue.");
                }).build();
            if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException();
            focusHeld=true;oldMode=manager.getMode();
            if(oldMode!=AudioManager.MODE_IN_COMMUNICATION){manager.setMode(AudioManager.MODE_IN_COMMUNICATION);changedMode=true;}
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            // ~300 ms of headroom rides out a GC pause or scheduling gap on a watch without polling.
            int out=Math.max(14400,AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
            track=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(out).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException();
            int in=Math.max(FRAME*4,AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            record=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(in).build();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();
            if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(record.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}
            track.play();
            if(!captureGate.muted()){
                record.startRecording();
                if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException();
            }
            capture=new Thread(this::capture,"grok-microphone");playback=new Thread(this::play,"grok-speaker");capture.start();playback.start();
        }catch(SecurityException revoked){close();listener.failure("Microphone access was revoked. Allow microphone access before starting voice again.");}
        catch(Exception ignored){close();listener.failure("Watch audio could not start. Check microphone access and the audio route.");}
    }
    private void capture(){byte[] buffer=new byte[FRAME];int failedReads=0;try{while(!closed.get()){
        long ticket=captureGate.begin();if(ticket<0){Thread.sleep(20);continue;}
        AudioRecord r=record;if(r==null)return;
        int n=r.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
        if(closed.get())return;if(!captureGate.accepts(ticket))continue;
        // Like the official Android client, a failed read is retried; only about 2 s of failures end the call.
        if(n<=0){if(++failedReads>=40){listener.failure("Microphone capture stopped. Start a new call.");return;}Thread.sleep(50);continue;}
        failedReads=0;
        byte[] pcm=Arrays.copyOf(buffer,n);try{listener.input(pcm,level(pcm));}finally{Arrays.fill(pcm,(byte)0);}
    }}catch(InterruptedException ignored){Thread.currentThread().interrupt();}catch(Exception ignored){if(!closed.get())listener.failure("Microphone capture failed.");}finally{Arrays.fill(buffer,(byte)0);}}
    // The microphone records only when the caller hasn't muted and no other app holds audio focus.
    private boolean userMuted=true,focusPaused;
    synchronized void mute(boolean value){userMuted=value;applyCapture();}
    private synchronized void focusPause(boolean paused){
        if(focusPaused==paused)return;focusPaused=paused;applyCapture();
        AudioTrack t=track;try{if(t!=null){if(paused)t.pause();else t.play();}}catch(Exception ignored){if(!closed.get())listener.failure("Watch audio could not resume.");}
    }
    private void applyCapture(){boolean off=userMuted||focusPaused;if(captureGate.muted()==off)return;captureGate.mute(off);try{AudioRecord r=record;if(r!=null){if(off)r.stop();else r.startRecording();}}catch(Exception ignored){if(!closed.get())listener.failure("Microphone could not change mute state.");}}
    void output(byte[] pcm){if(closed.get()||pcm==null||pcm.length==0)return;
        PcmPlaybackBuffer.Admission admission;int queued;
        synchronized(lock){if(closed.get())return;admission=queue.offer(pcm);queued=queue.size();
            if(admission==PcmPlaybackBuffer.Admission.ACCEPTED){framesQueued+=pcm.length/2;hadOutput=true;lock.notifyAll();return;}}
        if(admission==PcmPlaybackBuffer.Admission.CLOSED)return;
        // Counts only: never put audio, captions, account identifiers or credentials in logs.
        android.util.Log.w("GrokWearAudio","playback_reject cause="+admission+" incoming_bytes="+pcm.length+" queued_bytes="+queued+" capacity_bytes="+queue.capacity());
        listener.failure(admission==PcmPlaybackBuffer.Admission.INVALID_FORMAT
                ?"Grok returned an unsupported audio format. Call ended."
                :"Voice reply exceeded the watch's playback buffer. Call ended.");
    }
    private void play(){byte[] scratch=new byte[FRAME];float[] writtenLevel=new float[1];
        PcmPlaybackBuffer.Writer writer=(pcm,offset,length)->{
            int n=track.write(pcm,offset,length,AudioTrack.WRITE_NON_BLOCKING);
            if(n>0&&n<=length&&(n&1)==0)writtenLevel[0]=level(pcm,offset,n);
            return n;
        };
        try{while(!closed.get()){
            int n=0;boolean drained=false;
            synchronized(lock){
                if(closed.get()||track==null)return;
                // The same lock covers copying, nonblocking write and interrupt/flush. No old
                // speech remains outside the ring to be written after a caller interrupts it.
                if(queue.size()>0){n=queue.writeTo(writer,scratch);if(n>0)framesWritten+=n/2;}
                else if(hadOutput&&Integer.toUnsignedLong(track.getPlaybackHeadPosition())>=framesWritten){hadOutput=false;drained=true;}
                // Successful writes continue immediately. A full native buffer drains ~1 frame
                // per 42 us, so 20 ms waits keep it topped up; an idle player is woken by
                // output(), interrupt() or close().
                if(n==0&&!drained)lock.wait(hadOutput?20:100);
            }
            if(n<0){listener.failure("Watch speaker playback failed.");return;}
            if(n>0)listener.output(writtenLevel[0]);
            if(drained){listener.output(0);listener.drained();}
        }}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception ignored){if(!closed.get())listener.failure("Watch speaker playback stopped.");}
        finally{Arrays.fill(scratch,(byte)0);}
    }
    boolean isDrained(){synchronized(lock){return !hadOutput&&queue.size()==0;}}
    /** Frames accepted since the last interrupt. Lock-free for UI captions. */
    long queuedFrames(){return framesQueued;}
    /** Frames actually played since the last interrupt. Lock-free for UI captions. */
    long playedFrames(){AudioTrack t=track;if(t==null)return 0;try{return Math.min(framesWritten,Integer.toUnsignedLong(t.getPlaybackHeadPosition()));}catch(RuntimeException released){return 0;}}
    void interrupt(){synchronized(lock){queue.clear();framesWritten=0;framesQueued=0;hadOutput=false;
        if(track!=null)try{track.pause();track.flush();track.play();}catch(Exception ignored){}lock.notifyAll();}listener.output(0);}
    static float level(byte[] pcm){return level(pcm,0,pcm.length);}
    private static float level(byte[] pcm,int offset,int length){double sum=0;for(int i=offset;i+1<offset+length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));sum+=(double)sample*sample;}return length<2?0:(float)Math.min(1,Math.sqrt(sum/(length/2))/10000.0);}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;captureGate.mute(true);
        AudioRecord r=record;if(r!=null)try{r.stop();}catch(Exception ignored){}
        if(capture!=null)capture.interrupt();if(playback!=null)playback.interrupt();
        synchronized(lock){queue.close();framesWritten=0;framesQueued=0;hadOutput=false;lock.notifyAll();
            if(track!=null){try{track.pause();track.flush();track.release();}catch(Exception ignored){}track=null;}}
        // The capture read is unblocked by stop before release.
        if(capture!=null&&capture!=Thread.currentThread())try{capture.join(250);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        if(r!=null)try{r.release();}catch(Exception ignored){}record=null;
        if(echo!=null){try{echo.release();}catch(Exception ignored){}echo=null;}
        if(changedMode)try{if(manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)manager.setMode(oldMode);}catch(Exception ignored){}
        if(focusHeld)try{manager.abandonAudioFocusRequest(focus);}catch(Exception ignored){}
    }
}
