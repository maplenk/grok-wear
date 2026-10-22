package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeAudioPolicyTest {
    @Test public void initiallyMutedNeverAdmitsCapture() {
        NativeAudio.CaptureGate gate=new NativeAudio.CaptureGate();
        assertTrue(gate.muted());assertEquals(-1,gate.begin());assertFalse(gate.accepts(-1));
        gate.mute(true);assertEquals(-1,gate.begin());
    }
    @Test public void readErrorFromPriorMuteIntervalIsDiscardedEvenAfterUnmute() {
        NativeAudio.CaptureGate gate=new NativeAudio.CaptureGate();gate.mute(false);
        long oldRead=gate.begin();assertTrue(gate.accepts(oldRead));
        gate.mute(true);assertFalse(gate.accepts(oldRead));
        gate.mute(false);assertFalse(gate.accepts(oldRead));assertTrue(gate.accepts(gate.begin()));
    }
    @Test public void repeatedUnmuteDoesNotInvalidateCurrentCapture() {
        NativeAudio.CaptureGate gate=new NativeAudio.CaptureGate();gate.mute(false);
        long read=gate.begin();gate.mute(false);assertTrue(gate.accepts(read));
    }
    @Test public void pcmMeterHandlesSignedLittleEndianSilenceAndClipping() {
        assertEquals(0,NativeAudio.level(new byte[20]),0);
        assertEquals(0,NativeAudio.level(new byte[0]),0);
        assertEquals(1,NativeAudio.level(new byte[]{0,(byte)128,(byte)255,127}),0);
        assertEquals(.1f,NativeAudio.level(new byte[]{(byte)232,3,24,(byte)252}),.0001f);
    }
}
