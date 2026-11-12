package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Frames shaped like the gateway's /events stream as Grok Bot Android 1.17.0 reads it; all values synthetic. */
public class BotEventsTest {
    private static String upserted(JSONObject agent)throws Exception{
        return new JSONObject().put("channel","agent-upserted").put("payload",new JSONObject().put("agent",agent).put("activeAgentId","bot-1")
            .put("ordered",new JSONObject().put("epoch","e").put("replicaKey","roster").put("sequence",7))).toString();
    }
    private static String appended(String botId,JSONObject entry)throws Exception{
        return new JSONObject().put("channel","transcript").put("payload",new JSONObject().put("type","appended").put("agentId",botId).put("entry",entry)
            .put("ordered",new JSONObject().put("epoch","e").put("replicaKey","transcript").put("sequence",9))).toString();
    }
    private static JSONObject agent(long unread,boolean running)throws Exception{
        return new JSONObject().put("id","bot-1").put("name","Synthetic Bot").put("avatarShape","star").put("avatarColor","blue").put("avatarDataUrl",JSONObject.NULL)
            .put("hasUnread",unread>0).put("unreadCount",unread).put("isRunning",running).put("isRunningTurn",running).put("isComposingMessage",false)
            .put("lastMessagePreview","Synthetic  reply\ntext").put("isGroup",false).put("memberIds",new JSONArray()).put("notifyOnUpdatesEnabled",true);
    }
    private static JSONObject reply(String text)throws Exception{
        return new JSONObject().put("id","entry-2").put("seq",12).put("kind","send-message").put("role","").put("content","").put("isStreaming",false)
            .put("message",new JSONObject().put("type","text").put("content",text)).put("timestampMs",1000);
    }
    private static JSONObject mine()throws Exception{
        return new JSONObject().put("id","entry-1").put("seq",11).put("kind","message").put("role","user").put("content","Synthetic question")
            .put("clientNonce","nonce").put("isStreaming",false).put("timestampMs",900);
    }

