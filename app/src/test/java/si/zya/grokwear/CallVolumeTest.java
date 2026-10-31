package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

/** Stream level becomes a fill. An empty range stays empty, and the fill grows up from the bottom of the arc. */
public final class CallVolumeTest {
    @Test public void fractionCoversMinMaxAndAMiddleStep(){
        assertEquals(0f,CallVolume.fraction(1,1,15),0f);
        assertEquals(1f,CallVolume.fraction(15,1,15),0f);
        assertEquals(0f,CallVolume.fraction(4,4,4),0f);
        assertEquals(0f,CallVolume.fraction(0,0,0),0f);
        assertEquals(.5f,CallVolume.fraction(8,1,15),0f);
        assertEquals(.5f,CallVolume.fraction(5,0,10),0f);
    }
    @Test public void arcIsCentredOnThreeOClockAndFillsFromTheBottom(){
        assertEquals(60f,CallVolume.SWEEP,0f);
        assertEquals(-30f,CallVolume.trackStart(CallVolume.SWEEP),0f);
        assertEquals(30f,CallVolume.fillStart(CallVolume.SWEEP,0f),0f);
        assertEquals(0f,CallVolume.fillSweep(CallVolume.SWEEP,0f),0f);
        assertEquals(0f,CallVolume.fillStart(CallVolume.SWEEP,.5f),0f);
        assertEquals(30f,CallVolume.fillSweep(CallVolume.SWEEP,.5f),0f);
        assertEquals(-30f,CallVolume.fillStart(CallVolume.SWEEP,1f),0f);
        assertEquals(60f,CallVolume.fillSweep(CallVolume.SWEEP,1f),0f);
    }
}
