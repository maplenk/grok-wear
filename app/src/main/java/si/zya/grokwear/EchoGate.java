package si.zya.grokwear;

/**
 * Drops microphone frames that are no louder than the speaker's recent output.
 * Pure: no Android types, so the decision can be unit-tested.
 * Every method is synchronized. The speaker thread publishes the reference and the
 * microphone thread reads it; the fields must change together.
 *
 * AudioTrack holds about 300 ms, so the frame just written is not the frame being
 * heard. The reference is the loudest write still inside {@link #HOLD_MS}.
 */
final class EchoGate {
    static final float K=2.0f;
    static final float FLOOR=0.02f;
    static final long TAIL_MS=300;
    static final long HOLD_MS=500;
    /** No tail armed. Distinct from a deadline of 0. */
    private static final long NONE=Long.MIN_VALUE;
    /** Strictly decreasing by level, oldest first. A frame is ~43 ms, so 500 ms is about 12 entries. */
    private static final int HOLDS=64;

    private final long[] at=new long[HOLDS];
    private final float[] level=new float[HOLDS];
    private int count;
    private float volume=1f;
    /** Snapshot taken when a level-0 write starts a tail. Does not shorten the hold window. */
    private float zeroPeak;
    private long zeroEnds=NONE;
    /** Snapshot taken when playback drains. Measured from the playback head. */
    private float drainPeak;
    private long drainEnds=NONE;

    synchronized void volume(float fraction){volume=unit(fraction);}

    /**
     * One written frame. A level of 0 starts the tail once and does not extend it.
     * A positive level cancels an open tail and joins the hold window.
     */
    synchronized void output(float writtenLevel,float volumeFraction,long nowMs){
        volume=unit(volumeFraction);
        if(!(writtenLevel>0f)){startZeroTail(nowMs);return;}
        zeroEnds=NONE;zeroPeak=0f;drainEnds=NONE;drainPeak=0f;
        remember(writtenLevel,nowMs);
    }

    /** Playback has caught the write head. Starts the drain tail once; a second call does not move it. */
    synchronized void drained(float volumeFraction,long nowMs){
        volume=unit(volumeFraction);
        expireTails(nowMs);
        if(drainEnds!=NONE)return;
        float peak=windowPeak(nowMs);
        if(!(peak>0f))return;
        drainPeak=peak;drainEnds=nowMs+TAIL_MS;
    }

    synchronized void reset(){count=0;zeroPeak=0f;zeroEnds=NONE;drainPeak=0f;drainEnds=NONE;}

    /** True when the frame should be sent. Silence (no reference) always passes. */
    synchronized boolean pass(float micLevel,long nowMs){
        float reference=reference(nowMs);
        if(reference==0f)return true;
        return micLevel>Math.max(FLOOR,K*reference);
    }

    private void startZeroTail(long nowMs){
        expireTails(nowMs);
        if(zeroEnds!=NONE)return;
        float peak=windowPeak(nowMs);
        if(!(peak>0f))return;
        zeroPeak=peak;zeroEnds=nowMs+TAIL_MS;
    }

    private float reference(long nowMs){
        expireTails(nowMs);
        float peak=windowPeak(nowMs);
        if(zeroEnds!=NONE)peak=Math.max(peak,zeroPeak);
        if(drainEnds!=NONE)peak=Math.max(peak,drainPeak);
        return peak*volume;
    }

    /** A finished tail must not block the next one. A live tail is left where it is. */
    private void expireTails(long nowMs){
        if(zeroEnds!=NONE&&nowMs>=zeroEnds){zeroEnds=NONE;zeroPeak=0f;}
        if(drainEnds!=NONE&&nowMs>=drainEnds){drainEnds=NONE;drainPeak=0f;}
    }

    /** Loudest write with age under {@link #HOLD_MS}. The front of the ring is that peak. */
    private float windowPeak(long nowMs){expire(nowMs);return count==0?0f:level[0];}

    private void remember(float written,long nowMs){
        expire(nowMs);
        while(count>0&&!(level[count-1]>written))count--;
        if(count==HOLDS)return;
        at[count]=nowMs;level[count]=written;count++;
    }

    private void expire(long nowMs){
        int drop=0;
        while(drop<count&&nowMs>=at[drop]+HOLD_MS)drop++;
        if(drop==0)return;
        count-=drop;
        if(count>0){System.arraycopy(at,drop,at,0,count);System.arraycopy(level,drop,level,0,count);}
    }

    private static float unit(float fraction){if(!(fraction>0f))return 0f;return Math.min(fraction,1f);}
}
