package si.zya.grokwear;

import java.util.Locale;

/** Which face the call orb plays. Mute never replaces a real call's own motion. */
final class CallFace {
    private CallFace(){}
    /** Preview keeps its screen, including muted. A real muted call keeps listening, thinking or speaking. */
    static String orb(String screen,String voiceState,boolean preview){
        return !preview&&"muted".equals(screen)?voiceState.toLowerCase(Locale.ROOT):screen;
    }
}
