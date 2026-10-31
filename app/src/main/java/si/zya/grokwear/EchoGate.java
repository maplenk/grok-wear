package si.zya.grokwear;

/**
 * Drops microphone frames that are no louder than the speaker's recent output.
 * Pure: no Android types, so the decision can be unit-tested.
 * Every method is synchronized. The speaker thread publishes the reference and the
 * microphone thread reads it; the fields must change together.
 */
final class EchoGate {
    static final float K=2.0f;
    static final float FLOOR=0.02f;
    static final long TAIL_MS=300;
    /** No tail in progress. Distinct from a deadline of 0. */
    private static final long LIVE=Long.MIN_VALUE;

    private float played;
    private float volume=1f;
    private boolean holding;
    private long tailEnds=LIVE;

    synchronized void volume(float fraction){volume=unit(fraction);}

    /** Last frame written to the speaker. A level of 0 starts the tail; it does not extend one. */
    synchronized void output(float writtenLevel,float volumeFraction,long nowMs){
        volume=unit(volumeFraction);
        if(!(writtenLevel>0f)){startTail(nowMs);return;}
        played=writtenLevel;holding=true;tailEnds=LIVE;
    }

    /** Playback has caught the write head. Starts the tail once; a second call does not move it. */
    synchronized void drained(float volumeFraction,long nowMs){volume=unit(volumeFraction);startTail(nowMs);}

    synchronized void reset(){played=0f;holding=false;tailEnds=LIVE;}

    /** True when the frame should be sent. Silence (no reference) always passes. */
    synchronized boolean pass(float micLevel,long nowMs){
        float reference=reference(nowMs);
        if(reference==0f)return true;
        return micLevel>Math.max(FLOOR,K*reference);
    }

    private void startTail(long nowMs){if(holding&&tailEnds==LIVE)tailEnds=nowMs+TAIL_MS;}

    private float reference(long nowMs){
        if(!holding)return 0f;
        if(tailEnds!=LIVE&&nowMs>=tailEnds){holding=false;played=0f;tailEnds=LIVE;return 0f;}
        return played*volume;
    }

    private static float unit(float fraction){if(!(fraction>0f))return 0f;return Math.min(fraction,1f);}
}
