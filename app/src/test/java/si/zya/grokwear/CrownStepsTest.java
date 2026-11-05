package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

/** One crown detent is one step; a shortfall waits, and a finished call forgets it. */
public final class CrownStepsTest {
    @Test public void bothDirectionsStepOncePerDetent(){
        CrownSteps crown=new CrownSteps();
        assertEquals(1,crown.consume(CrownSteps.DETENT));
        assertEquals(2,crown.consume(CrownSteps.DETENT*2));
        assertEquals(-1,crown.consume(-CrownSteps.DETENT));
        assertEquals(-3,crown.consume(-CrownSteps.DETENT*3));
        assertEquals(0,crown.consume(0));
    }
    @Test public void shortfallCarriesUntilItMakesADetent(){
        CrownSteps crown=new CrownSteps();
        assertEquals(0,crown.consume(.25f));
        assertEquals(0,crown.consume(.25f));
        assertEquals(0,crown.consume(.25f));
        assertEquals(1,crown.consume(.25f));
        assertEquals(0,crown.consume(.25f));
        assertEquals(2,crown.consume(1.75f));
        assertEquals(0,crown.consume(-.5f));
        assertEquals(-1,crown.consume(-.5f));
        assertEquals(-1,crown.consume(-1.25f));
        assertEquals(0,crown.consume(.25f));
        assertEquals(-1,crown.consume(-1.5f));
    }
    @Test public void resetDropsTheLeftoverWhenTheCallEnds(){
        CrownSteps crown=new CrownSteps();
        assertEquals(0,crown.consume(.75f));
        crown.reset();
        assertEquals(0,crown.consume(.5f));
        assertEquals(1,crown.consume(.5f));
        assertEquals(0,crown.consume(-.75f));
        crown.reset();
        assertEquals(0,crown.consume(-.5f));
        assertEquals(-1,crown.consume(-.5f));
    }
}
