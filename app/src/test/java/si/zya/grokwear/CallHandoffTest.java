package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** End-of-call handoff formatting and delivery, matching the official clients. */
public final class CallHandoffTest {
    static JSONArray turns(String... speakerText)throws Exception {
        JSONArray turns=new JSONArray();
        for(int i=0;i<speakerText.length;i+=2)turns.put(new JSONObject().put("id","t"+i).put("speaker",speakerText[i]).put("text",speakerText[i+1]).put("atMs",i));
        return turns;
    }
    @Test public void onlyTemporalBotsTakeTheHandoff()throws Exception {
        assertTrue(CallHandoff.applies(new JSONObject().put("harness","temporal")));
        assertFalse(CallHandoff.applies(new JSONObject().put("harness","box")));
        assertFalse(CallHandoff.applies(new JSONObject()));
        assertFalse(CallHandoff.applies(null));
    }
    @Test public void silentCallSendsOnlyTheNotice()throws Exception {
        assertEquals(Arrays.asList(CallHandoff.CALL_ENDED),CallHandoff.requests(turns("assistant","Hi there.")));
        assertEquals("The call ended. This channel is closed from now on, so anything still owed goes in the chat.",CallHandoff.CALL_ENDED);
    }
    @Test public void lastCallerLineIsTheRequestAndEarlierLinesAreQuoted()throws Exception {
        List<String> requests=CallHandoff.requests(turns("user","What's on today?","assistant","Two meetings.","user","  ","user","Move the second one to Friday."));
        assertEquals(2,requests.size());
        assertEquals("Move the second one to Friday. What the user said on the call: \"What's on today?\"",requests.get(1));
        assertEquals("Only one line",CallHandoff.requests(turns("user","Only one line")).get(1));
    }
    @Test public void linesAlreadyHandedOffAsATaskAreNotSentAgain()throws Exception {
        JSONArray call=turns("user","Check my inbox","assistant","On it.","user","Also book the table for eight");
        assertEquals("Also book the table for eight",CallHandoff.requests(call,1).get(1));
        assertEquals(java.util.Arrays.asList(CallHandoff.CALL_ENDED),CallHandoff.requests(turns("user","Check my inbox"),1));
    }
    @Test public void keepsTheLastSixCallerLines()throws Exception {
        String[] spoken=new String[16];for(int i=0;i<8;i++){spoken[2*i]="user";spoken[2*i+1]="line "+i;}
        String request=CallHandoff.requests(turns(spoken)).get(1);
        assertTrue(request.startsWith("line 7 What the user said on the call: \"line 2\""));
        assertFalse(request.contains("line 1"));
    }
    @Test public void longRequestsDropTheOldestQuotesThenCutToTwoThousand(){
        String big=new String(new char[1500]).replace('\0','a');
        List<String> lines=new ArrayList<>(Arrays.asList(big,"short ask"));
        assertEquals("short ask What the user said on the call: \""+big+"\"",CallHandoff.leftoverRequest(lines));
        lines=new ArrayList<>(Arrays.asList(big,big,"short ask"));
        String fitted=CallHandoff.leftoverRequest(lines);
        assertTrue(fitted.length()<=CallHandoff.MAX_REQUEST);assertTrue(fitted.startsWith("short ask"));
        String huge=new String(new char[2500]).replace('\0','b');
        assertEquals(CallHandoff.MAX_REQUEST,CallHandoff.leftoverRequest(new ArrayList<>(Arrays.asList(huge))).length());
    }
    @Test public void sendsInOrderAndStopsWithoutRetryOnTheFirstRefusal()throws Exception {
        List<JSONObject> sent=new ArrayList<>();
        JSONObject line=new JSONObject().put("theyJustTalked",true);
        String outcome=CallHandoff.send(body->{sent.add(body);return new JSONObject().put("kind","VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_SERVED").put("output",new JSONObject().put("relay","accepted"));},
            "bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED,"Book it"),line);
        assertEquals("accepted",outcome);assertEquals(2,sent.size());
        assertEquals("send_task",sent.get(0).getString("name"));assertEquals("bot-1",sent.get(0).getString("agentId"));assertEquals("call-1",sent.get(0).getString("callId"));
        assertEquals(CallHandoff.CALL_ENDED,sent.get(0).getJSONObject("input").getString("request"));assertEquals("Book it",sent.get(1).getJSONObject("input").getString("request"));
        assertTrue(sent.get(1).getJSONObject("line").getBoolean("theyJustTalked"));
        sent.clear();
        assertEquals("refused relay=refused",CallHandoff.send(body->{sent.add(body);return new JSONObject().put("kind","VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_SERVED").put("output",new JSONObject().put("relay","refused").put("spoken","private words"));},
            "bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED,"Book it"),line));
        assertEquals("A refused notice must not send the leftover request",1,sent.size());
        sent.clear();
        assertEquals("refused kind=VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_UNKNOWN_TOOL",CallHandoff.send(body->{sent.add(body);return new JSONObject().put("kind","VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_UNKNOWN_TOOL").put("output",new JSONObject().put("error","no"));},
            "bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED,"Book it"),line));
        assertEquals("refused kind=other",CallHandoff.send(body->new JSONObject().put("kind","bad value\n"),"bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED),line));
        assertEquals("accepted",CallHandoff.send(body->new JSONObject().put("kind","served").put("output",new JSONObject().put("relay","accepted")),"bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED),line));
        sent.clear();
        sent.clear();
        assertEquals("failed",CallHandoff.send(body->{sent.add(body);throw new Exception("offline");},"bot-1","call-1",Arrays.asList(CallHandoff.CALL_ENDED,"Book it"),line));
        assertEquals("A failure is never retried",1,sent.size());
    }
}
