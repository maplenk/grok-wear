package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

public class EchoGateTest {
    private static float threshold(float played,float volume){return Math.max(EchoGate.FLOOR,EchoGate.K*played*volume);}

    @Test public void noReferencePassesEverything(){
        EchoGate gate=new EchoGate();
        assertTrue(gate.pass(0f,0));
        assertTrue(gate.pass(EchoGate.FLOOR,1));
        assertTrue(gate.pass(1f,2));
        gate.output(0f,1f,0);
        gate.drained(1f,10);
        assertTrue(gate.pass(0f,10));
        assertTrue(gate.pass(1f,10_000));
        // A silent speaker (volume fraction 0) is not an echo reference.
        gate.output(1f,0f,20);
        assertTrue(gate.pass(0f,21));
        assertTrue(gate.pass(0.01f,21));
    }

    @Test public void echoLevelInputIsGated(){
        EchoGate gate=new EchoGate();
        float played=0.1f;
        gate.output(played,1f,0);
        float threshold=threshold(played,1f);
        assertFalse(gate.pass(played,1));
        assertFalse(gate.pass(threshold,1));
        // The reference is the played level times the call-volume fraction.
        EchoGate quiet=new EchoGate();
        quiet.output(0.4f,0.5f,0);
        assertFalse(quiet.pass(0.4f*0.5f,1));
        assertFalse(quiet.pass(threshold(0.4f,0.5f),1));
        // Below the floor, K times a tiny reference still gates up to FLOOR.
        EchoGate whisper=new EchoGate();
        whisper.output(0.005f,1f,0);
        assertEquals(EchoGate.FLOOR,threshold(0.005f,1f),0f);
        assertFalse(whisper.pass(EchoGate.FLOOR,1));
    }

    @Test public void loudSpeechOverTheBotPasses(){
        EchoGate gate=new EchoGate();
        float played=0.1f;
        gate.output(played,1f,0);
        assertTrue(gate.pass(Math.nextUp(threshold(played,1f)),1));
        assertTrue(gate.pass(1f,1));
        EchoGate scaled=new EchoGate();
        scaled.output(0.4f,0.5f,0);
        assertTrue(scaled.pass(Math.nextUp(threshold(0.4f,0.5f)),1));
        EchoGate whisper=new EchoGate();
        whisper.output(0.005f,1f,0);
        assertTrue(whisper.pass(Math.nextUp(EchoGate.FLOOR),1));
    }

    @Test public void tailExpiresAfter300Ms(){
        EchoGate drained=new EchoGate();
        drained.output(0.2f,1f,0);
        // Still inside the hold window, before the playback head catches up.
        assertFalse(drained.pass(0.05f,400));
        // drained() is the playback head. Its 300 ms tail outlasts the hold on the write at t=0.
        drained.drained(1f,400);
        assertFalse(drained.pass(0.05f,EchoGate.HOLD_MS));
        assertFalse(drained.pass(0.05f,400+EchoGate.TAIL_MS-1));
        assertTrue(drained.pass(0.05f,400+EchoGate.TAIL_MS));
        assertTrue(drained.pass(0f,400+EchoGate.TAIL_MS+1));
        // A second drain does not move the deadline.
        EchoGate once=new EchoGate();
        once.output(0.2f,1f,0);
        once.drained(1f,400);
        once.drained(1f,600);
        assertFalse(once.pass(0.05f,400+EchoGate.TAIL_MS-1));
        assertTrue(once.pass(0.05f,400+EchoGate.TAIL_MS));
        // A level-0 write starts a tail, and a later zero does not move it.
        // The hold still covers the loud write after that tail has ended.
        EchoGate silent=new EchoGate();
        silent.output(0.2f,1f,0);
        silent.output(0f,1f,100);
        silent.output(0f,1f,200);
        assertFalse(silent.pass(0.05f,100+EchoGate.TAIL_MS));
        assertFalse(silent.pass(0.05f,EchoGate.HOLD_MS-1));
        assertTrue(silent.pass(0.05f,EchoGate.HOLD_MS));
        // A new spoken frame cancels the tail and arms a fresh reference.
        silent.output(0.2f,1f,EchoGate.HOLD_MS+5);
        assertFalse(silent.pass(0.05f,EchoGate.HOLD_MS+6));
        // The held level tracks the current volume fraction, including during the tail.
        EchoGate scaled=new EchoGate();
        scaled.output(0.4f,1f,0);
        scaled.drained(1f,0);
        assertFalse(scaled.pass(0.5f,10));
        scaled.volume(0.25f);
        assertTrue(scaled.pass(0.5f,10));
    }

    @Test public void quietFramesKeepTheLoudPeakUntilHoldAndTailExpire(){
        EchoGate gate=new EchoGate();
        float loud=0.4f;
        gate.output(loud,1f,0);
        gate.output(0f,1f,50);
        gate.output(0f,1f,80);
        // The zero at t=50 started a tail that ends at 350. The loud write is still inside the hold.
        assertFalse(gate.pass(loud,50+EchoGate.TAIL_MS));
        assertFalse(gate.pass(loud,EchoGate.HOLD_MS-1));
        assertFalse(gate.pass(0.05f,EchoGate.HOLD_MS-1));
        // Both the hold (500) and the tail (350) have expired.
        assertTrue(gate.pass(loud,EchoGate.HOLD_MS));
        assertTrue(gate.pass(0.05f,EchoGate.HOLD_MS));
        assertTrue(gate.pass(0f,EchoGate.HOLD_MS));
    }

    @Test public void holdWindowRollsForwardToTheNewerQuieterPeak(){
        EchoGate gate=new EchoGate();
        gate.output(0.8f,1f,0);
        gate.output(0.2f,1f,200);
        assertFalse(gate.pass(0.8f,EchoGate.HOLD_MS-1));
        // The loud frame is HOLD_MS old. The quieter write is the reference until t=700.
        assertTrue(gate.pass(0.8f,EchoGate.HOLD_MS));
        assertFalse(gate.pass(0.2f,EchoGate.HOLD_MS));
        assertFalse(gate.pass(threshold(0.2f,1f),200+EchoGate.HOLD_MS-1));
        assertTrue(gate.pass(0.2f,200+EchoGate.HOLD_MS));
        assertTrue(gate.pass(Math.nextUp(threshold(0.2f,1f)),EchoGate.HOLD_MS));
    }

    @Test public void resetClearsTheReference(){
        EchoGate gate=new EchoGate();
        gate.output(0.5f,1f,0);
        assertFalse(gate.pass(0.05f,10));
        gate.reset();
        assertTrue(gate.pass(0f,10));
        assertTrue(gate.pass(0.05f,10));
        assertTrue(gate.pass(1f,11));
        gate.output(0.5f,1f,0);
        gate.drained(1f,20);
        gate.reset();
        assertTrue(gate.pass(0.05f,20+EchoGate.TAIL_MS-1));
    }
}