    @Test public void framesJoinDataLinesAndSkipKeepAlives(){
        BotEvents.Frames frames=new BotEvents.Frames();
        assertNull(frames.line(": keep-alive"));assertNull(frames.line(""));
        assertNull(frames.line("event: message"));assertNull(frames.line("data: {\"a\":"));assertNull(frames.line("data:1}"));
        assertEquals("{\"a\":\n1}",frames.line(""));
        assertNull("one frame per blank line",frames.line(""));
    }
    @Test public void oversizedFramesAreDroppedAndTheNextOneStillReads(){
        BotEvents.Frames frames=new BotEvents.Frames();char[] big=new char[BotEvents.MAX_FRAME];java.util.Arrays.fill(big,'x');
        assertNull(frames.line("data: "+new String(big)));assertNull(frames.line("data: more"));assertNull(frames.line(""));
        assertNull(frames.line("data: ok"));assertEquals("ok",frames.line(""));
    }
    @Test public void rosterEventsBecomeSanitizedRowsWithoutPhotos()throws Exception{
        BotEvents.Event event=BotEvents.parse(upserted(agent(2,true).put("avatarDataUrl","data:image/png;base64,AAAA").put("avatarShape","not-a-shape")));
        assertEquals("bot",event.kind);assertEquals("bot-1",event.botId);
        assertEquals(2,event.value.getLong("unreadCount"));assertTrue(event.value.getBoolean("isRunning"));
        assertEquals("Synthetic reply text",event.value.getString("lastMessagePreview"));
        assertFalse("live rows never carry a photo",event.value.has("avatarDataUrl"));assertFalse(event.value.has("avatarShape"));
        assertFalse(event.value.has("notifyOnUpdatesEnabled"));
        assertNull("groups are not Bots",BotEvents.parse(upserted(agent(0,false).put("isGroup",true))));
    }
    @Test public void aBotsSendMessageIsAReplyWithItsText()throws Exception{
        BotEvents.Event event=BotEvents.parse(appended("bot-1",reply("Synthetic\nreply")));
        assertEquals("entry",event.kind);assertEquals("bot-1",event.botId);
        assertTrue(event.value.getBoolean("reply"));assertEquals("Synthetic reply",event.value.getString("text"));
        assertEquals("markdown reads as the chat shows it","Cheapest: Thu Oct 29, fare drop",BotEvents.parse(appended("bot-1",reply("Cheapest: **Thu Oct 29**, `fare` drop"))).value.getString("text"));
        JSONObject own=BotEvents.parse(appended("bot-1",mine())).value;
        assertFalse(own.getBoolean("reply"));assertTrue(own.getBoolean("user"));assertEquals("your own text is never kept","",own.getString("text"));
        JSONObject streaming=BotEvents.parse(appended("bot-1",new JSONObject().put("kind","message").put("role","assistant").put("content","Partial").put("isStreaming",true))).value;
        assertFalse("a reply still streaming isn't one yet",streaming.getBoolean("reply"));
        assertTrue(BotEvents.parse(appended("bot-1",new JSONObject().put("kind","message").put("role","assistant").put("content","Done").put("isStreaming",false))).value.getBoolean("reply"));
    }
    @Test public void otherChannelsAndMalformedFramesAreIgnored()throws Exception{
        assertNull(BotEvents.parse(new JSONObject().put("channel","box-disk-pressure").put("payload",JSONObject.NULL).toString()));
        assertNull(BotEvents.parse(new JSONObject().put("channel","transcript").put("payload",new JSONObject().put("type","updated").put("agentId","bot-1").put("entry",reply("x"))).toString()));
        assertNull(BotEvents.parse(appended("",reply("x"))));
        assertNull(BotEvents.parse("not json"));assertNull(BotEvents.parse("{}"));
    }
    @Test public void aReplyEndsTheWaitButYourOwnEchoDoesNot()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",0);
        assertNull(waits.entry("bot-1",BotEvents.entry(mine())));
        assertNull("other Bots don't end it",waits.entry("bot-2",BotEvents.entry(reply("Elsewhere"))));
        JSONObject alert=waits.entry("bot-1",BotEvents.entry(reply("Synthetic answer")));
        assertEquals("bot-1",alert.getString("bot"));assertEquals("Synthetic Bot",alert.getString("name"));assertEquals("Synthetic answer",alert.getString("text"));
        assertTrue(waits.isEmpty());
    }
    @Test public void aRisingUnreadCountEndsTheWaitWhenTheStreamMissedTheReply()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",0);
        JSONObject row=GrokCore.rosterRow(agent(3,true),0);
        assertNull("the first count seen is the baseline",waits.bot(row,100));
        assertNull(waits.bot(GrokCore.rosterRow(agent(1,true),0),200));
        JSONObject alert=waits.bot(GrokCore.rosterRow(agent(2,false),0),300);
        assertEquals("Synthetic reply text",alert.getString("text"));assertTrue(waits.isEmpty());
    }
    @Test public void waitsLapseAfterTenMinutesOrWhenTheBotStaysIdle()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("slow","Slow","m1",0);waits.start("quiet","Quiet","m2",0);waits.start("busy","Busy","m3",0);
        waits.bot(GrokCore.rosterRow(agent(0,false).put("id","quiet"),0),1000);
        waits.bot(GrokCore.rosterRow(agent(0,true).put("id","quiet"),0),1500);
        waits.bot(GrokCore.rosterRow(agent(0,false).put("id","quiet"),0),2000);
        waits.bot(GrokCore.rosterRow(agent(0,true).put("id","busy"),0),2000);
        waits.expire(2000+BotEvents.Waits.QUIET_MS);assertTrue("working again restarted the idle clock",waits.waiting("quiet"));
        waits.expire(2001+BotEvents.Waits.QUIET_MS);assertFalse(waits.waiting("quiet"));
        assertTrue("no news, so it waits on",waits.waiting("slow"));assertTrue("still working",waits.waiting("busy"));
        waits.expire(BotEvents.Waits.LIMIT_MS+1);assertTrue(waits.isEmpty());
    }
    @Test public void aReplyThatLandedBeforeTheWaitBeganDoesNotAlert()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",0);
        assertNull("the count already includes the reply: it's only the baseline",waits.bot(GrokCore.rosterRow(agent(1,false),0),10));
        waits.expire(11+BotEvents.Waits.QUIET_MS);assertTrue(waits.isEmpty());
    }
    @Test public void sendingAgainKeepsTheBaselineAndRestartsTheClock()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",0);
        waits.bot(GrokCore.rosterRow(agent(4,true),0),10);
        waits.start("bot-1","Synthetic Bot","m2",BotEvents.Waits.LIMIT_MS);waits.expire(BotEvents.Waits.LIMIT_MS+5);assertTrue(waits.waiting("bot-1"));
        assertNotNull(waits.bot(GrokCore.rosterRow(agent(5,false),0),BotEvents.Waits.LIMIT_MS+6));
    }
    @Test public void theChatCheckFindsTheFirstBotMessageAfterYoursByOrder()throws Exception{
        JSONObject job=new JSONObject().put("nonce","nonce").put("text","Synthetic question");
        JSONObject earlier=reply("Older answer"),tail=new JSONObject().put("entries",new JSONArray().put(earlier).put(mine()).put(reply("Synthetic answer")).put(reply("Later")));
        assertEquals("Synthetic answer",GrokCore.replyAfter(tail,job).getString("text"));
        assertNull("nothing after yours yet",GrokCore.replyAfter(new JSONObject().put("entries",new JSONArray().put(earlier).put(mine())),job));
        assertNull("yours isn't in the chat yet",GrokCore.replyAfter(new JSONObject().put("entries",new JSONArray().put(earlier)),job));
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",0);
        assertNull("an older message's check doesn't end a newer wait",waits.found("bot-1","m0",BotEvents.entry(reply("x"))));
        assertEquals("Synthetic answer",waits.found("bot-1","m1",GrokCore.replyAfter(tail,job)).getString("text"));assertTrue(waits.isEmpty());
    }
    @Test public void onlyWhatTheChatShowsCountsAsAReply()throws Exception{
        assertFalse("suppressed",BotEvents.entry(reply("Hidden").put("suppressed",new JSONObject().put("reason","x"))).getBoolean("reply"));
        assertFalse("Bot to Bot",BotEvents.entry(reply("Between Bots").put("toAgent","bot-2")).getBoolean("reply"));
        assertFalse("voice channel",BotEvents.entry(reply("Spoken").put("channel","voice")).getBoolean("reply"));
        assertFalse("voice channel on the message",BotEvents.entry(reply("Spoken").put("message",new JSONObject().put("type","text").put("content","x").put("channel","voice"))).getBoolean("reply"));
        assertFalse("not text",BotEvents.entry(reply("x").put("message",new JSONObject().put("type","image").put("content","x"))).getBoolean("reply"));
        assertFalse("still streaming",BotEvents.entry(reply("x").put("streaming",true)).getBoolean("reply"));
        assertTrue(BotEvents.entry(reply("Shown")).getBoolean("reply"));
        assertTrue(BotEvents.entry(reply("Shown").put("suppressed",JSONObject.NULL).put("toAgent",JSONObject.NULL)).getBoolean("reply"));
        assertFalse("a tool step is neither",BotEvents.entry(new JSONObject().put("kind","tool-call").put("role","assistant")).getBoolean("reply"));
    }

    // Tray frames shaped like FEATURES-SPEC §1.2–1.3 (Grok Bot Android 1.25.0); all values synthetic.
    private static String tray(JSONObject payload)throws Exception{return new JSONObject().put("channel","tray").put("payload",payload).toString();}
    private static String pushed(JSONObject tray)throws Exception{return tray(new JSONObject().put("type","pushed").put("tray",tray));}
    private static JSONObject usageTray(String id)throws Exception{
        return new JSONObject().put("kind","error").put("id",id).put("agentId","bot-1").put("createdAt",1762958400123L).put("count",1)
            .put("title","Usage limit reached").put("errorKind","usage_limit").put("detail","You've used this period's Bot allowance.")
            .put("actions",new JSONArray().put(new JSONObject().put("kind","open-url").put("label","Manage usage").put("url","https://example.invalid/usage")));
    }
    @Test public void aPushedUsageTrayBecomesAUsageNotice()throws Exception{
        BotEvents.Event event=BotEvents.parse(pushed(usageTray("tray_1")));
        assertEquals("tray",event.kind);assertEquals("bot-1",event.botId);assertEquals("pushed",event.value.getString("type"));
        JSONObject notice=event.value.getJSONObject("tray");
        assertEquals("tray_1",notice.getString("id"));assertEquals("bot-1",notice.getString("agentId"));assertEquals(1762958400123L,notice.getLong("createdAt"));
        assertEquals("Usage limit reached",notice.getString("title"));assertEquals("You've used this period's Bot allowance.",notice.getString("detail"));
        assertFalse("actions (top-up links) are never kept",notice.has("actions"));
        assertEquals("a string time reads too",5L,BotEvents.parse(pushed(usageTray("t").put("createdAt","5"))).value.getJSONObject("tray").getLong("createdAt"));
        assertEquals("no time is 0",0L,BotEvents.parse(pushed(usageTray("t").put("createdAt",JSONObject.NULL))).value.getJSONObject("tray").getLong("createdAt"));
    }
    @Test public void trayTextIsBoundedAndOneLine()throws Exception{
        char[] many=new char[513];java.util.Arrays.fill(many,'a');String long513=new String(many);
        JSONObject notice=BotEvents.parse(pushed(usageTray("tray_1").put("title","  Usage\n\tlimit\u0007 reached ").put("detail",long513))).value.getJSONObject("tray");
        assertEquals("Usage limit reached",notice.getString("title"));assertEquals(300,notice.getString("detail").length());
        assertFalse("blank text is left out",BotEvents.parse(pushed(usageTray("t").put("title"," \n"))).value.getJSONObject("tray").has("title"));
        assertNull("an id over 512 characters",BotEvents.parse(pushed(usageTray(long513))));
        assertNotNull("512 is fine",BotEvents.parse(pushed(usageTray(long513.substring(1)))));
        assertNull("a Bot id over 512 characters",BotEvents.parse(pushed(usageTray("t").put("agentId",long513))));
        assertNull("no Bot",BotEvents.parse(pushed(usageTray("t").put("agentId",""))));
        assertNull("a Bot id that isn't text",BotEvents.parse(pushed(usageTray("t").put("agentId",7))));
        assertNull("a control character in an id",BotEvents.parse(pushed(usageTray("t\n1"))));
        assertNull("no id",BotEvents.parse(pushed(usageTray(""))));
    }
    @Test public void onlyUsageLimitErrorTraysCount()throws Exception{
        // Another tray pushed under an id replaces it: that id is no longer a usage notice.
        BotEvents.Event other=BotEvents.parse(pushed(usageTray("tray_2").put("errorKind","provider_overloaded")));
        assertEquals("tray",other.kind);assertEquals("dismissed",other.value.getString("type"));assertEquals("tray_2",other.value.getString("id"));
        assertEquals("dismissed",BotEvents.parse(pushed(usageTray("tray_3").put("kind","info"))).value.getString("type"));
        JSONObject noKind=usageTray("tray_4");noKind.remove("errorKind");
        assertEquals("dismissed",BotEvents.parse(pushed(noKind)).value.getString("type"));
        BotEvents.Event dismissed=BotEvents.parse(tray(new JSONObject().put("type","dismissed").put("id","tray_1")));
        assertEquals("tray",dismissed.kind);assertEquals("dismissed",dismissed.value.getString("type"));assertEquals("tray_1",dismissed.value.getString("id"));
        assertEquals("cleared",BotEvents.parse(tray(new JSONObject().put("type","cleared"))).value.getString("type"));
        assertNull("snapshots are left to a reread",BotEvents.parse(tray(new JSONObject().put("type","snapshot").put("trays",new JSONArray().put(usageTray("tray_1"))))));
        assertNull(BotEvents.parse(tray(new JSONObject().put("type","dismissed"))));
        assertNull(BotEvents.parse(tray(new JSONObject().put("type","pushed"))));
        assertNull(BotEvents.parse(tray(new JSONObject().put("type","pushed").put("tray","not an object"))));
        assertNull(BotEvents.parse(new JSONObject().put("channel","tray").put("payload",JSONObject.NULL).toString()));
    }
    @Test public void aUsageNoticeAfterTheSendEndsTheWaitWithItsWords()throws Exception{
        long wall=1_800_000_000_000L;
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("bot-1","Synthetic Bot","m1",1000);
        JSONObject notice=BotEvents.parse(pushed(usageTray("tray_1").put("createdAt",wall))).value.getJSONObject("tray");
        assertNull("other Bots don't end it",waits.usage(new JSONObject(notice.toString()).put("agentId","bot-2"),6000,wall));
        // Sent five seconds before now: a notice from well before the send is an older one, repeated.
        assertNull(waits.usage(new JSONObject(notice.toString()).put("createdAt",wall-5000-BotEvents.Waits.CLOCK_GRACE_MS-1),6000,wall));
        assertTrue(waits.waiting("bot-1"));
        JSONObject alert=waits.usage(new JSONObject(notice.toString()).put("createdAt",wall-5000-BotEvents.Waits.CLOCK_GRACE_MS),6000,wall);
        assertEquals("a server clock a little behind still counts","bot-1",alert.getString("bot"));assertTrue(alert.getBoolean("usage"));
        assertEquals("Synthetic Bot",alert.getString("name"));assertEquals("This Bot is out of usage until it resets.",alert.getString("text"));
        assertTrue(waits.isEmpty());
        waits.start("bot-1","Synthetic Bot","m2",1000);
        assertNotNull("a notice without a time counts",waits.usage(new JSONObject(notice.toString()).put("createdAt",0),6000,wall));
        assertNull("nothing left to end",waits.usage(notice,6000,wall));
    }

    private static String transcript(String type,String botId,JSONObject entry)throws Exception{
        return new JSONObject().put("channel","transcript").put("payload",new JSONObject().put("type",type).put("agentId",botId).put("entry",entry)).toString();
    }
    @Test public void approvalRequestsComeLiveAndSoDoTheirAnswers()throws Exception{
        JSONObject pending=ApprovalsTest.entry("pending",ApprovalsTest.phoneCall());
        BotEvents.Event asked=BotEvents.parse(transcript("appended","agt_A",pending));
        assertEquals("entry",asked.kind);assertFalse("not a reply",asked.value.getBoolean("reply"));
        JSONObject approval=asked.value.getJSONObject("approval");
        assertEquals("pending",approval.getString("status"));assertEquals("phone_call",approval.getString("kind"));assertEquals("Book a table for 2 at 7pm tonight",approval.getString("line"));
        assertFalse("ids stay with the chat page, not the event",approval.has("requestId"));
        // Answered or expired elsewhere: the same entry, changed.
        BotEvents.Event expired=BotEvents.parse(transcript("updated","agt_A",ApprovalsTest.entry("expired",ApprovalsTest.phoneCall())));
        assertEquals("expired",expired.value.getJSONObject("approval").getString("status"));
        assertNull("other changed entries still don't count",BotEvents.parse(transcript("updated","bot-1",reply("x"))));
        assertNull("a hidden one doesn't",BotEvents.parse(transcript("appended","agt_A",ApprovalsTest.entry("pending",null).put("toAgent","agt_B"))).value.optJSONObject("approval"));
        assertNull(BotEvents.parse(transcript("removed","agt_A",pending)));
    }
    @Test public void aPhoneCallMovingOnComesLive()throws Exception{
        BotEvents.Event placed=BotEvents.parse(transcript("appended","agt_A",PhoneCallsTest.entry(PhoneCallsTest.call().put("status","dialing"))));
        assertEquals("dialing",placed.value.getJSONObject("call").getString("status"));assertFalse(placed.value.getBoolean("reply"));
        BotEvents.Event ended=BotEvents.parse(transcript("updated","agt_A",PhoneCallsTest.entry(PhoneCallsTest.call().put("status","ended"))));
        assertEquals("ended",ended.value.getJSONObject("call").getString("status"));assertFalse(ended.value.has("approval"));
        assertNull("not a reply alert",new BotEvents.Waits().entry("agt_A",ended.value));
    }
    @Test public void aRequestForYourOkEndsTheWaitWithItsLine()throws Exception{
        BotEvents.Waits waits=new BotEvents.Waits();waits.start("agt_A","Concierge","m1",0);
        assertNull("a settled one doesn't",waits.entry("agt_A",BotEvents.entry(ApprovalsTest.entry("denied",ApprovalsTest.phoneCall()))));
        assertNull("another Bot's doesn't",waits.entry("agt_B",BotEvents.entry(ApprovalsTest.entry("pending",ApprovalsTest.phoneCall()))));
        JSONObject alert=waits.entry("agt_A",BotEvents.entry(ApprovalsTest.entry("pending",ApprovalsTest.phoneCall())));
        assertTrue(alert.getBoolean("approval"));assertEquals("Concierge",alert.getString("name"));assertEquals("Book a table for 2 at 7pm tonight",alert.getString("text"));
        assertTrue(waits.isEmpty());
    }
}
