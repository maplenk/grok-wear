package si.zya.grokwear;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** The watch's assistant gesture (holding a button) opens a call with the main Bot, like the official app's assistant. */
public final class GrokAssistantSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args){
        return new VoiceInteractionSession(this){
            @Override public void onShow(Bundle shown,int flags){super.onShow(shown,flags);
                try{getContext().startActivity(new Intent(getContext(),MainActivity.class).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
                catch(RuntimeException refused){}
                hide(); // Grok Wear's own screen shows the call; this session has no window of its own.
            }
        };
    }
}
