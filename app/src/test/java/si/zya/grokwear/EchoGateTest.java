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
        // While frames are still being written, the reference does not age out.
        assertFalse(drained.pass(0.05f,10_000));
        drained.drained(1f,10_000);
        assertFalse(drained.pass(0.05f,10_000+EchoGate.TAIL_MS-1));
        assertTrue(drained.pass(0.05f,10_000+EchoGate.TAIL_MS));
        assertTrue(drained.pass(0f,10_000+EchoGate.TAIL_MS+1));
        // A second drain does not move the deadline.
        EchoGate once=new EchoGate();
        once.output(0.2f,1f,0);
        once.drained(1f,100);
        once.drained(1f,200);
        assertFalse(once.pass(0.05f,100+EchoGate.TAIL_MS-1));
        assertTrue(once.pass(0.05f,100+EchoGate.TAIL_MS));
        // An output level of 0 starts the same tail.
        EchoGate silent=new EchoGate();
        silent.output(0.2f,1f,0);
        silent.output(0f,1f,500);
        silent.output(0f,1f,700);
        assertFalse(silent.pass(0.05f,500+EchoGate.TAIL_MS-1));
        assertTrue(silent.pass(0.05f,500+EchoGate.TAIL_MS));
        // A new spoken frame cancels the tail and arms a fresh reference.
        silent.output(0.2f,1f,500+EchoGate.TAIL_MS+5);
        assertFalse(silent.pass(0.05f,500+EchoGate.TAIL_MS+6));
        // The held level tracks the current volume fraction, including during the tail.
        EchoGate scaled=new EchoGate();
        scaled.output(0.4f,1f,0);
        scaled.drained(1f,0);
        assertFalse(scaled.pass(0.5f,10));
        scaled.volume(0.25f);
        assertTrue(scaled.pass(0.5f,10));
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
