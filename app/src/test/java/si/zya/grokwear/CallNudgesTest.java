package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** voice-call-nudge frames and the realtime messages they become, as MORE-FEATURES-SPEC §2.3–2.5 shows them; values synthetic. */
public class CallNudgesTest {
    static final String CALL="call-6f1c2a9e-0000-4000-8000-000000000000";
    static JSONObject payload(String kind,String update)throws Exception {return new JSONObject().put("agentId","agt_A").put("callId",CALL).put("update",update).put("kind",kind);}

    @Test public void onlyAWellFormedNudgeCounts()throws Exception {
        JSONObject n=CallNudges.of(payload("outcome","Booked: table for 2 at 7:30pm at Luigi's, confirmation LT-4471."));
        assertEquals("agt_A",n.getString("agentId"));assertEquals(CALL,n.getString("callId"));assertEquals("outcome",n.getString("kind"));
        assertEquals("Booked: table for 2 at 7:30pm at Luigi's, confirmation LT-4471.",n.getString("update"));
        for(String kind:new String[]{"ack","progress","outcome","overheard"})assertNotNull(kind,CallNudges.of(payload(kind,"x")));
        assertNull("unknown kind",CallNudges.of(payload("landed","x")));assertNull("blank",CallNudges.of(payload("ack"," \n ")));
        assertNull("not a string",CallNudges.of(payload("ack","x").put("callId",7)));assertNull(CallNudges.of(payload("ack","x").put("update",new JSONObject())));
        JSONObject noAgent=payload("ack","x");noAgent.remove("agentId");assertNull(CallNudges.of(noAgent));assertNull(CallNudges.of(null));
        String longer=new String(new char[CallNudges.MAX_UPDATE+50]).replace('\0','w');
        assertEquals("kept bounded",CallNudges.MAX_UPDATE,CallNudges.of(payload("outcome",longer)).getString("update").length());
    }
    @Test public void theEventStreamHandsOnTheNudgeAlone()throws Exception {
        BotEvents.Event event=BotEvents.parse(new JSONObject().put("channel","voice-call-nudge").put("payload",payload("progress","Calling Luigi's now")).toString());
        assertEquals("nudge",event.kind);assertEquals("agt_A",event.botId);assertEquals("Calling Luigi's now",event.value.getString("update"));
        assertNull(BotEvents.parse(new JSONObject().put("channel","voice-call-nudge").put("payload",payload("other","x")).toString()));
    }
    @Test public void onlyTheCallOnTheLineTakesItOnce()throws Exception {
        Set<String> spoken=new HashSet<>();JSONObject n=CallNudges.of(payload("outcome","Done"));
        assertNull(CallNudges.refusal(n,"agt_A",CALL,true,false,spoken));
        assertEquals("off_line",CallNudges.refusal(n,"agt_A",CALL,false,true,spoken));
        assertEquals("other_call",CallNudges.refusal(n,"agt_B",CALL,true,true,spoken));
        assertEquals("other_call",CallNudges.refusal(n,"agt_A","call-other",true,true,spoken));
        spoken.add("Done");assertEquals("the same text again","duplicate",CallNudges.refusal(n,"agt_A",CALL,true,true,spoken));
        assertNull("other text",CallNudges.refusal(CallNudges.of(payload("ack","Done.")),"agt_A",CALL,true,false,spoken));
        JSONObject overheard=CallNudges.of(payload("overheard","Calling Luigi's now"));
        assertEquals("overheard_off",CallNudges.refusal(overheard,"agt_A",CALL,true,false,spoken));assertNull(CallNudges.refusal(overheard,"agt_A",CALL,true,true,spoken));
        for(int i=spoken.size();i<CallNudges.MAX_SPOKEN;i++)spoken.add("update "+i);
        assertEquals("full",CallNudges.refusal(CallNudges.of(payload("outcome","new")),"agt_A",CALL,true,true,spoken));
    }
    @Test public void spokenWorkIsOneAsyncUpdate()throws Exception {
        for(String kind:new String[]{"ack","progress","outcome"}){
            JSONArray frames=CallNudges.frames(CallNudges.of(payload(kind,"Booked.")),"seeded-nudge-nudge-1",1762958500123L);
            assertEquals(1,frames.length());assertTrue(CallNudges.spoken(CallNudges.of(payload(kind,"x"))));
            CallQuestionsTest.same("{\"type\":\"async_update.add\",\"event_id\":\"seeded-nudge-nudge-1\",\"async_update\":{\"topic\":\"work_landed\","
                +"\"payload\":[{\"message\":{\"type\":\"text\",\"content\":\"Booked.\"},\"timestampMs\":1762958500123}]}}",frames.getJSONObject(0));
            assertFalse("nothing holds the reply back",frames.toString().contains("trigger_response"));
        }
    }
    @Test public void anOverheardNoteIsASilentFunctionCallPair()throws Exception {
        JSONArray frames=CallNudges.frames(CallNudges.of(payload("overheard","Calling Luigi's now")),"seeded-nudge-nudge-3b0e",1794493300987L);
        assertEquals(2,frames.length());assertFalse(CallNudges.spoken(CallNudges.of(payload("overheard","x"))));
        CallQuestionsTest.same("{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"function_call\",\"name\":\"work_overheard\",\"call_id\":\"seeded-nudge-nudge-3b0e\",\"arguments\":\"{}\"}}",frames.getJSONObject(0));
        JSONObject output=frames.getJSONObject(1).getJSONObject("item");
        assertEquals("conversation.item.create",frames.getJSONObject(1).getString("type"));assertEquals("function_call_output",output.getString("type"));assertEquals("seeded-nudge-nudge-3b0e",output.getString("call_id"));
        assertEquals("milliseconds stripped, official key order","{\"status\":\"in progress\",\"steps\":[\"Calling Luigi's now\"],\"at\":\"2026-11-12T14:21:40Z\"}",output.getString("output"));
        for(int i=0;i<frames.length();i++)assertNotEquals("no reply asked for","response.create",frames.getJSONObject(i).getString("type"));
    }
    @Test public void idsAreMintedTheOfficialWay(){
        String call=CallNudges.newCallId();assertTrue(call.startsWith("call-"));assertEquals(call.substring(5),UUID.fromString(call.substring(5)).toString());
        assertNotEquals(call,CallNudges.newCallId());
        String seed=CallNudges.seedId();assertTrue(seed.startsWith("seeded-nudge-nudge-"));UUID.fromString(seed.substring("seeded-nudge-nudge-".length()));
    }
}
