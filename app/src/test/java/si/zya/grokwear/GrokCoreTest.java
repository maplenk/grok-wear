package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class GrokCoreTest {
    static final class Memory implements GrokCore.Store {
        JSONObject data=new JSONObject();boolean fail;
        public JSONObject read()throws Exception{return GrokCore.copy(data);}
        public void write(JSONObject v)throws Exception{if(fail)throw new Exception("disk failure");data=GrokCore.copy(v);}
    }
    static JSONObject config()throws Exception{return new JSONObject().put("gatewayUrl","https://box.cursorvm.com")
            .put("gatewayToken","synthetic-token").put("headers",new JSONObject().put("x-anyrun-network-token","synthetic-route"));}
    static final class Fixture {
        Memory mem=new Memory();List<String> calls=new ArrayList<>();JSONObject send=new JSONObject(),thread=new JSONObject(),tail=new JSONObject();
        Exception sendError;GrokCore c;
        Fixture()throws Exception {
            c=new GrokCore(mem,(cfg,method,body)->{calls.add(method);switch(method){
                case "listAgents":return new JSONObject().put("agents",new JSONArray().put(new JSONObject().put("id","main").put("name","Main Bot")));
                case "sendPrompt":if(sendError!=null)throw sendError;return send;
                case "getAgentThread":assertEquals("receipt",body.getString("rootId"));return thread;
                case "getAgentTranscriptTail":return tail;
            }throw new AssertionError(method);});
            c.link(config());c.bots();c.select("main");calls.clear();send.put("messageId","receipt");
        }
    }
    static JSONObject entry(String id,String kind,String text)throws Exception{return new JSONObject().put("id",id).put("kind",kind).put("text",text);}
    static JSONObject transcript(JSONObject...entries)throws Exception{return new JSONObject().put("entries",new JSONArray(Arrays.asList(entries)));}

    @Test public void restrictCredentialDestinations()throws Exception {
        for(String url:Arrays.asList("http://box.cursorvm.com","https://cursorvm.com.evil.test","https://evil.test/cursor.com","https://user@box.cursorvm.com","https://box.cursorvm.com:444","https://box.cursorvm.com/?token=x","https://box.cursorvm.com/#frag","https://box.cursorvm.com/a/../b")){
            try{GrokCore.validateConfig(config().put("gatewayUrl",url));fail(url);}catch(Exception expected){}
        }
    }
    @Test public void prohibitHeaderInjection()throws Exception {
        for(String key:Arrays.asList("Authorization","Host","Cookie","Content-Length","Proxy-Authorization")){
            try{GrokCore.validateConfig(config().put("headers",new JSONObject().put(key,"x")));fail(key);}catch(Exception expected){}
        }
        try{GrokCore.validateConfig(config().put("headers",new JSONObject().put("x-route","a\r\nb")));fail();}catch(Exception expected){}
    }
    @Test public void sanitizeViewAndExplicitSelection()throws Exception {
        Fixture f=new Fixture();String s=f.c.view().toString();assertFalse(s.contains("synthetic-token"));assertFalse(s.contains("synthetic-route"));
        try{f.c.select("other");fail();}catch(Exception expected){}
        assertFalse(f.c.view().getBoolean("voice"));
    }
    @Test public void groupRecordsCannotBeSelectedAsMainBot()throws Exception {
        Memory m=new Memory();GrokCore c=new GrokCore(m,(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
                .put(new JSONObject().put("id","group").put("memberAgentIds",new JSONArray().put("member")))
                .put(new JSONObject().put("id","main").put("name","Main"))));
        c.link(config());assertEquals(1,c.bots().length());
        try{c.select("group");fail();}catch(GrokCore.Failure expected){}c.select("main");
    }
    @Test public void singleAgentResponseIsRecognized()throws Exception {
        GrokCore c=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agent",new JSONObject().put("agentId","main").put("name","Main")));
        c.link(config());assertEquals(1,c.bots().length());c.select("main");
    }
    @Test public void onlyKnownAvatarIdentifiersSurviveDiscoveryAndSelection()throws Exception {
        Memory memory=new Memory();
        GrokCore c=new GrokCore(memory,(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
                .put(new JSONObject().put("id","styled").put("name","Styled").put("avatarShape","cloud").put("avatarColor","orange").put("avatarUrl","https://example.invalid/private"))
                .put(new JSONObject().put("id","unknown").put("name","Unknown").put("avatarShape","https://example.invalid/path").put("avatarColor","#123456"))
                .put(new JSONObject().put("id","legacy").put("name","Legacy"))));
        c.link(config());JSONArray bots=c.bots();
        assertEquals("cloud",bots.getJSONObject(0).getString("avatarShape"));
        assertEquals("orange",bots.getJSONObject(0).getString("avatarColor"));
        assertFalse(bots.toString().contains("example.invalid"));
        for(int i=1;i<3;i++){assertFalse(bots.getJSONObject(i).has("avatarShape"));assertFalse(bots.getJSONObject(i).has("avatarColor"));}
        c.select("styled");assertEquals("orange",c.view().getJSONObject("bot").getString("avatarColor"));
        c.select("legacy");assertFalse(c.view().getJSONObject("bot").has("avatarColor"));
    }
    @Test public void runtimeSettingsPrimaryAndPinOrderAreExplicitAndTyped()throws Exception {
        JSONObject settings=new JSONObject().put("mainAgentId","actual-id").put("pinnedAgents",new JSONObject()
            .put("agentIds",new JSONArray().put("second").put("first").put("second")));
        JSONObject result=GrokCore.botPreferences(new JSONObject().put("settings",settings));
        assertEquals("actual-id",result.getString("mainAgentId"));assertEquals("[\"second\",\"first\"]",result.getJSONArray("pinnedAgentIds").toString());
        assertEquals(0,GrokCore.botPreferences(new JSONObject()).length());
        settings.put("mainAgentId","bad\nvalue").getJSONObject("pinnedAgents").put("agentIds",new JSONArray().put(1));
        assertEquals(0,GrokCore.botPreferences(new JSONObject().put("settings",settings)).length());
    }
    @Test public void optionalRosterMetadataStaysTypedBoundedAndNeverInfersPrimary()throws Exception {
        JSONObject known=new JSONObject().put("id","known").put("name","Main Bot")
            .put("hasUnread",true).put("unreadCount",3).put("isRunning",false).put("isRunningTurn",true)
            .put("lastActivityAt",1234).put("lastMessagePreview","Hello\n  world\t!")
            .put("avatarDataUrl","data:image/png;base64,AQID").put("isPrimary",true).put("pinnedAtMs",3);
        JSONObject invalid=new JSONObject().put("id","invalid").put("hasUnread","true")
            .put("isRunning",1).put("unreadCount",-1).put("lastViewedAt",1.5)
            .put("lastMessagePreview",new JSONObject().put("text","secret"))
            .put("avatarDataUrl","data:image/svg+xml;base64,AQID");
        GrokCore core=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
            .put(known).put(invalid).put(new JSONObject().put("id","missing"))));
        core.link(config());JSONArray rows=core.bots();JSONObject row=rows.getJSONObject(0);
        assertTrue(row.getBoolean("hasUnread"));assertEquals(3,row.getInt("unreadCount"));
        assertFalse(row.getBoolean("isRunning"));assertTrue(row.getBoolean("isRunningTurn"));
        assertEquals("Hello world !",row.getString("lastMessagePreview"));assertEquals(1234,row.getLong("lastActivityAt"));
        assertEquals("data:image/png;base64,AQID",row.getString("avatarDataUrl"));
        assertFalse(row.has("isPrimary"));assertFalse(row.has("pinnedAtMs"));
        for(int i=1;i<3;i++)for(String key:Arrays.asList("hasUnread","isRunning","unreadCount","lastViewedAt","lastMessagePreview","avatarDataUrl"))
            assertFalse(rows.getJSONObject(i).has(key));
        core.select("known");assertTrue(core.view().getJSONObject("bot").getBoolean("hasUnread"));
    }
    @Test public void verifiedOwnershipSessionAndNativeMissingDefaultSurviveDiscovery()throws Exception {
        GrokCore core=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
            .put(new JSONObject().put("id","owner").put("name","Owner"))
            .put(new JSONObject().put("id","teammate").put("name","Teammate").put("viewerIsOwner",false).put("viewerSessionId","dm:known"))
            .put(new JSONObject().put("id","bad-bool").put("viewerIsOwner","true"))
            .put(new JSONObject().put("id","bad-session").put("viewerIsOwner",false).put("viewerSessionId","bad\n\nvalue"))));
        core.link(config());JSONArray bots=core.bots();assertTrue(bots.getJSONObject(0).getBoolean("viewerIsOwner"));
        assertFalse(bots.getJSONObject(1).getBoolean("viewerIsOwner"));assertEquals("dm:known",bots.getJSONObject(1).getString("viewerSessionId"));
        assertTrue(bots.getJSONObject(2).getBoolean("viewerMetadataInvalid"));assertFalse(bots.getJSONObject(2).getBoolean("viewerIsOwner"));
        assertTrue(bots.getJSONObject(3).getBoolean("viewerMetadataInvalid"));assertFalse(bots.getJSONObject(3).has("viewerSessionId"));
        core.select("teammate");assertEquals("dm:known",core.view().getJSONObject("bot").getString("viewerSessionId"));
    }
    static JSONObject pinned(Object ids)throws Exception {
        JSONObject pins=new JSONObject();if(ids!=null)pins.put("agentIds",ids);
        return new JSONObject().put("settings",new JSONObject().put("mainAgentId","main").put("pinnedAgents",pins));
    }
    @Test public void pinningAppendsAndUnpinningRemovesWithoutTouchingOtherPins()throws Exception {
        JSONArray stored=new JSONArray().put("b").put("main").put("c");
        assertEquals("[\"b\",\"main\",\"c\",\"d\"]",GrokCore.withPin(pinned(stored),"d",true).toString());
        assertEquals("Pinning an already pinned Bot keeps the order","[\"b\",\"main\",\"c\"]",GrokCore.withPin(pinned(stored),"main",true).toString());
        assertEquals("[\"b\",\"c\"]",GrokCore.withPin(pinned(stored),"main",false).toString());
        assertEquals("[\"b\",\"main\",\"c\"]",GrokCore.withPin(pinned(stored),"x",false).toString());
        assertEquals("Connect JSON leaves out an empty list","[\"d\"]",GrokCore.withPin(pinned(null),"d",true).toString());
        assertEquals("[\"d\"]",GrokCore.withPin(new JSONObject(),"d",true).toString());
    }
    @Test public void unreadablePinListsAreNeverWrittenBack()throws Exception {
        assertNull(GrokCore.withPin(pinned(new JSONArray().put("b").put(3)),"d",true));
        assertNull(GrokCore.withPin(pinned(new JSONArray().put("b\nc")),"d",true));
        assertNull(GrokCore.withPin(pinned("b"),"d",true));
        assertNull(GrokCore.withPin(pinned(new JSONArray()),"",true));
    }
    @Test public void storedPinsComeFromTheServerReply()throws Exception {
        JSONArray sent=new JSONArray().put("b").put("d");
        JSONObject prefs=GrokCore.storedPins(pinned(new JSONArray().put("b")),new JSONObject().put("settings",new JSONObject().put("pinnedAgents",new JSONObject().put("agentIds",sent))),sent);
        assertEquals("[\"b\",\"d\"]",prefs.getJSONArray("pinnedAgentIds").toString());assertEquals("main",prefs.getString("mainAgentId"));
        JSONObject cleared=GrokCore.storedPins(pinned(new JSONArray().put("b")),new JSONObject().put("settings",new JSONObject().put("pinnedAgents",new JSONObject())),new JSONArray());
        assertEquals(0,cleared.getJSONArray("pinnedAgentIds").length());
        try{GrokCore.storedPins(pinned(null),new JSONObject().put("settings",new JSONObject()),sent);fail("A reply without the list was not stored");}
        catch(GrokCore.Failure expected){}
    }
    @Test public void botVoiceSettingsSurviveDiscoveryNormalizedLikeTheOfficialApps()throws Exception {
        GrokCore core=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
            .put(new JSONObject().put("id","custom").put("voiceId","ara").put("voiceSpeed",1.3).put("voiceLanguage","hi"))
            .put(new JSONObject().put("id","text-speed").put("voiceSpeed","0.6").put("voiceLanguage","auto"))
            .put(new JSONObject().put("id","bad").put("voiceId","bad voice\n").put("voiceSpeed",-2).put("voiceLanguage","not a language"))));
        core.link(config());JSONArray bots=core.bots();
        JSONObject custom=bots.getJSONObject(0);assertEquals("ara",custom.getString("voiceId"));assertEquals(1.25,custom.getDouble("voiceSpeed"),0);assertEquals("hi",custom.getString("voiceLanguage"));
        JSONObject text=bots.getJSONObject(1);assertEquals(.5,text.getDouble("voiceSpeed"),0);assertFalse(text.has("voiceLanguage"));
        JSONObject bad=bots.getJSONObject(2);assertFalse(bad.has("voiceId"));assertFalse(bad.has("voiceSpeed"));assertFalse(bad.has("voiceLanguage"));
    }
    @Test public void botRoleLineSurvivesDiscoverySingleLineAndBounded()throws Exception {
        String longTitle=new String(new char[81]).replace('\0','x');
        GrokCore core=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
            .put(new JSONObject().put("id","ops").put("title","Platform\n  ops"))
            .put(new JSONObject().put("id","long").put("title",longTitle))
            .put(new JSONObject().put("id","none"))));
        core.link(config());JSONArray bots=core.bots();
        assertEquals("Platform ops",bots.getJSONObject(0).getString("title"));
        assertFalse(bots.getJSONObject(1).has("title"));assertFalse(bots.getJSONObject(2).has("title"));
    }
    @Test public void harnessSurvivesDiscoveryOnlyWhenWellFormed()throws Exception {
        GrokCore core=new GrokCore(new Memory(),(cfg,method,body)->new JSONObject().put("agents",new JSONArray()
            .put(new JSONObject().put("id","temporal").put("harness","temporal"))
            .put(new JSONObject().put("id","bad").put("harness","Temporal\nx"))
            .put(new JSONObject().put("id","none"))));
        core.link(config());JSONArray bots=core.bots();
        assertEquals("temporal",bots.getJSONObject(0).getString("harness"));assertTrue(CallHandoff.applies(bots.getJSONObject(0)));
        assertFalse(bots.getJSONObject(1).has("harness"));assertFalse(bots.getJSONObject(2).has("harness"));
        core.select("temporal");assertEquals("temporal",core.view().getJSONObject("bot").getString("harness"));
    }
    @Test public void rosterRefreshUpdatesPreviouslySelectedMetadataAndRemovesMissingBot()throws Exception {
        Memory memory=new Memory();JSONObject[] reply={new JSONObject().put("agents",new JSONArray().put(new JSONObject().put("id","main").put("name","Initial")))};
        GrokCore core=new GrokCore(memory,(cfg,method,body)->reply[0]);core.link(config());core.bots();core.select("main");
        memory.data.getJSONObject("bot").remove("viewerIsOwner"); // old installed selection before ownership was retained
        reply[0]=new JSONObject().put("agents",new JSONArray().put(new JSONObject().put("id","main").put("name","Updated").put("viewerIsOwner",false).put("viewerSessionId","dm:updated")));
        core.bots();JSONObject selected=core.view().getJSONObject("bot");assertEquals("main",selected.getString("id"));assertEquals("Updated",selected.getString("name"));assertFalse(selected.getBoolean("viewerIsOwner"));assertEquals("dm:updated",selected.getString("viewerSessionId"));
        reply[0]=new JSONObject().put("agents",new JSONArray());core.bots();assertTrue(core.view().isNull("bot"));
    }
    @Test public void cacheBotAndPersistBeforeSending()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");assertEquals("prepared",f.c.job(id).getString("state"));assertTrue(f.calls.isEmpty());
        assertEquals("waiting",f.c.submit(id).getString("state"));assertEquals(Collections.singletonList("sendPrompt"),f.calls);
        f.c.submit(id);assertEquals(1,f.calls.size());
    }
    @Test public void diskFailurePreventsTransmission()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");f.mem.fail=true;
        try{f.c.submit(id);fail();}catch(Exception expected){}assertTrue(f.calls.isEmpty());
    }
    @Test public void crashDuringSubmitNeverResends()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");f.mem.data.getJSONArray("jobs").getJSONObject(0).put("state","submitting");
        f.c.submit(id);assertTrue(f.calls.isEmpty());f.c.poll(id);assertEquals("unknown",f.c.job(id).getString("state"));assertFalse(f.calls.contains("sendPrompt"));
    }
    @Test public void cancelledAdmissionNeverSendsOrRetries()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");
        assertEquals("failed",f.c.submit(id,()->false).getString("state"));
        f.c.submit(id,()->true);assertTrue(f.calls.isEmpty());
    }
    @Test public void admissionIsCheckedAfterDurableWrite()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");
        f.c.submit(id,()->{assertEquals("submitting",f.mem.data.optJSONArray("jobs").optJSONObject(0).optString("state"));return false;});
        assertTrue(f.calls.isEmpty());
    }
    @Test public void changedBotInvalidatesReview()throws Exception {
        Fixture f=new Fixture();String profile=f.c.view().getString("profile");
        f.mem.data.put("bot",new JSONObject().put("id","other").put("name","Other"));
        try{f.c.prepare("hello",profile,"main");fail();}catch(GrokCore.Failure expected){}
        assertTrue(f.calls.isEmpty());assertEquals(0,f.c.view().getJSONArray("jobs").length());
    }
    @Test public void relinkInvalidatesReviewEvenForSameBotId()throws Exception {
        Fixture f=new Fixture();String profile=f.c.view().getString("profile");
        f.c.unlink();f.c.link(config());f.c.bots();f.c.select("main");f.calls.clear();
        try{f.c.prepare("hello",profile,"main");fail();}catch(GrokCore.Failure expected){}
        assertTrue(f.calls.isEmpty());
    }
    @Test public void receiptMissingIsUnknown()throws Exception {
        Fixture f=new Fixture();f.send=new JSONObject();String id=f.c.prepare("hello");assertEquals("unknown",f.c.submit(id).getString("state"));
        f.c.submit(id);assertEquals(1,f.calls.size());
    }
    @Test public void timeoutAndRejectedAreDifferent()throws Exception {
        Fixture f=new Fixture();f.sendError=new java.io.IOException("network");String id=f.c.prepare("hello");assertEquals("unknown",f.c.submit(id).getString("state"));
        Fixture g=new Fixture();g.sendError=new GrokCore.Failure("Denied",true);assertEquals("failed",g.c.submit(g.c.prepare("hello")).getString("state"));
    }
    @Test public void pendingJobBlocksAnotherPrompt()throws Exception {
        Fixture f=new Fixture();f.c.submit(f.c.prepare("first"));try{f.c.prepare("second");fail();}catch(GrokCore.Failure expected){}assertEquals(1,f.calls.size());
    }
    @Test public void correlatedReplyNeverNewestMessageGuess()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");f.c.submit(id);
        f.thread=transcript(entry("other","send-message","wrong answer"));assertEquals("",f.c.poll(id).getString("reply"));
        f.thread=transcript(entry("reply","send-message","correct").put("rootId","receipt"));assertEquals("correct",f.c.poll(id).getString("reply"));
    }
    @Test public void threadAnchorRequiresExactPromptAndUser()throws Exception {
        JSONObject answer=entry("a","send-message","answer");
        assertEquals("",GrokCore.matchedReply(transcript(entry("receipt","prompt","different"),answer),"receipt","hello"));
        assertEquals("",GrokCore.matchedReply(transcript(entry("receipt","send-message","hello"),answer),"receipt","hello"));
        assertEquals("answer",GrokCore.matchedReply(transcript(entry("receipt","prompt","hello"),answer),"receipt","hello"));
    }
    @Test public void foreignRootsAndDuplicateEntriesExcluded()throws Exception {
        JSONObject root=entry("receipt","prompt","hello"),a=entry("a","send-message","one");
        assertEquals("one",GrokCore.matchedReply(transcript(root,a,a,entry("b","send-message","wrong").put("rootId","other")),"receipt","hello"));
    }
    @Test public void nestedActualReplyShape()throws Exception {
        JSONObject a=new JSONObject().put("id","reply").put("kind","send-message").put("replyToId","receipt")
                .put("message",new JSONObject().put("type","text").put("content","चार"));
        assertEquals("चार",GrokCore.matchedReply(new JSONObject().put("thread",transcript(a)),"receipt","hello"));
    }
    @Test public void unknownReconcilesOnlyExactNonceAndPrompt()throws Exception {
        Fixture f=new Fixture();f.send=new JSONObject();String id=f.c.prepare("hello");f.c.submit(id);String nonce=f.c.job(id).getString("nonce");
        f.tail=transcript(entry("receipt","prompt","hello").put("clientNonce","other"));f.c.poll(id);assertEquals("unknown",f.c.job(id).getString("state"));
        f.tail=transcript(entry("receipt","prompt","hello").put("clientNonce",nonce));f.c.poll(id);assertEquals("waiting",f.c.job(id).getString("state"));
        assertEquals(1,Collections.frequency(f.calls,"sendPrompt"));
    }
    @Test public void ambiguousNonceDoesNotSelectFirst()throws Exception {
        JSONObject j=new JSONObject().put("nonce","n").put("text","hello");
        assertEquals("",GrokCore.reconcile(transcript(entry("a","prompt","hello").put("clientNonce","n"),entry("b","prompt","hello").put("clientNonce","n")),j));
    }
    @Test public void unlinkSeparatesAccountsAndDeletesInbox()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("private old text");f.c.unlink();f.c.link(config());
        assertFalse(f.c.view().toString().contains("private old text"));assertNull(f.c.view().optJSONObject("bot"));
        try{f.c.submit(id);fail();}catch(Exception expected){}assertTrue(f.calls.isEmpty());
    }
    @Test public void replacingConnectionNeedsUnlink()throws Exception {
        Fixture f=new Fixture();try{f.c.link(config());fail();}catch(GrokCore.Failure expected){}assertEquals("Main Bot",f.c.view().getJSONObject("bot").getString("name"));
    }
    @Test public void oversizedReplyNotSilentlyTruncated()throws Exception {
        try{GrokCore.matchedReply(transcript(entry("a","send-message",String.join("",Collections.nCopies(33000,"x"))).put("rootId","receipt")),"receipt","hello");fail();}catch(GrokCore.Failure expected){}
    }
    @Test public void secretNetworkErrorsNeverReachUi()throws Exception {
        Fixture f=new Fixture();f.sendError=new Exception("synthetic-token");String id=f.c.prepare("hello");assertFalse(f.c.submit(id).toString().contains("synthetic-token"));
        assertFalse(GrokCore.safe(new Exception("sensitive")).contains("sensitive"));
    }
    @Test public void inboxDeleteDoesNotSendRemoteAction()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");f.c.delete(id);assertEquals(0,f.c.view().getJSONArray("jobs").length());assertTrue(f.calls.isEmpty());
    }
    @Test public void encryptedStoreDraftSurvivesCoreRecreationWithoutEnteringView()throws Exception {
        Fixture f=new Fixture();f.c.saveDraft("private draft",true);
        GrokCore recreated=new GrokCore(f.mem,f.c.transport);
        assertEquals("private draft",recreated.draft().getString("text"));
        assertTrue(recreated.draft().getBoolean("gateway"));
        assertFalse(recreated.view().toString().contains("private draft"));
        recreated.unlink();assertEquals("",recreated.draft().getString("text"));
    }
    @Test public void preparationAtomicallyClearsOnlyMatchingGatewayDraft()throws Exception {
        Fixture f=new Fixture();f.c.saveDraft("hello",true);f.c.prepare("hello");
        assertEquals("",f.c.draft().getString("text"));
        Fixture g=new Fixture();g.c.saveDraft("hello",false);g.c.prepare("hello");
        assertEquals("hello",g.c.draft().getString("text"));
    }
    @Test public void browserLinkIsIdempotentOnlyForSameAccountAndLogin()throws Exception {
        Memory m=new Memory();GrokCore c=new GrokCore(m,(a,b,d)->new JSONObject());
        c.linkBrowser(config(),"account","login");String profile=c.view().getString("profile");
        c.saveDraft("keep",true);c.linkBrowser(config(),"account","login");
        assertEquals(profile,c.view().getString("profile"));assertEquals("keep",c.draft().getString("text"));
        try{c.linkBrowser(config(),"other","login");fail();}catch(GrokCore.Failure expected){}
        try{c.linkBrowser(config(),"account","new-login");fail();}catch(GrokCore.Failure expected){}
    }
    @Test public void firstLinkPreservesUnsentDraftButRelinkDoesNotCarryOldAccountData()throws Exception {
        for(boolean browser:new boolean[]{true,false}) {
            GrokCore c=new GrokCore(new Memory(),(a,b,d)->new JSONObject());
            c.saveDraft("review me",true);
            if(browser)c.linkBrowser(config(),"account","login");else c.link(config());
            assertEquals("review me",c.draft().getString("text"));
            c.unlink();c.link(config());assertEquals("",c.draft().getString("text"));
        }
    }
    @Test public void cancellationDuringCredentialRenewalPreventsSend()throws Exception {
        Fixture f=new Fixture();boolean[] admitted={true};
        GrokCore c=new GrokCore(f.mem,f.c.transport,root->{admitted[0]=false;return root.getJSONObject("config");});
        String id=c.prepare("hello");assertEquals("failed",c.submit(id,()->admitted[0]).getString("state"));
        assertTrue(f.calls.isEmpty());
    }
    @Test public void refusedRenewalNeverUsesStaleGatewayOrRepeatsPrompt()throws Exception {
        Fixture f=new Fixture();GrokCore c=new GrokCore(f.mem,f.c.transport,root->{throw new GrokCore.Failure("Relink",true);});
        String id=c.prepare("hello");assertEquals("failed",c.submit(id).getString("state"));c.submit(id);
        assertTrue(f.calls.isEmpty());
    }
    @Test public void refusedPollStopsBackgroundObservationWithoutChangingDelivery()throws Exception {
        Fixture f=new Fixture();String id=f.c.prepare("hello");f.c.submit(id);f.calls.clear();
        GrokCore c=new GrokCore(f.mem,f.c.transport,root->{throw new GrokCore.Failure("Relink",true);});
        JSONObject result=c.poll(id);assertEquals("waiting",result.getString("state"));
        assertTrue(result.getBoolean("polling_finished"));assertTrue(result.getBoolean("poll_error"));assertEquals("Relink",result.getString("notice"));assertTrue(f.calls.isEmpty());
    }
    @Test public void malformedRefreshedConfigStopsBeforeGatewayAndAutomaticPolling()throws Exception {
        Fixture f=new Fixture();String waiting=f.c.prepare("first");f.c.submit(waiting);f.calls.clear();
        GrokCore c=new GrokCore(f.mem,f.c.transport,root->config().put("gatewayUrl","https://bad host.cursorvm.com"));
        assertTrue(c.poll(waiting).getBoolean("polling_finished"));assertTrue(f.calls.isEmpty());
        Fixture g=new Fixture();GrokCore submit=new GrokCore(g.mem,g.c.transport,root->{throw new java.io.IOException("private-details");});
        JSONObject failed=submit.submit(submit.prepare("hello"));assertEquals("failed",failed.getString("state"));
        assertFalse(failed.toString().contains("private-details"));assertTrue(g.calls.isEmpty());
    }
}
