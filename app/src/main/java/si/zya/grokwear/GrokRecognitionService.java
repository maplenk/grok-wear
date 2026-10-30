package si.zya.grokwear;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Android requires an assistant to name a speech recognizer. Grok Wear doesn't offer one: requests fail at once. */
public final class GrokRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent,Callback listener){try{listener.error(SpeechRecognizer.ERROR_CLIENT);}catch(Exception ignored){}}
    @Override protected void onCancel(Callback listener){}
    @Override protected void onStopListening(Callback listener){}
}
