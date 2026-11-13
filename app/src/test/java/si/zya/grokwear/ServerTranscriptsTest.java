package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

public class ServerTranscriptsTest {
    static JSONObject row(long seq,JSONObject entry)throws Exception {
        return new JSONObject().put("seq",Long.toString(seq)).put("updatedSeq",Long.toString(seq)).put("entryId",entry.optString("id"))
            .put("body",Base64.getEncoder().encodeToString(entry.toString().getBytes(StandardCharsets.UTF_8)));
    }
    static JSONObject message(String id,String role,String text)throws Exception {
        return new JSONObject().put("id",id).put("kind","message").put("role",role).put("content",text);
    }

    @Test public void aServerPageReadsLikeAGatewayPage()throws Exception {
        // Newest first, one row more than asked: the extra row only says there's more.
        JSONObject reply=new JSONObject().put("entries",new JSONArray()
            .put(row(385,message("t86u","user","third"))).put(row(384,message("t85u","assistant","second")))
            .put(row(383,message("t84u","user","first"))));
        JSONObject page=ServerTranscripts.page(reply,2);
        JSONArray entries=page.getJSONArray("entries");
        assertEquals(2,entries.length());
        assertEquals("t85u",entries.getJSONObject(0).getString("id"));assertEquals(384,entries.getJSONObject(0).getLong("seq"));
        assertEquals("third",entries.getJSONObject(1).getString("content"));
        assertEquals(384,page.getLong("nextBeforeSeq"));
        // The whole history: no cursor.
        assertFalse(ServerTranscripts.page(reply,5).has("nextBeforeSeq"));
        assertEquals("first",GrokCore.entryText(ServerTranscripts.page(reply,5).getJSONArray("entries").getJSONObject(0)));
    }
    @Test public void rowsWithoutAReadableBodyAreSkipped()throws Exception {
        JSONObject reply=new JSONObject().put("entries",new JSONArray()
            .put(new JSONObject().put("seq","9").put("blobHash","abc"))
            .put(new JSONObject().put("seq","8").put("body","bm90IGpzb24="))
            .put(new JSONObject().put("seq","x").put("body","e30="))
            .put(row(7,message("ok","assistant","fine"))));
        JSONArray entries=ServerTranscripts.page(reply,10).getJSONArray("entries");
        assertEquals(1,entries.length());assertEquals("ok",entries.getJSONObject(0).getString("id"));
        assertEquals(-1,ServerTranscripts.seqOf("12a"));assertEquals(-1,ServerTranscripts.seqOf(-3));assertEquals(42,ServerTranscripts.seqOf(42));
    }
    static byte[] frame(int flags,String json){
        byte[] body=json.getBytes(StandardCharsets.UTF_8);byte[] out=new byte[body.length+5];out[0]=(byte)flags;
        out[1]=(byte)(body.length>>>24);out[2]=(byte)(body.length>>>16);out[3]=(byte)(body.length>>>8);out[4]=(byte)body.length;
        System.arraycopy(body,0,out,5,body.length);return out;
    }
    @Test public void theStreamReadsMessagesUntilItsEnd()throws Exception {
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        wire.write(frame(0,"{\"connected\":{}}"));wire.write(frame(0,"{\"agentState\":{\"snapshot\":true}}"));wire.write(frame(2,"{}"));
        ServerTranscripts.Frames frames=new ServerTranscripts.Frames(new ByteArrayInputStream(wire.toByteArray()));
        assertTrue(frames.next().has("connected"));assertTrue(frames.next().has("agentState"));assertNull(frames.next());
        // The request is one enveloped message.
        byte[] request=ServerTranscripts.envelope(new JSONObject().put("cursors",new JSONArray()));
        assertEquals(0,request[0]);assertEquals(request.length-5,((request[1]&255)<<24)|((request[2]&255)<<16)|((request[3]&255)<<8)|(request[4]&255));
    }
    @Test public void anErrorTrailerOrABrokenStreamThrows()throws Exception {
        try{new ServerTranscripts.Frames(new ByteArrayInputStream(frame(2,"{\"error\":{\"code\":\"unauthenticated\",\"message\":\"x\"}}"))).next();fail();}
        catch(GrokCore.Failure refused){assertEquals("unauthenticated",refused.code);}
        try{new ServerTranscripts.Frames(new ByteArrayInputStream(frame(0,"{}"))).next();new ServerTranscripts.Frames(new ByteArrayInputStream(new byte[0])).next();fail();}
        catch(IOException ended){assertTrue(ended.getMessage().contains("end-of-stream"));}
        try{new ServerTranscripts.Frames(new ByteArrayInputStream(frame(1,"{}"))).next();fail();}catch(IOException compressed){}
        byte[] huge={0,0x7f,0,0,0};
        try{new ServerTranscripts.Frames(new ByteArrayInputStream(huge)).next();fail();}catch(IOException tooLarge){}
    }
    @Test public void agentStateGivesTheBotRowItsUnreadPreviewAndWork()throws Exception {
        ServerTranscripts.States states=new ServerTranscripts.States();long now=1_800_000_000_000L;
        JSONObject base=new JSONObject().put("id","A").put("name","TheBot").put("hasUnread",true).put("unreadCount",4).put("lastMessagePreview","stale from the gateway");
        // Unknown Bots keep the gateway's state.
        assertEquals(4,states.over(base,now).getLong("unreadCount"));
        Set<String> touched=states.apply(new JSONObject().put("snapshot",true)
            .put("client",new JSONArray().put(new JSONObject().put("agentId","A").put("unreadCount",0).put("unreadThreadCount",1)
                .put("lastActivityAtMs",String.valueOf(now-5000)).put("lastEntryPreview",new JSONObject().put("text","Done,\n booked it"))))
            .put("live",new JSONArray().put(new JSONObject().put("agentId","A").put("isRunning",true).put("isComposingMessage",true).put("updatedAtMs",now-1000))
                .put(new JSONObject().put("agentId","A").put("sessionId","group:x").put("isRunning",false))));
        assertEquals(Set.of("A"),touched);
        JSONObject row=states.over(base,now);
        assertEquals("TheBot",row.getString("name"));assertEquals(0,row.getLong("unreadCount"));
        assertTrue("an unread thread still counts",row.getBoolean("hasUnread"));
        assertEquals("Done, booked it",row.getString("lastMessagePreview"));assertEquals(now-5000,row.getLong("lastActivityAt"));
        assertTrue(row.getBoolean("isRunning"));assertTrue(row.getBoolean("isRunningTurn"));assertTrue(row.getBoolean("isComposingMessage"));
        // A running Bot the server hasn't updated for 90 s counts as idle.
        assertFalse(states.over(base,now+91_000).getBoolean("isRunning"));
        // A delta replaces that Bot's client state; a new snapshot clears what it doesn't name.
        states.apply(new JSONObject().put("client",new JSONArray().put(new JSONObject().put("agentId","A").put("unreadCount",2).put("lastMessagePreview","plain"))));
        assertEquals("plain",states.over(base,now).getString("lastMessagePreview"));assertEquals(2,states.over(base,now).getLong("unreadCount"));
        states.apply(new JSONObject().put("snapshot",true));assertFalse(states.knows("A"));
    }
    @Test public void aBotWaitingForYouSaysSoAndKeepsSayingItWhenItGoesStale()throws Exception {
        ServerTranscripts.States states=new ServerTranscripts.States();long now=1_800_000_000_000L;
        JSONObject base=new JSONObject().put("id","A").put("name","TheBot").put("awaitingUserResponse",new JSONObject().put("reason","gateway"));
        assertTrue("unknown Bots keep the gateway's",states.over(base,now).has("awaitingUserResponse"));
        // The main session isn't waiting, a task session is: any waiting session counts. A group seat isn't the Bot's own.
        states.apply(new JSONObject().put("live",new JSONArray()
            .put(new JSONObject().put("agentId","A").put("isRunning",true).put("updatedAtMs",now-1000))
            .put(new JSONObject().put("agentId","A").put("sessionId","task:7").put("awaiting",new JSONObject().put("tabId","t").put("reason","approval").put("sinceMs",String.valueOf(now-500))))
            .put(new JSONObject().put("agentId","B").put("sessionId","group:x").put("awaiting",new JSONObject()))));
        JSONObject row=states.over(base,now);
        assertTrue(row.getBoolean("awaiting"));assertFalse("the server's state replaces the gateway's",row.has("awaitingUserResponse"));
        // Gone stale: idle again, still waiting (settleStale keeps it).
        JSONObject stale=states.over(base,now+91_000);assertFalse(stale.getBoolean("isRunning"));assertTrue(stale.getBoolean("awaiting"));
        assertFalse("a group seat waiting doesn't make the Bot wait",states.over(new JSONObject().put("id","B"),now).has("awaiting"));
        // The waiting session moves on: the Bot no longer waits, whatever the gateway said.
        states.apply(new JSONObject().put("live",new JSONArray().put(new JSONObject().put("agentId","A").put("sessionId","task:7"))));
        row=states.over(base,now);assertFalse(row.has("awaiting"));assertFalse(row.has("awaitingUserResponse"));
        // A server row keeps it through the Bot list's own reduction, as a gateway row does.
        states.apply(new JSONObject().put("live",new JSONArray().put(new JSONObject().put("agentId","A").put("awaiting",new JSONObject()))));
        assertTrue(GrokCore.rosterRow(states.over(base,now),0).getBoolean("awaiting"));
    }
    @Test public void structuredPreviewsReadAsTheOfficialAppShowsThem()throws Exception {
        assertEquals("Messaged Ops: on it",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("sentAgentMessage",new JSONObject().put("peerName","Ops").put("text","on it")))));
        assertEquals("3 attachments",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("attachments",new JSONObject().put("count",3)))));
        assertEquals("Lunch? — Yes / No",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("widgetOptions",new JSONObject().put("prompt","Lunch?").put("options",new JSONArray().put("Yes").put("No"))))));
        assertEquals("",ServerTranscripts.previewOf(new JSONObject()));
        assertEquals("Done",ServerTranscripts.previewOf(new JSONObject().put("lastEntryPreview",new JSONObject().put("text",new JSONObject().put("text","Done"))).put("lastMessagePreview","older")));
    }
    // A turnFailed frame's fields as FEATURES-SPEC §1.4 lists them; values synthetic.
    static JSONObject turnFailed(Object code)throws Exception {
        return new JSONObject().put("agentId","A").put("turnId","turn-9").put("code",code).put("summary","Usage\nlimit reached")
            .put("failedAtMs","1800000000000").put("sessionId","main");
    }
    @Test public void aUsageLimitTurnFailureIsTheSameNoticeAsAGatewayTray()throws Exception {
        BotEvents.Event event=ServerTranscripts.turnFailed(turnFailed("GROK_BOT_TURN_FAILURE_CODE_USAGE_LIMIT"));
        assertEquals("tray",event.kind);assertEquals("A",event.botId);assertEquals("pushed",event.value.getString("type"));
        JSONObject tray=event.value.getJSONObject("tray");
        assertEquals("server-turn:turn-9",tray.getString("id"));assertEquals("A",tray.getString("agentId"));
        assertEquals(1_800_000_000_000L,tray.getLong("createdAt"));assertEquals("Usage limit reached",tray.getString("detail"));assertFalse(tray.has("title"));
        // The code's number, as a number or a string, means the same.
        assertNotNull(ServerTranscripts.turnFailed(turnFailed(3)));assertNotNull(ServerTranscripts.turnFailed(turnFailed("3")));
        assertNotNull(ServerTranscripts.turnFailed(turnFailed("USAGE_LIMIT")));
        assertEquals("no time is 0",0L,ServerTranscripts.turnFailed(turnFailed(3).put("failedAtMs","soon")).value.getJSONObject("tray").getLong("createdAt"));
    }
    @Test public void otherTurnFailuresAreNotUsageNotices()throws Exception {
        for(Object code:new Object[]{"GROK_BOT_TURN_FAILURE_CODE_TIMEOUT",2,"GROK_BOT_TURN_FAILURE_CODE_RATE_LIMIT",4,"GROK_BOT_TURN_FAILURE_CODE_UNPAID_INVOICE",6,7,0,"usage_limit","GROK_BOT_TURN_FAILURE_CODE_USAGE_LIMIT_X",JSONObject.NULL})
            assertNull(String.valueOf(code),ServerTranscripts.turnFailed(turnFailed(code)));
        assertNull("no turn",ServerTranscripts.turnFailed(turnFailed(3).put("turnId","")));
        assertNull("no Bot",ServerTranscripts.turnFailed(turnFailed(3).put("agentId",JSONObject.NULL)));
        char[] many=new char[512];java.util.Arrays.fill(many,'t');
        assertNull("an id past 512 characters",ServerTranscripts.turnFailed(turnFailed(3).put("turnId",new String(many))));
        assertNull(ServerTranscripts.turnFailed(null));
    }
    @Test public void aFollowedChatGetsOnlyNewAndChangedRows()throws Exception {
        ServerTranscripts.Scopes scopes=new ServerTranscripts.Scopes();
        JSONObject page=new JSONObject().put("generation","2").put("entries",new JSONArray().put(row(385,message("c","user","x"))).put(row(384,message("b","user","y"))));
        long[] marks=ServerTranscripts.marks(page);assertEquals(2,marks[0]);assertEquals(385,marks[1]);assertEquals(385,marks[2]);
        assertTrue("a new chat reconnects the stream",scopes.adopt("A",marks));assertFalse(scopes.adopt("A",marks));
        JSONObject cursor=scopes.cursors().getJSONObject(0);
        assertEquals("A",cursor.getString("agentId"));assertEquals(2,cursor.getLong("generation"));assertEquals("385",cursor.getString("afterUpdatedSeq"));
        assertNull("already shown",scopes.row("A",2,row(385,message("c","user","x"))));
        assertEquals("appended",scopes.row("A",2,row(386,message("d","assistant","hi"))));
        JSONObject edited=row(386,message("d","assistant","hi there")).put("updatedSeq","387");
        assertEquals("updated",scopes.row("A",2,edited));
        assertNull("an older generation is stale",scopes.row("A",1,row(390,message("e","user","z"))));
        assertNull("a chat not followed",scopes.row("B",2,row(1,message("f","user","z"))));
        assertEquals("387",scopes.cursors().getJSONObject(0).getString("afterUpdatedSeq"));
        for(int i=0;i<ServerTranscripts.Scopes.MAX+2;i++)scopes.adopt("bot"+i,marks);
        assertEquals(ServerTranscripts.Scopes.MAX,scopes.cursors().length());assertFalse("the least recently read goes first",scopes.has("A"));
    }
}
