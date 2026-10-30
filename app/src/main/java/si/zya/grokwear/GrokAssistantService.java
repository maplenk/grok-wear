package si.zya.grokwear;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/**
 * Grok Wear as the watch's digital assistant. Android binds this while Grok Wear holds that role; holding
 * the assistant button opens GrokAssistantSessionService, which starts a call with the main Bot. It never
 * opens the microphone itself: the call does, once it starts.
 */
public final class GrokAssistantService extends VoiceInteractionService {
    /** The assistant gesture from the lock screen: the same call as the button, once the watch is unlocked. */
    @Override public void onLaunchVoiceAssistFromKeyguard(){
        try{startActivity(new Intent(this,MainActivity.class).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException refused){}
    }
}
