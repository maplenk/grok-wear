package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Phone-call entries shaped like FEATURES-SPEC §3.2 (Grok Bot Android 1.25.0); all values synthetic. */
public class PhoneCallsTest {
    static final long CONNECTED=1762958461000L;
    static JSONObject call()throws Exception {
        return new JSONObject().put("callId","oc_77").put("name","Luigi's Trattoria").put("toDisplay","+1 415-555-0100").put("status","in_progress")
            .put("connectedAtMs",CONNECTED).put("transferAvailable",true)
            .put("transcript",new JSONArray().put(new JSONObject().put("atMs",CONNECTED+2000).put("speaker","agent").put("text","Hi, I'd like to book a table")));
    }
    static JSONObject entry(JSONObject call)throws Exception {
        return new JSONObject().put("id","ent_456").put("kind","send-message").put("message",new JSONObject().put("type","phone-call").put("call",call));
    }
    private static JSONObject of(JSONObject call)throws Exception {return PhoneCalls.of(entry(call));}

    @Test public void aTalkingCallShowsWhoItCallsATimerAndBothControls()throws Exception {
        JSONObject c=of(call());
        assertEquals("ent_456",c.getString("entryId"));assertEquals("oc_77",c.getString("callId"));assertEquals("Luigi's Trattoria",c.getString("label"));
        assertEquals("Concierge is calling Luigi's Trattoria",PhoneCalls.headline(c,"Concierge"));
        assertNull("talking: the line is the timer",PhoneCalls.status(c,""));
        assertEquals("1:05",PhoneCalls.timer(c,CONNECTED+65_000));assertEquals("1:00:01",PhoneCalls.clock(3_601_000));assertEquals("0:00",PhoneCalls.timer(c,CONNECTED-5000));
        assertTrue(PhoneCalls.canEnd(c,true));assertTrue(PhoneCalls.canTakeOver(c,true));
        assertFalse("a teammate's Bot",PhoneCalls.canEnd(c,false));assertFalse(PhoneCalls.canTakeOver(c,false));
        assertFalse("the transcript isn't kept",c.toString().contains("book a table"));
    }
    @Test public void withoutANameTheCardShowsTheNumberAsGrokFormatsIt()throws Exception {
        JSONObject c=of(call().put("name",JSONObject.NULL));
        assertEquals("+1 415-555-0100",c.getString("label"));assertEquals("Concierge is calling +1 415-555-0100",PhoneCalls.headline(c,"Concierge"));
        JSONObject nobody=of(call().put("name","").put("toDisplay",""));assertEquals("Your Bot is calling someone",PhoneCalls.headline(nobody,""));
    }
    @Test public void theStatusLineFollowsTheOfficialCard()throws Exception {
        assertEquals("Calling…",PhoneCalls.status(of(call().put("status","dialing")),""));
        JSONObject noTime=call();noTime.remove("connectedAtMs");
        assertEquals("no connect time yet","Calling…",PhoneCalls.status(of(noTime),""));
        assertEquals("Ending call…",PhoneCalls.status(of(call()),"hangup"));
        assertEquals("Connecting you…",PhoneCalls.status(of(call()),"transfer"));
        JSONObject ringing=of(call().put("transfer",new JSONObject().put("state","ringing").put("initiator","user")));
        assertEquals("Connecting you…",PhoneCalls.status(ringing,""));assertFalse("a transfer under way",PhoneCalls.canTakeOver(ringing,true));assertTrue(PhoneCalls.canEnd(ringing,true));
        assertNull("someone else's transfer ringing is still talking",PhoneCalls.status(of(call().put("transfer",new JSONObject().put("state","ringing").put("initiator","agent"))),""));
        assertEquals("Connecting you…",PhoneCalls.status(of(call().put("transfer",new JSONObject().put("state","answered"))),""));
        JSONObject handed=of(call().put("transferTargetName","Ana").put("transcript",new JSONArray().put(new JSONObject().put("action","transfer").put("result","handed_off"))));
        assertEquals("Transferred to Ana",PhoneCalls.status(handed,""));assertFalse(PhoneCalls.canTakeOver(handed,true));
        assertFalse("Take over only when Grok offers it",PhoneCalls.canTakeOver(of(call().put("transferAvailable",false)),true));
        assertFalse("true means true",PhoneCalls.canTakeOver(of(call().put("transferAvailable","true")),true));
        assertFalse("not while calling",PhoneCalls.canTakeOver(of(call().put("status","dialing")),true));
    }
    @Test public void anEndedCallIsOneLineWithNoControls()throws Exception {
        JSONObject ended=of(call().put("status","ended").put("durationMs",134_000));
        assertEquals("2:14 · Ended",PhoneCalls.status(ended,""));assertEquals("Phone call to Luigi's Trattoria",PhoneCalls.headline(ended,"Concierge"));
        assertFalse(PhoneCalls.canEnd(ended,true));assertFalse(PhoneCalls.canTakeOver(ended,true));
        assertEquals("a request still waiting doesn't matter once ended","2:14 · Ended",PhoneCalls.status(ended,"hangup"));
        assertEquals("Ended",PhoneCalls.status(of(call().put("status","ended")),""));
        assertEquals("No answer",PhoneCalls.status(of(call().put("status","ended").put("outcome","no_answer")),""));
        assertEquals("Didn't go through",PhoneCalls.status(of(call().put("status","ended").put("outcome","failed")),""));
        assertEquals("Transferred to you",PhoneCalls.status(of(call().put("status","ended").put("endReason","handed_off")),""));
    }
    @Test public void onlyPhoneCallEntriesWithACallIdCountAndTextIsBounded()throws Exception {
        assertNull(PhoneCalls.of(entry(call()).put("kind","message")));
        assertNull(PhoneCalls.of(entry(call()).put("message",new JSONObject().put("type","text").put("content","hi"))));
        assertNull(PhoneCalls.of(entry(call()).put("message",new JSONObject().put("type","phone-call"))));
        assertNull("no call id",of(call().put("callId","")));assertNull(of(call().put("callId","oc\n1")));
        char[] many=new char[513];java.util.Arrays.fill(many,'c');String long513=new String(many);
        assertNull("a call id over 512",of(call().put("callId",long513)));assertNotNull(of(call().put("callId",long513.substring(1))));
        JSONObject wordy=of(call().put("name","Luigi's\n\tTrattoria "+long513).put("transferTargetName",long513));
        assertTrue(wordy.getString("label").startsWith("Luigi's Trattoria c"));assertEquals(300,wordy.getString("label").length());assertEquals(300,wordy.getString("transferTarget").length());
        assertEquals("an odd status is none","",of(call().put("status","In Progress!")).getString("status"));
        assertEquals("a time sent as text",CONNECTED,of(call().put("connectedAtMs",Long.toString(CONNECTED))).getLong("connectedAtMs"));
        assertNull(PhoneCalls.of(null));
    }
    @Test public void endCallAndTakeOverSendOnlyTheCallIdAndReadTheReply()throws Exception {
        JSONObject body=PhoneCalls.body(of(call()));
        assertEquals(1,body.length());assertEquals("oc_77",body.getString("outboundCallId"));
        try{PhoneCalls.body(new JSONObject());fail("no call id");}catch(GrokCore.Failure expected){}
        assertNull(PhoneCalls.refused(new JSONObject().put("accepted",true),false));
        assertEquals("a refusal in its own words","The call already ended.",PhoneCalls.refused(new JSONObject().put("accepted",false).put("refusal",new JSONObject().put("message","The call\nalready ended.")),false));
        assertEquals("accepted but refused is refused","Couldn't end the call",PhoneCalls.refused(new JSONObject().put("accepted",true).put("refusal",new JSONObject()),false));
        assertEquals("Couldn't take over the call",PhoneCalls.refused(new JSONObject(),true));
        assertEquals("Couldn't end the call",PhoneCalls.refused(new JSONObject().put("accepted","true"),false));
        assertEquals("Couldn't end the call",PhoneCalls.refused(null,false));
    }
}
