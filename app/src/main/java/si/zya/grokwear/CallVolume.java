package si.zya.grokwear;

/** Call-volume level for the edge arc. Canvas 0° is 3 o'clock and positive sweep is clockwise. */
final class CallVolume {
    /** Arc centred on 3 o'clock, inside the 50–60° range of the Wear volume arc. */
    static final float SWEEP=60f;
    private CallVolume(){}
    /** Zero when the stream has no range, so the fill never divides by zero. */
    static float fraction(int volume,int min,int max){
        return max<=min?0f:(volume-min)/(float)(max-min);
    }
    /** Top of the track. The arc then sweeps down through 3 o'clock. */
    static float trackStart(float sweep){return -sweep/2f;}
    /** Fill grows from the bottom of the track toward the top. */
    static float fillStart(float sweep,float fraction){return sweep/2f-sweep*fraction;}
    static float fillSweep(float sweep,float fraction){return sweep*fraction;}
}
