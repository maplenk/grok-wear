package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Cross-component protocol contract. All clients and the gateway are synthetic. */
public final class SharedConversationTest {
    static final class Memory implements HistoryCoordinator.Store {
        private JSONObject value=new JSONObject();
        public JSONObject read()throws Exception{return new JSONObject(value.toString());}
        public void write(JSONObject next)throws Exception{value=new JSONObject(next.toString());}
    }
    @Test public void phoneContextWatchVoiceAndLaterMacMessageSurviveFreshClients()throws Exception {
        JSONObject binding=new JSONObject().put("accountKey","fixture-account").put("authGeneration","fixture-login")
            .put("agentId","bot-test").put("sessionId","").put("viewerIsOwner",true);
        JSONArray remoteTail=new JSONArray();
        Map<String,JSONObject> calls=new HashMap<>();
        long now=System.currentTimeMillis();
        remoteTail.put(new JSONObject().put("id","phone-user").put("seq",1).put("kind","message")
            .put("role","user").put("content","The phone context marker is a blue teacup.").put("timestampMs",now-4000));
        remoteTail.put(new JSONObject().put("id","phone-bot").put("seq",2).put("kind","send-message")
            .put("message",new JSONObject().put("type","text").put("content","I have the phone context.")).put("timestampMs",now-3000));
        HistoryCoordinator.Transport gateway=(method,body,identity)->{
            assertEquals("fixture-account",identity.getString("accountKey"));
            if(method.equals("getAgentTranscriptTail"))return new JSONObject().put("entries",new JSONArray(remoteTail.toString()));
            if(method.equals("getVoiceCall"))return calls.get(body.getString("callId"));
            if(method.equals("recordVoiceCall")){
                JSONObject record=new JSONObject(body.getJSONObject("record").toString());
                assertFalse(calls.containsKey(record.getString("callId")));
                calls.put(record.getString("callId"),record);
                remoteTail.put(new JSONObject().put("id","voice-card").put("seq",3).put("kind","voice-call")
                    .put("timestampMs",record.getLong("endedAtMs")).put("call",new JSONObject()
                    .put("callId",record.getString("callId")).put("durationMs",record.getLong("durationMs"))));
                return null;
            }
            throw new AssertionError("Unexpected gateway mutation: "+method);
        };
        HistoryCoordinator watch=new HistoryCoordinator(new Memory(),gateway,(b,s)->{},System::currentTimeMillis);
        HistoryCoordinator.Context context=watch.prepare(binding);
        assertEquals(2,context.turns().length());
        JSONObject recorded;
        try(NativeVoiceSessionTest.Fixture wire=new NativeVoiceSessionTest.Fixture(context.turns())){
            wire.receive("session.updated");
            for(int i=0;i<2;i++){
                JSONObject item=wire.frame("conversation.item.create").getJSONObject("item");
                wire.events.message(new JSONObject().put("type","conversation.item.created").put("item",item).toString());
            }
            wire.callback("ready");
            wire.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.completed")
                .put("item_id","spoken-watch-user").put("transcript","The watch context marker is a green notebook.").toString());
            wire.receive("response.created");
            wire.events.message(new JSONObject().put("type","response.output_audio_transcript.done")
                .put("item_id","spoken-watch-bot").put("transcript","I have both context markers.").toString());
            wire.barrier();
            recorded=wire.session.recordSnapshot("hung-up",System.currentTimeMillis(),0);
        }
        assertEquals("pending",watch.stage(context,recorded).getString("syncStatus"));
        assertEquals("synced",watch.record(context,recorded).getString("syncStatus"));
        assertEquals(1,calls.size());
        for(int i=0;i<recorded.getJSONArray("turns").length();i++)
            assertFalse(recorded.getJSONArray("turns").getJSONObject(i).getString("text").contains("blue teacup"));
        // No shared local cache: another device can only obtain what the gateway holds.
        HistoryCoordinator freshMac=new HistoryCoordinator(new Memory(),gateway,(b,s)->{},System::currentTimeMillis);
        JSONArray macContext=freshMac.prepare(binding).turns();
        assertEquals(1,count(macContext,"The watch context marker is a green notebook."));
        remoteTail.put(new JSONObject().put("id","later-mac-user").put("seq",4).put("kind","message")
            .put("role","user").put("content","The Mac context marker is an orange folder.").put("timestampMs",System.currentTimeMillis()+1));
        HistoryCoordinator reopenedWatch=new HistoryCoordinator(new Memory(),gateway,(b,s)->{},System::currentTimeMillis);
        JSONArray next=reopenedWatch.prepare(binding).turns();
        assertEquals(1,count(next,"The phone context marker is a blue teacup."));
        assertEquals(1,count(next,"The watch context marker is a green notebook."));
        assertEquals(1,count(next,"The Mac context marker is an orange folder."));
        assertEquals(1,calls.size());
    }
    private static int count(JSONArray turns,String expected)throws Exception{
        int found=0;for(int i=0;i<turns.length();i++)if(expected.equals(turns.getJSONObject(i).optString("text")))found++;return found;
    }
}
