package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

/** A live call counts from its start. Mute sends no new snapshot, and a finished call stays stamped. */
public final class CallElapsedTest {
    @Test public void liveCallCountsFromTheStart(){
        assertEquals(7000,CallElapsed.millis(1000,5000,8000));
    }
    @Test public void mutedCallKeepsCountingWithoutANewSnapshot(){
        assertEquals(11000,CallElapsed.millis(1000,5000,12000));
    }
    @Test public void finishedCallKeepsTheStampedDuration(){
        assertEquals(42000,CallElapsed.millis(0,42000,90000));
    }
    @Test public void callThatHasNotStartedStaysAtZero(){
        assertEquals(0,CallElapsed.millis(0,0,5000));
    }
}
