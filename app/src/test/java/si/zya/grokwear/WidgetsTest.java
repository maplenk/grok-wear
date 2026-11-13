package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;

/** A Bot's question with option buttons, shaped as MORE-FEATURES-SPEC §5.1 and the live entry show it; values synthetic. */
public class WidgetsTest {
    static JSONObject widget(){
        try{return new JSONObject().put("prompt","Can I read today's events on your phone calendar?").put("options",new JSONArray()
            .put(new JSONObject().put("label","Allow").put("value","allow").put("style","primary")).put(new JSONObject().put("label","Don't allow").put("value","deny")));}
        catch(Exception e){throw new AssertionError(e);}
    }
    /** As the live entry comes: no author, the widget on a send-message. */
    static JSONObject entry(JSONObject widget)throws Exception {
        return new JSONObject().put("id","t93s406").put("kind","send-message").put("seq",406).put("timestampMs",1762958400000L)
            .put("message",new JSONObject().put("type","widget").put("widget",widget));
    }

    @Test public void theLiveShapeReadsAsAQuestionWithItsOptions()throws Exception {
        JSONObject w=Widgets.of(entry(widget()),"agt_A");
        assertEquals("t93s406",w.getString("entryId"));assertEquals("no author: the chat's Bot","agt_A",w.getString("agentId"));
        assertEquals("Can I read today's events on your phone calendar?",w.getString("prompt"));
        JSONArray options=w.getJSONArray("options");assertEquals(2,options.length());
        assertEquals("Allow",options.getJSONObject(0).getString("label"));assertEquals("allow",options.getJSONObject(0).getString("value"));
        assertFalse("primary looks like any option",options.getJSONObject(0).has("danger"));
        assertFalse(w.getBoolean("multiSelect"));assertFalse(w.getBoolean("allowCustom"));assertFalse(w.getBoolean("dismissOnMoveOn"));
        assertFalse(w.has("special"));assertFalse(w.has("answer"));assertFalse(w.has("dismissed"));
        assertEquals("open",Widgets.state(w,false));assertTrue(Widgets.answerable(w));
        assertEquals("",Widgets.of(entry(widget()),"").getString("agentId"));
        assertEquals("agt_B",Widgets.of(entry(widget()).put("author",new JSONObject().put("id","agt_B")),"").getString("agentId"));
    }
    @Test public void anOptionSendsItsValueElseItsLabel()throws Exception {
        JSONObject spec=widget();spec.getJSONArray("options").put(new JSONObject().put("label","Ask me later")).put(new JSONObject().put("label","Never").put("value","").put("style","danger").put("description","Stops asking"))
            .put(new JSONObject().put("label","Pick a main Bot").put("value","sand:choose-main-agent")).put(new JSONObject().put("label","Call").put("value","sand:start-voice-call"))
            .put(new JSONObject().put("value","no label")).put("not an option");
        JSONArray options=Widgets.of(entry(spec),"agt_A").getJSONArray("options");
        assertEquals("the phone's own values and unlabeled options are left out",4,options.length());
        assertEquals("Ask me later",options.getJSONObject(2).getString("value"));
        JSONObject never=options.getJSONObject(3);assertEquals("an empty value reads as absent","Never",never.getString("value"));
        assertTrue(never.getBoolean("danger"));assertEquals("Stops asking",never.getString("description"));
        JSONObject many=widget();for(int i=0;i<30;i++)many.getJSONArray("options").put(new JSONObject().put("label","Option "+i));
        assertEquals(Widgets.MAX_OPTIONS,Widgets.of(entry(many),"agt_A").getJSONArray("options").length());
    }
    @Test public void onlyAWidgetWithATextPromptIsAQuestion()throws Exception {
        assertNull(Widgets.of(entry(widget().put("prompt",3)),"agt_A"));
        JSONObject noPrompt=widget();noPrompt.remove("prompt");assertNull(Widgets.of(entry(noPrompt),"agt_A"));
        assertNull(Widgets.of(entry(widget()).put("kind","message"),"agt_A"));
        JSONObject text=entry(widget());text.getJSONObject("message").put("type","text");assertNull(Widgets.of(text,"agt_A"));
        assertNull(Widgets.of(entry(widget()).put("id",""),"agt_A"));assertNull(Widgets.of(null,"agt_A"));
        JSONObject empty=Widgets.of(entry(widget().put("prompt","").put("options",new JSONArray())),"agt_A");
        assertEquals("",empty.getString("prompt"));assertFalse("no options, nothing to tap",Widgets.answerable(empty));
        for(String form:new String[]{"ruleChange","steps","order"}){JSONObject special=Widgets.of(entry(widget().put(form,new JSONObject())),"agt_A");
            assertTrue(form,special.getBoolean("special"));assertFalse("left to the phone",Widgets.answerable(special));}
    }
    @Test public void itsStateFollowsTheOfficialPresentation()throws Exception {
        JSONObject answered=Widgets.of(entry(widget()).put("respondedValue","allow"),"agt_A");
        assertEquals("answered",Widgets.state(answered,true));assertEquals("Allow",Widgets.answerText(answered,answered.getString("answer")));
        assertEquals("echoed",Widgets.state(Widgets.of(entry(widget()).put("respondedValue","allow").put("respondedValueEchoed",true),"agt_A"),false));
        assertEquals("dismissed",Widgets.state(Widgets.of(entry(widget()).put("widgetDismissed",true),"agt_A"),false));
        JSONObject moveOn=Widgets.of(entry(widget().put("dismissOnMoveOn",true)),"agt_A");
        assertEquals("open",Widgets.state(moveOn,false));assertEquals("stale once you moved on","dismissed",Widgets.state(moveOn,true));
        assertEquals("only a dismissOnMoveOn question goes stale","open",Widgets.state(Widgets.of(entry(widget()),"agt_A"),true));
        JSONArray rows=new JSONArray().put(new JSONObject().put("role","assistant")).put(new JSONObject().put("role","widget")).put(new JSONObject().put("role","assistant"));
        assertFalse(Widgets.movedOn(rows,1));rows.put(new JSONObject().put("role","user"));assertTrue(Widgets.movedOn(rows,1));assertFalse(Widgets.movedOn(rows,3));
    }
    @Test public void severalPicksGoInOptionOrderOncePerLine()throws Exception {
        JSONObject spec=new JSONObject().put("prompt","Which days?").put("multiSelect",true).put("options",new JSONArray()
            .put(new JSONObject().put("label","Mon").put("value","monday")).put(new JSONObject().put("label","Tue").put("value","tuesday"))
            .put(new JSONObject().put("label","Tue again").put("value"," Tuesday ")).put(new JSONObject().put("label","Wed")));
        JSONObject w=Widgets.of(entry(spec),"agt_A");assertTrue(w.getBoolean("multiSelect"));
        assertEquals("monday\ntuesday\nWed",Widgets.joinPicked(w,Set.of(3,0,1,2)));assertEquals("",Widgets.joinPicked(w,Set.of()));
        assertEquals("Mon, Tue, Wed",Widgets.answerText(w,"monday\ntuesday\nWed"));
        assertEquals("a typed answer reads as typed","Mon, Thursday",Widgets.answerText(w,"monday\nThursday"));
        assertTrue(Widgets.of(entry(widget().put("allowCustom",true)),"agt_A").getBoolean("allowCustom"));
    }
    @Test public void answersAndDismissalsCarryOnlyTheIdsAndTheValue()throws Exception {
        JSONObject w=Widgets.of(entry(widget()),"agt_A");
        CallQuestionsTest.same("{\"agentId\":\"agt_A\",\"entryId\":\"t93s406\",\"value\":\"allow\"}",Widgets.body(w,"allow"));
        CallQuestionsTest.same("{\"agentId\":\"agt_A\",\"entryId\":\"t93s406\"}",Widgets.body(w,null));
        try{Widgets.body(w,"");fail("Nothing is sent for an empty answer");}catch(GrokCore.Failure expected){}
        try{Widgets.body(Widgets.of(entry(widget()),""),"allow");fail("No Bot, no answer");}catch(GrokCore.Failure expected){assertEquals(Widgets.FAILED,expected.getMessage());}
    }
    @Test public void groksServerAndTheGatewayEachSayWhetherTheyTookIt()throws Exception {
        assertNull(Widgets.refused(new JSONObject().put("accepted",true),true));
        assertEquals("",Widgets.refused(new JSONObject(),true));assertEquals("",Widgets.refused(null,true));
        assertEquals("Already answered.",Widgets.refused(new JSONObject().put("refusal",new JSONObject().put("failureCode","widget/answered").put("message","Already\n answered.")),true));
        assertNull("an empty gateway reply is accepted",Widgets.refused(new JSONObject(),false));assertNull(Widgets.refused(null,false));
        assertEquals("",Widgets.refused(new JSONObject().put("accepted",false),false));
        assertEquals("No.",Widgets.refused(new JSONObject().put("accepted",true).put("refusal",new JSONObject().put("message","No.")),false));
        assertEquals(Widgets.FAILED,Widgets.problem(new GrokCore.Failure("Grok connection failed.",false)).getMessage());
        GrokCore.Failure signIn=new GrokCore.Failure("Grok refused this sign-in. Sign in again.",true,"",403);assertSame(signIn,Widgets.problem(signIn));
    }
    @Test public void anAskToUseThePhoneIsOnlyShownWhilePending()throws Exception {
        JSONObject ask=new JSONObject().put("id","perm-1").put("kind","send-message").put("message",new JSONObject().put("type","local-tool-permission")
            .put("ask",new JSONObject().put("status","pending").put("action","run-phone-tool").put("machineId","m-phone")));
        assertEquals("perm-1",Widgets.phoneTool(ask).getString("entryId"));
        ask.getJSONObject("message").getJSONObject("ask").put("status","approved");assertNull("settled",Widgets.phoneTool(ask));
        ask.getJSONObject("message").getJSONObject("ask").put("status","pending").put("action","run-command");assertNull("a computer's ask isn't the phone's",Widgets.phoneTool(ask));
        assertNull(Widgets.phoneTool(entry(widget())));assertNull(Widgets.phoneTool(null));
    }
    @Test public void rosterPreviewsReadAsTheOfficialAppWordsThem()throws Exception {
        JSONObject options=new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetOptions",new JSONObject().put("prompt","Lunch?").put("options",new JSONArray().put("Yes").put("No"))));
        assertEquals("Lunch? — Yes / No",ServerTranscripts.previewOf(options));
        assertEquals("Lunch? — Yes",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetAnswered",new JSONObject().put("prompt","Lunch?").put("answer","Yes")))));
        assertEquals("an empty prompt reads Question","Question — Yes / No",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetOptions",new JSONObject().put("prompt","").put("options",new JSONArray().put("Yes").put("No"))))));
        assertEquals("Question — Yes",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetAnswered",new JSONObject().put("answer","Yes")))));
        assertEquals("Question",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetPrompt",new JSONObject()))));
    }
}
