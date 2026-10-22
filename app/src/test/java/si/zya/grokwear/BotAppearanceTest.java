package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BotAppearanceTest {
    @Test public void approvedTimingsHaveStableEndpoints(){
        assertEquals(0,BotAppearance.morph(0),0);assertEquals(1,BotAppearance.morph(.28),0);
        assertEquals(0,BotAppearance.blink("idle",1),0);assertEquals(.92,BotAppearance.blink("idle",1.61),.00001);
        assertEquals(.92,BotAppearance.blink("idle",3.59),.00001);assertEquals(0,BotAppearance.blink("listening",1.61),0);
        assertEquals(0,BotAppearance.bob("speaking",.62),.00001);assertEquals(-6,BotAppearance.bob("speaking",.31),.00001);
        assertEquals(1,BotAppearance.breathe("idle",0),.00001);assertEquals(1.04,BotAppearance.breathe("idle",2),.00001);
    }
    @Test public void dotsArePhaseShiftedAndStationaryStatesNeverLoop(){
        assertEquals(-12,BotAppearance.dotY(.42,0),.00001);assertNotEquals(BotAppearance.dotY(.42,0),BotAppearance.dotY(.42,1),.001);
        assertTrue(BotAppearance.loops("idle"));assertTrue(BotAppearance.loops("thinking"));
        for(String state:new String[]{"happy","sad","sleepy","muted","confused"})assertFalse(BotAppearance.loops(state));
    }
}
