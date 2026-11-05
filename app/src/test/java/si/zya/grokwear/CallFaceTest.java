package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

/** Mute hides the microphone. The orb keeps the call's own loop, and preview stays as it was. */
public final class CallFaceTest {
    @Test public void mutedCallKeepsTheUnderlyingMotion(){
        assertEquals("listening",CallFace.orb("muted","Listening",false));
        assertEquals("thinking",CallFace.orb("muted","Thinking",false));
        assertEquals("speaking",CallFace.orb("muted","Speaking",false));
        assertTrue(BotAppearance.loops(CallFace.orb("muted","Speaking",false)));
        assertFalse(BotAppearance.loops("muted"));
    }
    @Test public void otherScreensStayAsTheyAre(){
        assertEquals("listening",CallFace.orb("listening","Listening",false));
        assertEquals("connecting",CallFace.orb("connecting","Connecting",false));
        assertEquals("reconnecting",CallFace.orb("reconnecting","Reconnecting",false));
    }
    @Test public void previewMutedStaysMuted(){
        assertEquals("muted",CallFace.orb("muted","Speaking",true));
        assertEquals("listening",CallFace.orb("listening","Speaking",true));
    }
}
