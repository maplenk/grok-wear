package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline protocol fixtures for the Bot/voice boundaries that previously differed from native. */
public final class NativeVoiceProtocolTest {
    @Test public void toolsShareVoiceSessionIdentity()throws Exception {
        String voiceCall="voice-session-123";
        JSONObject first=NativeVoiceSession.toolRequest("bot-a",voiceCall,"recall_text_messages",new JSONObject(),new JSONObject());
        JSONObject second=NativeVoiceSession.toolRequest("bot-a",voiceCall,"search_conversations",new JSONObject().put("query","last call"),new JSONObject());
        assertEquals(voiceCall,first.getString("callId"));assertEquals(first.getString("callId"),second.getString("callId"));
        assertEquals("bot-a",second.getString("agentId"));assertFalse(first.has("call_id"));
        assertEquals("last call",second.getJSONObject("input").getString("query"));
    }
    @Test public void androidSafeToolOutputPreservesTypesAndEscaping()throws Exception {
        Object[] values={new JSONObject().put("text","quote \" and newline\n"),new JSONArray().put("one").put(2),"[not an array]",true,42,JSONObject.NULL,null};
        for(Object value:values){String encoded=NativeVoiceSession.encodeOutput(value);JSONArray decoded=new JSONArray("["+encoded+"]");
            if(value==null||value==JSONObject.NULL)assertTrue(decoded.isNull(0));
            else assertEquals(value.toString(),decoded.get(0).toString());}
    }
}
