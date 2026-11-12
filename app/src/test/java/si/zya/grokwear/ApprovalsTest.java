package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Approval entries shaped like FEATURES-SPEC §2.1–2.4 (Grok Bot Android 1.25.0); all values synthetic. */
public class ApprovalsTest {
    static JSONObject entry(String status,JSONObject copy)throws Exception {
        JSONObject approval=new JSONObject().put("requestId","req_9").put("status",status).put("surface","mcp").put("summary","Place a phone call to Luigi's Trattoria");
        if(copy!=null)approval.put("summaryCopy",copy);
        return new JSONObject().put("id","ent_123").put("kind","send-message").put("author",new JSONObject().put("id","agt_A")).put("timestampMs",1762958400000L)
            .put("message",new JSONObject().put("type","auto-review-approval").put("approval",approval));
    }
    static JSONObject copy(String kind,JSONObject params)throws Exception {return new JSONObject().put("kind",kind).put("params",params);}
    static JSONObject phoneCall()throws Exception {
        return copy("phone_call",new JSONObject().put("destination","Luigi's Trattoria (+1 415 555 0100)").put("request","Book a table for 2\nat 7pm tonight").put("calleeKind","business").put("ringIfNeeded",true));
    }

    @Test public void aPhoneCallRequestReadsAsTheCardShowsIt()throws Exception {
        JSONObject a=Approvals.of(entry("pending",phoneCall()),"agt_chat");
        assertEquals("ent_123",a.getString("entryId"));assertEquals("the author answers, not the chat's Bot","agt_A",a.getString("agentId"));
        assertEquals("req_9",a.getString("requestId"));assertEquals("pending",a.getString("status"));assertEquals("phone_call",a.getString("kind"));
        assertEquals("Book a table for 2 at 7pm tonight",a.getString("line"));
        assertEquals("Call Luigi's Trattoria (+1 415 555 0100)?",Approvals.headline(a,"Concierge"));
        assertArrayEquals(new String[]{"Call","Cancel"},Approvals.choices(a));
    }
    @Test public void eachKindHasItsQuestionLineAndAnswers()throws Exception {
        JSONObject context=Approvals.of(entry("pending",copy("phone_call_context",new JSONObject().put("context","Party of two, window seat"))),"agt_A");
        assertEquals("Send this to your call?",Approvals.headline(context,"Concierge"));assertEquals("Party of two, window seat",context.getString("line"));
        assertArrayEquals(new String[]{"Send","Cancel"},Approvals.choices(context));
        JSONObject terms=Approvals.of(entry("pending",copy("phone_call_terms",new JSONObject().put("termsUrl","https://example.invalid/t").put("policyUrl","https://example.invalid/p"))),"agt_A");
        assertEquals("Concierge wants to make phone calls for you",Approvals.headline(terms,"Concierge"));
        assertNull("legal consent stays on the phone",Approvals.choices(terms));assertEquals("Answer on your phone",Approvals.elsewhere(terms));
        JSONObject elicit=Approvals.of(entry("pending",copy("mcp_elicitation",new JSONObject())),"agt_A");
        assertNull(Approvals.choices(elicit));assertEquals("Answer on your computer",Approvals.elsewhere(elicit));
        JSONObject shell=Approvals.of(entry("pending",copy("shell",new JSONObject().put("description","List files").put("workingDirectory","~/src").put("surface","host_shell"))),"agt_A");
        assertEquals("List files on your local computer from ~/src",shell.getString("line"));assertEquals("Allow Concierge to run a command on your computer?",Approvals.headline(shell,"Concierge"));
        JSONObject box=Approvals.of(entry("pending",copy("shell",new JSONObject().put("description","List files").put("surface","box_shell"))),"agt_A");
        assertEquals("List files on Grok Bot's computer",box.getString("line"));assertEquals("Allow Concierge to run a command?",Approvals.headline(box,"Concierge"));
        JSONObject mcp=Approvals.of(entry("pending",copy("mcp",new JSONObject().put("serverDisplayName","Calendar").put("toolName","create_event").put("destinationHint","Work"))),"agt_A");
        assertEquals("Use Calendar tool create_event for Work",mcp.getString("line"));assertEquals("Allow Concierge to use a connected app?",Approvals.headline(mcp,"Concierge"));
        assertArrayEquals(new String[]{"Allow","Deny"},Approvals.choices(mcp));
        JSONObject plain=Approvals.of(entry("pending",null),"agt_A");
        assertEquals("no copy: the summary","Place a phone call to Luigi's Trattoria",plain.getString("line"));assertEquals("Approval needed",Approvals.headline(plain,"Concierge"));
        assertArrayEquals(new String[]{"Allow","Deny"},Approvals.choices(plain));
        JSONObject vague=Approvals.of(entry("pending",copy("mcp",new JSONObject().put("toolName","x"))),"agt_A");
        assertEquals("missing parts fall back to the summary","Place a phone call to Luigi's Trattoria",vague.getString("line"));
        assertEquals("Allow your Bot to use a connected app?",Approvals.headline(mcp,""));
    }
    @Test public void settledRequestsSayHowTheyEndedAndOfferNothing()throws Exception {
        String[][] cases={{"approved","Allowed once"},{"always","Always allowed"},{"denied","Denied"},{"expired","Expired"},{"withdrawn","Closed"}};
        for(String[] c:cases){JSONObject a=Approvals.of(entry(c[0],phoneCall()),"agt_A");
            assertNull(c[0],Approvals.choices(a));assertEquals(c[0],c[1],Approvals.settled(a));}
        assertEquals("closed",Approvals.of(entry("withdrawn",null),"agt_A").getString("status"));
        String[][] context={{"approved","Approved to send"},{"denied","Not sent"},{"expired","Request expired"}};
        for(String[] c:context)assertEquals(c[1],Approvals.settled(Approvals.of(entry(c[0],copy("phone_call_context",new JSONObject().put("context","x"))),"agt_A")));
    }
    @Test public void onlyAutoReviewEntriesWithTheirIdsCount()throws Exception {
        assertNull(Approvals.of(entry("pending",null).put("kind","message"),"agt_A"));
        assertNull("another kind of card",Approvals.of(entry("pending",null).put("message",new JSONObject().put("type","virtual-card-approval").put("approval",new JSONObject().put("requestId","r").put("status","pending"))),"agt_A"));
        JSONObject noRequest=entry("pending",null);noRequest.getJSONObject("message").getJSONObject("approval").remove("requestId");assertNull(Approvals.of(noRequest,"agt_A"));
        assertNull("no entry id",Approvals.of(entry("pending",null).put("id",""),"agt_A"));
        assertNull(Approvals.of(entry("pending",null).put("message",new JSONObject().put("type","auto-review-approval")),"agt_A"));
        assertNull(Approvals.of(null,"agt_A"));
        JSONObject noAuthor=entry("pending",null);noAuthor.remove("author");
        assertEquals("no author: the chat's Bot","agt_chat",Approvals.of(noAuthor,"agt_chat").getString("agentId"));
        assertEquals("",Approvals.of(noAuthor,"").getString("agentId"));
    }
    @Test public void idsAndTextAreBounded()throws Exception {
        char[] many=new char[513];java.util.Arrays.fill(many,'r');String long513=new String(many);
        JSONObject longRequest=entry("pending",null);longRequest.getJSONObject("message").getJSONObject("approval").put("requestId",long513);assertNull(Approvals.of(longRequest,"agt_A"));
        JSONObject fits=entry("pending",null);fits.getJSONObject("message").getJSONObject("approval").put("requestId",long513.substring(1));assertNotNull(Approvals.of(fits,"agt_A"));
        assertNull("a control character in an id",Approvals.of(entry("pending",null).put("id","ent\n1"),"agt_A"));
        assertEquals("a bad author id falls back","agt_A",Approvals.of(entry("pending",null).put("author",new JSONObject().put("id",long513)),"agt_A").getString("agentId"));
        JSONObject wordy=Approvals.of(entry("pending",copy("phone_call",new JSONObject().put("destination",long513).put("request","a\u0000b  "+long513))),"agt_A");
        assertEquals(300,wordy.getString("line").length());assertTrue(wordy.getString("line").startsWith("a b r"));assertEquals(300,wordy.getString("destination").length());
        assertEquals("an odd kind is no kind","",Approvals.of(entry("pending",copy("Phone Call!",new JSONObject())),"agt_A").getString("kind"));
    }
    @Test public void theAnswerGoesToTheGatewayWithItsIdsOrToTheServerWithout()throws Exception {
        JSONObject a=Approvals.of(entry("pending",phoneCall()),"agt_chat");
        JSONObject gateway=Approvals.gatewayBody(a,true);
        assertEquals(sorted(new JSONObject().put("agentId","agt_A").put("entryId","ent_123").put("requestId","req_9").put("resolution","approved").put("approvalPlatform","android")),sorted(gateway));
        assertEquals("denied",Approvals.gatewayBody(a,false).getString("resolution"));
        JSONObject server=Approvals.serverBody(a,true);
        assertEquals(sorted(new JSONObject().put("agentId","agt_A").put("requestId","req_9").put("resolution","GROK_BOT_AUTO_REVIEW_APPROVAL_RESOLUTION_APPROVED").put("approvalPlatform","android")),sorted(server));
        assertFalse("the server takes no entry id",server.has("entryId"));
        assertEquals("GROK_BOT_AUTO_REVIEW_APPROVAL_RESOLUTION_DENIED",Approvals.serverBody(a,false).getString("resolution"));
        JSONObject noBot=Approvals.of(new JSONObject(entry("pending",null).toString()).put("author",new JSONObject()),"");
        try{Approvals.gatewayBody(noBot,true);fail("no Bot to answer");}catch(GrokCore.Failure expected){assertEquals(Approvals.ELSEWHERE,expected.code);}
        try{Approvals.serverBody(noBot,true);fail("no Bot to answer");}catch(GrokCore.Failure expected){assertEquals(Approvals.ELSEWHERE,expected.code);}
    }
    private static String sorted(JSONObject o){java.util.TreeMap<String,Object> out=new java.util.TreeMap<>();java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){String k=keys.next();out.put(k,o.opt(k));}return out.toString();}
    @Test public void refusalsReadInPlainWords(){
        GrokCore.Failure stale=Approvals.problem(new GrokCore.Failure("Grok request failed (HTTP 409).",true,"auto-review/stale",409),false);
        assertEquals("Already answered or expired.",stale.getMessage());assertEquals(Approvals.STALE,stale.code);
        assertEquals(Approvals.STALE,Approvals.problem(new GrokCore.Failure("x",true,"not_found",404),true).code);
        GrokCore.Failure old=Approvals.problem(new GrokCore.Failure("x",true,"",404),false);
        assertEquals(Approvals.ELSEWHERE,old.code);assertTrue(old.getMessage().contains("Answer on your phone"));
        GrokCore.Failure lost=Approvals.problem(new GrokCore.Failure("Grok connection failed.",false),true);
        assertFalse("a lost reply is not a refusal",lost.rejected);assertTrue(lost.getMessage().startsWith("Couldn't confirm your answer"));
        assertEquals("Couldn't answer. Open on phone.",Approvals.problem(new GrokCore.Failure("x",true,"failed_precondition",400),true).getMessage());
        GrokCore.Failure signIn=new GrokCore.Failure("Grok refused this sign-in. Sign in again.",true,"",401);
        assertSame("sign-in trouble keeps its words",signIn,Approvals.problem(signIn,true));
        GrokCore.Failure elsewhere=new GrokCore.Failure("Answer this on your phone.",true,Approvals.ELSEWHERE);
        assertSame(elsewhere,Approvals.problem(elsewhere,false));
    }
}
