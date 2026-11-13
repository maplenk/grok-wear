package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** A question during a Bot's phone call, shaped as MORE-FEATURES-SPEC §1.2 shows it; all values synthetic. */
public class CallQuestionsTest {
    static final long DEADLINE=1_762_958_530_000L,REVEAL=1_762_958_472_000L;
    static JSONObject phoneCallQuestion(){
        try{return new JSONObject().put("askedBy","Luigi's Trattoria").put("prompt","Is 7:30 OK instead of 7?").put("placeholder","Type what Ava should say")
            .put("prefill","").put("deadlineAtMs",DEADLINE).put("revealAtMs",REVEAL);}catch(Exception e){throw new AssertionError(e);}
    }
    static JSONObject entry(JSONObject question)throws Exception {
        JSONObject message=new JSONObject().put("type","user-form")
            .put("formRequest",new JSONObject().put("title","Question from Luigi's Trattoria").put("instruction","They ask: is 7:30 OK instead of 7?"));
        if(question!=null)message.put("phoneCallQuestion",question);
        return new JSONObject().put("id","phone-call-question:oc_77:1").put("kind","send-message").put("author",new JSONObject().put("id","agt_A"))
            .put("timestampMs",1762958470000L).put("message",message);
    }

    /** JSON with its keys sorted, to compare by content; Android's org.json has no similar(). */
    static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();o.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String k:keys)out.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k))).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++)out.append(canonical(a.get(i))).append(',');return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    static void same(String expected,JSONObject actual)throws Exception {assertEquals(canonical(new JSONObject(expected)),canonical(actual));}

    @Test public void aUserFormWithAPhoneCallQuestionIsTheCard()throws Exception {
        JSONObject q=CallQuestions.of(entry(phoneCallQuestion()),"agt_chat");
        assertEquals("phone-call-question:oc_77:1",q.getString("entryId"));assertEquals("the chat's Bot answers","agt_chat",q.getString("agentId"));
        assertEquals("Luigi's Trattoria",q.getString("askedBy"));assertEquals("Is 7:30 OK instead of 7?",q.getString("prompt"));
        assertEquals("Type what Ava should say",q.getString("placeholder"));assertEquals("",q.getString("prefill"));
        assertEquals(DEADLINE,q.getLong("deadlineAtMs"));assertEquals(REVEAL,q.getLong("revealAtMs"));
        assertFalse(q.has("presentedAtMs"));assertFalse(q.has("closed"));assertFalse(q.has("resolution"));assertFalse(q.has("answer"));
        assertEquals("no chat Bot: the author","agt_A",CallQuestions.of(entry(phoneCallQuestion()),"").getString("agentId"));
        JSONObject noAuthor=entry(phoneCallQuestion());noAuthor.remove("author");assertEquals("",CallQuestions.of(noAuthor,"").getString("agentId"));
        // The prompt falls back to the form's instruction; times may come as strings.
        JSONObject bare=phoneCallQuestion();bare.remove("prompt");bare.put("deadlineAtMs",String.valueOf(DEADLINE));
        JSONObject fallback=CallQuestions.of(entry(bare),"agt_A");assertEquals("They ask: is 7:30 OK instead of 7?",fallback.getString("prompt"));assertEquals(DEADLINE,fallback.getLong("deadlineAtMs"));
    }
    @Test public void staleRowsAndOtherEntriesAreNotQuestions()throws Exception {
        assertNull("the prefix without a question: hidden, as in the official chat",CallQuestions.of(entry(null),"agt_A"));
        JSONObject plain=entry(phoneCallQuestion());plain.getJSONObject("message").put("type","text");assertNull(CallQuestions.of(plain,"agt_A"));
        JSONObject message=entry(phoneCallQuestion()).put("kind","message");assertNull(CallQuestions.of(message,"agt_A"));
        assertNull("no id",CallQuestions.of(entry(phoneCallQuestion()).put("id",""),"agt_A"));
        assertNull(CallQuestions.of(null,"agt_A"));
    }
    @Test public void itsStateFollowsTheOfficialOrder()throws Exception {
        JSONObject open=CallQuestions.of(entry(phoneCallQuestion()),"agt_A");
        assertEquals("open",CallQuestions.state(open,DEADLINE-1));assertEquals("the deadline passing here","timed_out",CallQuestions.state(open,DEADLINE));
        JSONObject answered=entry(phoneCallQuestion()).put("formResolution","submitted").put("formFieldOutcomes",new JSONArray()
            .put(new JSONObject().put("id","note").put("submittedValue","other")).put(new JSONObject().put("id","answer").put("submittedValue"," Yes, 7:30 works ")));
        JSONObject sent=CallQuestions.of(answered,"agt_A");assertEquals("sent",CallQuestions.state(sent,DEADLINE+1));assertEquals("Yes, 7:30 works",sent.getString("answer"));
        assertEquals("skipped",CallQuestions.state(CallQuestions.of(entry(phoneCallQuestion()).put("formResolution","dismissed"),"agt_A"),0));
        JSONObject ended=CallQuestions.of(entry(phoneCallQuestion().put("closed","ended")).put("formResolution","submitted"),"agt_A");
        assertEquals("Grok's closed wins","ended",CallQuestions.state(ended,0));
        assertEquals("closed",CallQuestions.state(CallQuestions.of(entry(phoneCallQuestion().put("closed",3)),"agt_A"),0));
        JSONObject noDeadline=phoneCallQuestion();noDeadline.remove("deadlineAtMs");
        assertEquals("no deadline, no local expiry","open",CallQuestions.state(CallQuestions.of(entry(noDeadline),"agt_A"),Long.MAX_VALUE));
    }
    @Test public void itIsHeldBackUntilRevealedPresentedOrClosed()throws Exception {
        JSONObject q=CallQuestions.of(entry(phoneCallQuestion()),"agt_A");
        assertTrue(CallQuestions.heldBack(q,REVEAL-1));assertFalse(CallQuestions.heldBack(q,REVEAL));
        assertFalse(CallQuestions.heldBack(CallQuestions.of(entry(phoneCallQuestion().put("presentedAtMs",REVEAL-5000)),"agt_A"),REVEAL-1));
        assertFalse(CallQuestions.heldBack(CallQuestions.of(entry(phoneCallQuestion().put("closed","ended")),"agt_A"),REVEAL-1));
        JSONObject now=phoneCallQuestion();now.remove("revealAtMs");assertFalse(CallQuestions.heldBack(CallQuestions.of(entry(now),"agt_A"),0));
    }
    @Test public void theCardSpeaksInTheOfficialWords()throws Exception {
        JSONObject q=CallQuestions.of(entry(phoneCallQuestion()),"agt_A");
        assertEquals("Luigi's Trattoria is asking",CallQuestions.asking(q));
        assertEquals("Respond in 1:05",CallQuestions.remaining(DEADLINE,DEADLINE-65_000));assertEquals("Respond in 0:00",CallQuestions.remaining(DEADLINE,DEADLINE+5));
        assertEquals("Type what Ava should say",CallQuestions.placeholder(q,"Bot A"));
        JSONObject plain=phoneCallQuestion();plain.remove("placeholder");plain.remove("askedBy");JSONObject p=CallQuestions.of(entry(plain),"agt_A");
        assertEquals("Type what Bot A should say",CallQuestions.placeholder(p,"Bot A"));assertEquals("Type what Bot should say",CallQuestions.placeholder(p,""));
        assertEquals("The person on the call is asking",CallQuestions.asking(p));
        assertEquals("Submitted",CallQuestions.settled("sent"));assertEquals("Skipped",CallQuestions.settled("skipped"));
        assertEquals("No reply in time",CallQuestions.settled("timed_out"));assertEquals("Call ended",CallQuestions.settled("ended"));assertEquals("Closed",CallQuestions.settled("other"));
    }
    @Test public void answersGoToTheGatewayOrGroksServerAsTheOfficialAppSendsThem()throws Exception {
        JSONObject q=CallQuestions.of(entry(phoneCallQuestion()),"agt_A");
        same("{\"agentId\":\"agt_A\",\"entryId\":\"phone-call-question:oc_77:1\",\"values\":{\"answer\":\"Yes, 7:30 works\"},\"platform\":\"android\"}",CallQuestions.gatewayBody(q,"  Yes, 7:30 works "));
        same("{\"agentId\":\"agt_A\",\"entryId\":\"phone-call-question:oc_77:1\",\"mode\":\"dismissed\",\"platform\":\"android\"}",CallQuestions.gatewayBody(q,null));
        same("{\"agentId\":\"agt_A\",\"entryId\":\"phone-call-question:oc_77:1\",\"values\":{\"answer\":\"Yes\"},\"platform\":\"GROK_BOT_USER_FORM_CLIENT_PLATFORM_ANDROID\"}",CallQuestions.serverBody(q,"Yes"));
        same("{\"agentId\":\"agt_A\",\"entryId\":\"phone-call-question:oc_77:1\",\"mode\":\"GROK_BOT_USER_FORM_DISMISS_MODE_DISMISSED\",\"platform\":\"GROK_BOT_USER_FORM_CLIENT_PLATFORM_ANDROID\"}",CallQuestions.serverBody(q,null));
        for(String empty:new String[]{"","   ",new String(new char[CallQuestions.MAX_ANSWER+1]).replace('\0','x')})
            try{CallQuestions.gatewayBody(q,empty);fail("Nothing is sent for an empty or overlong answer");}catch(GrokCore.Failure expected){}
        JSONObject noAuthor=entry(phoneCallQuestion());noAuthor.remove("author");
        try{CallQuestions.serverBody(CallQuestions.of(noAuthor,""),"Yes");fail("No Bot, no answer");}catch(GrokCore.Failure expected){assertEquals(CallQuestions.FAILED,expected.getMessage());}
    }
    @Test public void onlyAcceptedWithoutARefusalIsDone()throws Exception {
        assertNull(CallQuestions.refused(new JSONObject().put("accepted",true)));
        assertEquals("The call already ended.",CallQuestions.refused(new JSONObject().put("accepted",false).put("refusal",new JSONObject().put("message","The call\n already ended."))));
        assertEquals("a refusal even when accepted",CallQuestions.FAILED,CallQuestions.refused(new JSONObject().put("accepted",true).put("refusal",new JSONObject())));
        assertEquals(CallQuestions.FAILED,CallQuestions.refused(new JSONObject()));assertEquals(CallQuestions.FAILED,CallQuestions.refused(null));
        assertEquals(CallQuestions.FAILED,CallQuestions.problem(new GrokCore.Failure("Grok request failed (HTTP 404).",true,"",404)).getMessage());
        assertEquals("a lost reply too",CallQuestions.FAILED,CallQuestions.problem(new GrokCore.Failure("Grok connection failed.",false)).getMessage());
        GrokCore.Failure signIn=new GrokCore.Failure("Grok refused this sign-in. Sign in again.",true,"",401);assertSame(signIn,CallQuestions.problem(signIn));
    }
}
