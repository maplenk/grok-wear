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
    @Test public void voiceCallFractionIsZeroWithoutARangeAndClamped(){
        assertEquals(0f,NativeAudio.voiceCallFraction(3,5,5),0f);
        assertEquals(0f,NativeAudio.voiceCallFraction(1,2,1),0f);
        assertEquals(0f,NativeAudio.voiceCallFraction(0,0,10),0f);
        assertEquals(.5f,NativeAudio.voiceCallFraction(5,0,10),0f);
        assertEquals(1f,NativeAudio.voiceCallFraction(10,0,10),0f);
        assertEquals(0f,NativeAudio.voiceCallFraction(-1,0,10),0f);
        assertEquals(1f,NativeAudio.voiceCallFraction(12,0,10),0f);
    }
    @Test public void echoDiagnosticsAreCountsAndBooleansOnly(){
        String line=NativeAudio.echoDiagnostics(true,false,true,3,4);
        assertEquals("aec_available=true,aec_created=false,aec_enabled=true,gated=3,passed=4",line);
    }
    @Test public void pcmMeterHandlesSignedLittleEndianSilenceAndClipping() {
        assertEquals(0,NativeAudio.level(new byte[20]),0);
        assertEquals(0,NativeAudio.level(new byte[0]),0);
        assertEquals(1,NativeAudio.level(new byte[]{0,(byte)128,(byte)255,127}),0);
        assertEquals(.1f,NativeAudio.level(new byte[]{(byte)232,3,24,(byte)252}),.0001f);
    }
}
