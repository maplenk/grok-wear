package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryCoordinatorTest {
    static final long NOW=1800000000000L;
    static JSONObject binding(String bot)throws Exception{return new JSONObject().put("accountKey","account-hash").put("authGeneration","login-one").put("agentId",bot).put("sessionId","");}
    static class Memory implements HistoryCoordinator.Store {
        JSONObject value=new JSONObject();int writes;boolean broken;
        public JSONObject read()throws Exception{return copy(value);}
        public void write(JSONObject root)throws Exception{if(broken)throw new Exception("synthetic private error");value=copy(root);writes++;}
    }
    static class Fixture {
        final Memory memory=new Memory();JSONObject current=binding("bot-a"),page=new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",JSONObject.NULL),remoteRecord;
        final List<String> calls=new ArrayList<>();final List<JSONObject> requests=new ArrayList<>(),bindings=new ArrayList<>();final java.util.ArrayDeque<JSONObject> scriptedPages=new java.util.ArrayDeque<>();long now=NOW,advancePerTail;boolean offline,loseWrite;Runnable duringRead;
        final HistoryCoordinator core;
        Fixture()throws Exception {core=new HistoryCoordinator(memory,(method,body,b)->{
            synchronized(calls){calls.add(method);requests.add(copy(body));bindings.add(copy(b));}
            if(method.equals("getAgentTranscriptTail")){if(duringRead!=null)duringRead.run();if(offline)throw new Exception("synthetic network");now+=advancePerTail;return copy(scriptedPages.isEmpty()?page:scriptedPages.removeFirst());}
            if(method.equals("getVoiceCall")){if(offline)throw new Exception("synthetic network");return remoteRecord==null?null:copy(remoteRecord);}
            if(method.equals("recordVoiceCall")){
                assertEquals("attempting",memory.value.getJSONObject("outbox").getJSONObject(body.getJSONObject("record").getString("callId")).getString("state"));
                if(loseWrite)throw new Exception("response lost");return null;
            }
            throw new AssertionError(method);
        },(b,selected)->{
            if(!current.getString("accountKey").equals(b.optString("accountKey"))||!current.getString("authGeneration").equals(b.optString("authGeneration"))||selected&&(!current.getString("agentId").equals(b.optString("agentId"))||!current.getString("sessionId").equals(b.optString("sessionId"))))throw new GrokCore.Failure("Account changed",true);
        },()->now);}
        HistoryCoordinator.Context context()throws Exception{return new HistoryCoordinator.Context(current,new JSONArray());}
        int count(String method){return Collections.frequency(calls,method);}
    }
    static JSONObject copy(JSONObject o)throws Exception{return new JSONObject(o.toString());}
    static JSONObject text(String id,String role,String content,long at)throws Exception{return new JSONObject().put("id",id).put("kind","message").put("role",role).put("content",content).put("timestampMs",at);}
    static JSONObject record(String call,String bot)throws Exception{return new JSONObject().put("callId",call).put("agentId",bot).put("model","").put("startedAtMs",NOW-2000).put("endedAtMs",NOW).put("durationMs",2000).put("ending","hung-up").put("turns",new JSONArray().put(new JSONObject().put("id","u").put("speaker","user").put("text","Question").put("atMs",NOW-1500)).put(new JSONObject().put("id","a").put("speaker","assistant").put("text","Answer").put("atMs",NOW-500))).put("nudges",new JSONArray()).put("harnessMayCollect",false);}
    interface Throwing{void run()throws Exception;}
    static void rejected(Throwing action)throws Exception{try{action.run();fail("Expected safe refusal");}catch(GrokCore.Failure expected){assertFalse(expected.getMessage().contains("synthetic private"));}}

    @Test public void normalizesOnlyFinalUnattributedVisibleText()throws Exception {
        Fixture f=new Fixture();JSONArray raw=new JSONArray().put(text("u","user","Hi",1)).put(new JSONObject().put("id","bot").put("kind","send-message").put("message",new JSONObject().put("type","text").put("content","Hello")).put("timestampMs",2));
        raw.put(text("hidden","assistant","hidden",3).put("suppressed",true));raw.put(text("foreign","assistant","other Bot",4).put("fromAgent",new JSONObject().put("id","other")));
        raw.put(text("channel","user","slack",5).put("channel","slack:channel"));raw.put(text("stream","assistant","partial",6).put("isStreaming",true));
        raw.put(new JSONObject().put("id","permission").put("kind","send-message").put("message",new JSONObject().put("type","secret-request").put("secret","do-not-render")));
        raw.put(new JSONObject().put("id","suppressed-card").put("kind","send-message").put("suppressed",true).put("message",new JSONObject().put("type","text").put("content","hidden")));
        f.page.put("entries",raw);JSONArray result=f.core.page(f.current,null,20).getJSONArray("entries");assertEquals(2,result.length());assertEquals("user",result.getJSONObject(0).getString("role"));assertEquals("Hello",result.getJSONObject(1).getString("text"));assertFalse(result.toString().contains("do-not-render"));
    }
    @Test public void paginationUsesReturnedCursorAndKnownSessionExactly()throws Exception {
        Fixture f=new Fixture();f.current.put("sessionId","dm:known");f.page.put("nextBeforeSeq",123).put("entries",new JSONArray().put(text("r","user","test",1).put("seq",124)));
        JSONObject first=f.core.page(f.current,null,20);assertTrue(first.getBoolean("hasMore"));assertEquals(123,first.getLong("nextBeforeSeq"));
        f.page.put("nextBeforeSeq",JSONObject.NULL);f.core.page(f.current,123L,20);JSONObject request=f.requests.get(1);assertEquals(123,request.getLong("beforeSeq"));assertEquals("dm:known",request.getString("sessionId"));
        f.page.put("nextBeforeSeq",123);rejected(()->f.core.page(f.current,123L,10));
    }
    @Test public void expandsLatestTwoVoiceCardsRatherThanOldestTwo()throws Exception {
        Fixture f=new Fixture();JSONArray cards=new JSONArray();for(int i=0;i<3;i++)cards.put(new JSONObject().put("id","row"+i).put("kind","voice-call").put("timestampMs",i+1).put("call",new JSONObject().put("callId","call"+i).put("durationMs",1000)));
        f.page.put("entries",cards);f.core.page(f.current,null,20);List<String> ids=new ArrayList<>();for(int i=0;i<f.calls.size();i++)if(f.calls.get(i).equals("getVoiceCall"))ids.add(f.requests.get(i).getString("callId"));assertEquals(2,ids.size());assertEquals(new HashSet<>(Arrays.asList("call1","call2")),new HashSet<>(ids));
    }
    @Test public void latestVoiceDetailsOverlapAndKeepTailOrder()throws Exception {
        Memory memory=new Memory();JSONObject owner=binding("bot-a");
        java.util.concurrent.CountDownLatch bothStarted=new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger tails=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject tail=new JSONObject().put("entries",new JSONArray()
            .put(new JSONObject().put("id","first").put("kind","voice-call").put("seq",1).put("call",new JSONObject().put("callId","first-call")))
            .put(new JSONObject().put("id","second").put("kind","voice-call").put("seq",2).put("call",new JSONObject().put("callId","second-call"))))
            .put("nextBeforeSeq",JSONObject.NULL);
        HistoryCoordinator core=new HistoryCoordinator(memory,(method,body,b)->{
            if(method.equals("getAgentTranscriptTail")){tails.incrementAndGet();return copy(tail);}
            assertEquals("getVoiceCall",method);assertEquals("bot-a",body.getString("id"));
            assertEquals(owner.getString("authGeneration"),b.getString("authGeneration"));assertEquals(NOW+45000,b.getLong("deadlineMs"));
            bothStarted.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new Exception("details did not overlap");
            JSONObject saved=record(body.getString("callId"),"bot-a");
            saved.getJSONArray("turns").getJSONObject(0).put("text",body.getString("callId"));
            if(body.getString("callId").equals("second-call"))for(int i=0;i<2;i++){JSONObject turn=saved.getJSONArray("turns").getJSONObject(i);turn.put("atMs",turn.getLong("atMs")+3000);}
            return saved;
        },(b,selected)->{},()->NOW);
        java.util.concurrent.FutureTask<JSONArray> result=new java.util.concurrent.FutureTask<>(()->core.prepare(owner).turns());
        Thread caller=new Thread(result,"history-prepare-test");caller.start();
        try {
            assertTrue("Both independent voice reads must start before either is released",bothStarted.await(2,java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();JSONArray turns=result.get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(4,turns.length());assertEquals("first-call",turns.getJSONObject(0).getString("text"));
            assertEquals("second-call",turns.getJSONObject(2).getString("text"));assertEquals(1,tails.get());
        }finally{release.countDown();caller.join(4000);}
    }
    @Test public void parallelDetailFailureKeepsTheOtherCallAndNextPrepareReadsFreshAgain()throws Exception {
        Memory memory=new Memory();JSONObject owner=binding("bot-a");java.util.concurrent.atomic.AtomicInteger tails=new java.util.concurrent.atomic.AtomicInteger(),details=new java.util.concurrent.atomic.AtomicInteger();
        JSONArray cards=new JSONArray();for(String call:new String[]{"ok","missing"})cards.put(new JSONObject().put("id",call).put("kind","voice-call").put("call",new JSONObject().put("callId",call)));
        HistoryCoordinator core=new HistoryCoordinator(memory,(method,body,b)->{
            if(method.equals("getAgentTranscriptTail")){tails.incrementAndGet();return new JSONObject().put("entries",cards).put("nextBeforeSeq",JSONObject.NULL);}
            details.incrementAndGet();if(body.getString("callId").equals("missing"))throw new Exception("unavailable detail");return record("ok","bot-a");
        },(b,selected)->{},()->NOW);
        // The unavailable call stays a summary row; the call that loaded still seeds the voice context.
        JSONArray turns=core.prepare(owner).turns();assertTrue(turns.length()>0);assertEquals("Question",turns.getJSONObject(0).getString("text"));
        core.prepare(owner);assertEquals(2,tails.get());assertEquals(4,details.get());
    }
    @Test public void accountChangeWhileBothDetailsRunCannotPublishContextOrCache()throws Exception {
        Memory memory=new Memory();JSONObject owner=binding("bot-a");java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch bothStarted=new java.util.concurrent.CountDownLatch(2),release=new java.util.concurrent.CountDownLatch(1);
        JSONArray cards=new JSONArray();for(String call:new String[]{"first","second"})cards.put(new JSONObject().put("id",call).put("kind","voice-call").put("call",new JSONObject().put("callId",call)));
        HistoryCoordinator core=new HistoryCoordinator(memory,(method,body,b)->{
            if(method.equals("getAgentTranscriptTail"))return new JSONObject().put("entries",cards).put("nextBeforeSeq",JSONObject.NULL);
            bothStarted.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new Exception("details not released");return record(body.getString("callId"),"bot-a");
        },(b,selected)->{if(changed.get())throw new GrokCore.Failure("Account changed",true);},()->NOW);
        java.util.concurrent.FutureTask<Boolean> result=new java.util.concurrent.FutureTask<>(()->{rejected(()->core.prepare(owner));return true;});
        Thread caller=new Thread(result,"history-binding-change-test");caller.start();
        try{assertTrue(bothStarted.await(2,java.util.concurrent.TimeUnit.SECONDS));changed.set(true);release.countDown();assertTrue(result.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,memory.value.getJSONObject("pages").length());}
        finally{release.countDown();caller.join(4000);}
    }
    @Test public void recentSavedPageCanStartVoiceOfflineButAnOldOneCannot()throws Exception {
        Fixture f=new Fixture();f.page.put("entries",new JSONArray().put(text("u","user","old",1)));f.core.page(f.current,null,50);f.offline=true;
        JSONObject cached=f.core.page(f.current,null,50);assertTrue(cached.getBoolean("stale"));assertEquals("offline",cached.getString("syncStatus"));
        assertEquals("old",f.core.prepare(f.current).turns().getJSONObject(0).getString("text"));
        f.now+=HistoryCoordinator.SAVED_CONTEXT_MS+1;rejected(()->f.core.prepare(f.current));
    }
    @Test public void olderPageFailureKeepsTheFreshNewestPage()throws Exception {
        Fixture f=new Fixture();f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("q","user","newest question",5))).put("nextBeforeSeq",100));
        f.duringRead=()->{if(f.count("getAgentTranscriptTail")>=2)f.offline=true;};
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals(1,turns.length());assertEquals("newest question",turns.getJSONObject(0).getString("text"));assertEquals(2,f.count("getAgentTranscriptTail"));
    }
    @Test public void localStageIsDurableNoNetworkAndImmediateNextCallIncludesIt()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();JSONObject record=record("call-stage","bot-a");
        assertEquals("pending",f.core.stage(context,record).getString("syncStatus"));assertTrue(f.calls.isEmpty());assertEquals(1,f.memory.value.getJSONObject("outbox").length());
        f.core.stage(context,record);assertEquals(1,f.memory.value.getJSONObject("outbox").length());
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals(2,turns.length());assertEquals("Question",turns.getJSONObject(0).getString("text"));assertEquals("Answer",turns.getJSONObject(1).getString("text"));
        turns.getJSONObject(0).put("text","changed");assertEquals("Question",f.core.prepare(f.current).turns().getJSONObject(0).getString("text"));
    }
    @Test public void prepareDoesNotWaitForPendingUploadAndKeepsLocalTurns()throws Exception {
        Memory memory=new Memory();JSONObject owner=binding("bot-a");java.util.concurrent.CountDownLatch releaseUpload=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger uploads=new java.util.concurrent.atomic.AtomicInteger(),tails=new java.util.concurrent.atomic.AtomicInteger();
        HistoryCoordinator core=new HistoryCoordinator(memory,(method,body,b)->{
            if(method.equals("recordVoiceCall")){uploads.incrementAndGet();releaseUpload.await(3,java.util.concurrent.TimeUnit.SECONDS);return null;}
            assertEquals("getAgentTranscriptTail",method);tails.incrementAndGet();return new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",JSONObject.NULL);
        },(b,selected)->{},()->NOW);
        core.stage(new HistoryCoordinator.Context(owner,new JSONArray()),record("pending-local","bot-a"));
        java.util.concurrent.FutureTask<JSONArray> result=new java.util.concurrent.FutureTask<>(()->core.prepare(owner).turns());Thread caller=new Thread(result,"history-no-upload-test");caller.start();
        try{JSONArray turns=result.get(1,java.util.concurrent.TimeUnit.SECONDS);assertEquals(2,turns.length());assertEquals("Question",turns.getJSONObject(0).getString("text"));assertEquals("Answer",turns.getJSONObject(1).getString("text"));assertEquals(0,uploads.get());assertEquals(1,tails.get());assertEquals("staged",memory.value.getJSONObject("outbox").getJSONObject("pending-local").getString("state"));}
        finally{releaseUpload.countDown();caller.join(4000);}
    }
    @Test public void successfulWriteAndRemoteEchoDoNotDuplicateOrResend()throws Exception {
        Fixture f=new Fixture();JSONObject record=record("call-saved","bot-a");HistoryCoordinator.Context context=f.context();assertEquals("synced",f.core.record(context,record).getString("syncStatus"));f.core.record(context,record);assertEquals(1,f.count("recordVoiceCall"));
        f.remoteRecord=record;f.page.put("entries",new JSONArray().put(new JSONObject().put("id","voice-row").put("kind","voice-call").put("call",new JSONObject().put("callId","call-saved"))));
        JSONObject page=f.core.page(f.current,null,20);assertEquals(2,page.getJSONArray("entries").length());assertEquals(0,page.getInt("pendingCount"));
    }
    @Test public void lostWriteResponseReconcilesReadOnlyAndNullNeverTriggersReplay()throws Exception {
        Fixture f=new Fixture();JSONObject record=record("call-uncertain","bot-a");HistoryCoordinator.Context context=f.context();f.loseWrite=true;
        assertEquals("uncertain",f.core.record(context,record).getString("syncStatus"));assertEquals("uncertain",f.core.record(context,record).getString("syncStatus"));assertEquals(1,f.count("recordVoiceCall"));assertEquals(1,f.count("getVoiceCall"));
        f.remoteRecord=record;assertEquals("synced",f.core.record(context,record).getString("syncStatus"));assertEquals(1,f.count("recordVoiceCall"));
    }
    @Test public void durableAttemptingAfterRestartIsNeverReplayed()throws Exception {
        Fixture f=new Fixture();JSONObject record=record("call-death","bot-a");HistoryCoordinator.Context context=f.context();f.core.stage(context,record);f.memory.value.getJSONObject("outbox").getJSONObject("call-death").put("state","attempting");
        assertEquals("uncertain",f.core.record(context,record).getString("syncStatus"));assertEquals(0,f.count("recordVoiceCall"));assertEquals(1,f.count("getVoiceCall"));
    }
    @Test public void pendingCountsAndFollowupTextAreScopedToBotAndSession()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context a=f.context();f.core.stage(a,record("call-a","bot-a"));f.current=binding("bot-b");HistoryCoordinator.Context b=f.context();f.core.stage(b,record("call-b","bot-b"));
        f.loseWrite=true;JSONObject page=f.core.page(f.current,null,20);assertEquals(1,page.getInt("pendingCount"));assertTrue(page.getJSONArray("entries").toString().contains("call-b/"));assertFalse(page.getJSONArray("entries").toString().contains("call-a/"));
        f.loseWrite=false;assertEquals("synced",f.core.record(a,record("call-a","bot-a")).getString("syncStatus"));assertEquals("bot-a",f.requests.get(f.requests.size()-1).getJSONObject("record").getString("agentId"));
        f.current.put("sessionId","another-session");assertEquals(0,f.core.page(f.current,null,20).getInt("pendingCount"));
    }
    @Test public void changedAccountCannotApplyReadOrResurrectClearedScope()throws Exception {
        Fixture f=new Fixture();JSONObject original=copy(f.current);f.duringRead=()->{try{f.current.put("authGeneration","login-two");f.core.clear();}catch(Exception e){throw new AssertionError(e);}};
        rejected(()->f.core.page(original,null,20));assertFalse(f.memory.value.has("binding"));
        rejected(()->f.core.stage(new HistoryCoordinator.Context(original,new JSONArray()),record("stale","bot-a")));assertFalse(f.memory.value.has("binding"));
    }
    @Test public void boundedConfirmedRetentionNeverDropsUncertainCalls()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();f.loseWrite=true;f.core.record(context,record("keep-uncertain","bot-a"));f.loseWrite=false;
        for(int i=0;i<12;i++)f.core.record(context,record("confirmed-"+i,"bot-a"));JSONObject out=f.memory.value.getJSONObject("outbox");assertTrue(out.has("keep-uncertain"));assertEquals(9,out.length());
    }
    @Test public void conflictingFinalSnapshotIsRefusedBeforeAnotherWrite()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();JSONObject record=record("immutable","bot-a");f.core.stage(context,record);record.getJSONArray("turns").getJSONObject(0).put("text","different");rejected(()->f.core.record(context,record));assertEquals(0,f.count("recordVoiceCall"));
    }
    @Test public void seedDropsLeadingAssistantAndNeverSeedsSystemNotice()throws Exception {
        Fixture f=new Fixture();f.page.put("entries",new JSONArray().put(text("old-answer","assistant","cutoff",1)).put(text("question","user","question",2)).put(new JSONObject().put("id","notice").put("kind","notice").put("text","system").put("timestampMs",3)).put(text("answer","assistant","answer",4)));
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals(2,turns.length());assertEquals("user",turns.getJSONObject(0).getString("speaker"));
    }
    @Test public void explicitRecoverySendsOnlyKnownUndispatchedStageAndReconcilesUncertain()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();JSONObject record=record("recover-stage","bot-a");f.core.stage(context,record);
        f.core.recoverPending(f.current);assertEquals(1,f.count("recordVoiceCall"));assertEquals("confirmed",f.memory.value.getJSONObject("outbox").getJSONObject("recover-stage").getString("state"));
        f.loseWrite=true;f.core.record(context,record("recover-uncertain","bot-a"));int writes=f.count("recordVoiceCall");f.core.recoverPending(f.current);assertEquals(writes,f.count("recordVoiceCall"));assertTrue(f.count("getVoiceCall")>0);
    }
    @Test public void authorIdentityMatchesCurrentJwtSubjectHashInsteadOfFromUserBlanketRule()throws Exception {
        Fixture f=new Fixture();java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");StringBuilder key=new StringBuilder();for(byte b:sha.digest("current-user".getBytes(java.nio.charset.StandardCharsets.UTF_8)))key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));f.current.put("authorKey",key.toString());
        JSONObject own=text("own","user","own message",1).put("author",new JSONObject().put("kind","cursor_user").put("id","current-user")).put("fromUser",new JSONObject().put("name","current"));
        JSONObject other=text("other","user","someone else",2).put("author",new JSONObject().put("kind","cursor_user").put("id","different-user"));
        f.page.put("entries",new JSONArray().put(own).put(other));JSONArray rows=f.core.page(f.current,null,20).getJSONArray("entries");assertEquals(1,rows.length());assertEquals("own",rows.getJSONObject(0).getString("id"));
    }
    @Test public void missingVoiceRecordKeepsItsSummaryAndStillStartsVoice()throws Exception {
        Fixture f=new Fixture();f.page.put("entries",new JSONArray().put(text("q","user","question",1)).put(new JSONObject().put("id","voice-row").put("kind","voice-call").put("call",new JSONObject().put("callId","missing-call").put("durationMs",78000))));
        JSONObject page=f.core.page(f.current,null,50);assertEquals("partial",page.getString("syncStatus"));
        JSONArray rows=page.getJSONArray("entries");String summary="";for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals("voice-row"))summary=rows.getJSONObject(i).getString("text");
        assertEquals("Voice chat · 1:18",summary);
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals(1,turns.length());assertEquals("question",turns.getJSONObject(0).getString("text"));
    }
    @Test public void sparseToolTailFetchesOlderEligibleTurnsWithOneSharedDeadline()throws Exception {
        Fixture f=new Fixture();JSONArray tools=new JSONArray();for(int i=0;i<50;i++)tools.put(new JSONObject().put("id","tool-"+i).put("kind","tool-call").put("name","read"));
        f.scriptedPages.add(new JSONObject().put("entries",tools).put("nextBeforeSeq",100));
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("older-user","user","Earlier question",1)).put(text("older-answer","assistant","Earlier answer",2))).put("nextBeforeSeq",JSONObject.NULL));
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals(2,turns.length());assertEquals("Earlier question",turns.getJSONObject(0).getString("text"));assertEquals(2,f.count("getAgentTranscriptTail"));assertEquals(100,f.requests.get(1).getLong("beforeSeq"));assertEquals(f.bindings.get(0).getLong("deadlineMs"),f.bindings.get(1).getLong("deadlineMs"));
    }
    @Test public void sparseHistoryBeyondThreePagesRefusesRatherThanClaimingEmptyFreshContext()throws Exception {
        Fixture f=new Fixture();for(int i=0;i<3;i++)f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(new JSONObject().put("id","tool-"+i).put("kind","tool-call").put("name","read"))).put("nextBeforeSeq",300-i*100));
        rejected(()->f.core.prepare(f.current));assertEquals(3,f.count("getAgentTranscriptTail"));
    }
    @Test public void preparePaginationDoesNotResetSharedFortyFiveSecondBudget()throws Exception {
        Fixture f=new Fixture();f.advancePerTail=24000;f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",100));f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("u","user","too late",1))).put("nextBeforeSeq",JSONObject.NULL));
        rejected(()->f.core.prepare(f.current));assertEquals(2,f.count("getAgentTranscriptTail"));assertEquals(f.bindings.get(0).getLong("deadlineMs"),f.bindings.get(1).getLong("deadlineMs"));
    }
    @Test public void olderPagesNeverRepeatOutboxRecovery()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();f.loseWrite=true;f.core.record(context,record("uncertain-once","bot-a"));int before=f.count("getVoiceCall");
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",100));f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",JSONObject.NULL));f.core.prepare(f.current);
        assertEquals(before,f.count("getVoiceCall"));assertEquals(1,f.count("recordVoiceCall"));
    }
    @Test public void repeatedVoiceCardAcrossPagesLoadsDetailsOncePerPrepareOnly()throws Exception {
        Fixture f=new Fixture();f.remoteRecord=record("repeated","bot-a");JSONObject card=new JSONObject().put("id","repeated-row").put("kind","voice-call").put("call",new JSONObject().put("callId","repeated"));
        for(int run=0;run<2;run++){
            f.remoteRecord.getJSONArray("turns").getJSONObject(0).put("text","Fresh question "+run);
            f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(card)).put("nextBeforeSeq",200));
            f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(card)).put("nextBeforeSeq",100));
            f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",JSONObject.NULL));
            JSONArray turns=f.core.prepare(f.current).turns();assertEquals(2,turns.length());assertEquals("Fresh question "+run,turns.getJSONObject(0).getString("text"));
            assertEquals(run+1,f.count("getVoiceCall"));assertEquals((run+1)*3,f.count("getAgentTranscriptTail"));
        }
    }
    @Test public void pageDoesNotDispatchPendingUploadAndRecoveryProcessesOnlyOneWithOwnBudget()throws Exception {
        Fixture f=new Fixture();HistoryCoordinator.Context context=f.context();f.core.stage(context,record("pending-one","bot-a"));f.core.stage(context,record("pending-two","bot-a"));
        JSONObject page=f.core.page(f.current,null,20);assertEquals(4,page.getJSONArray("entries").length());assertEquals(0,f.count("recordVoiceCall"));
        f.core.recoverPending(f.current);assertEquals(1,f.count("recordVoiceCall"));assertEquals(NOW+30000,f.bindings.get(f.bindings.size()-1).getLong("deadlineMs"));
    }
    @Test public void prepareBatchesPagesIntoOneWriteAndKeepsConcurrentOutboxStage()throws Exception {
        Fixture f=new Fixture();f.core.stage(f.context(),record("first-local","bot-a"));int writes=f.memory.writes;
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",100));
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("remote","user","fresh remote",NOW-3000))).put("nextBeforeSeq",JSONObject.NULL));
        f.duringRead=()->{if(f.count("getAgentTranscriptTail")==2)try{f.core.stage(f.context(),record("concurrent-local","bot-a"));}catch(Exception e){throw new AssertionError(e);}};
        JSONArray turns=f.core.prepare(f.current).turns();assertEquals("fresh remote",turns.getJSONObject(0).getString("text"));
        assertEquals("one durable concurrent stage plus one batched cache write",writes+2,f.memory.writes);assertEquals(2,f.memory.value.getJSONObject("outbox").length());assertEquals(2,f.memory.value.getJSONObject("pages").length());
        Iterator<String> keys=f.memory.value.getJSONObject("pages").keys();while(keys.hasNext()){JSONArray cached=f.memory.value.getJSONObject("pages").getJSONObject(keys.next()).getJSONArray("entries");assertFalse(cached.toString().contains("first-local/"));assertFalse(cached.toString().contains("concurrent-local/"));}
    }
    @Test public void prepareCannotCommitAfterStoreRevisionChangesBetweenPages()throws Exception {
        Fixture f=new Fixture();f.core.stage(f.context(),record("local","bot-a"));f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",100));
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("remote","user","fresh",NOW-3000))).put("nextBeforeSeq",JSONObject.NULL));
        f.duringRead=()->{if(f.count("getAgentTranscriptTail")==2)try{f.core.clear();}catch(Exception e){throw new AssertionError(e);}};
        rejected(()->f.core.prepare(f.current));assertFalse(f.memory.value.has("binding"));assertFalse(f.memory.value.has("pages"));
    }
    @Test public void batchedPrepareDoesNotOverwriteNewerConcurrentPage()throws Exception {
        Fixture f=new Fixture();f.core.stage(f.context(),record("local","bot-a"));String key="bot-a\n\ntail/50";
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray()).put("nextBeforeSeq",100));
        f.scriptedPages.add(new JSONObject().put("entries",new JSONArray().put(text("remote","user","fresh",NOW-3000))).put("nextBeforeSeq",JSONObject.NULL));
        f.duringRead=()->{if(f.count("getAgentTranscriptTail")==2)try{JSONObject root=f.memory.read();root.getJSONObject("pages").put(key,new JSONObject().put("fetchedAtMs",NOW+1).put("entries",new JSONArray().put(new JSONObject().put("text","newer cached page"))));f.memory.write(root);}catch(Exception e){throw new AssertionError(e);}};
        f.core.prepare(f.current);assertEquals("newer cached page",f.memory.value.getJSONObject("pages").getJSONObject(key).getJSONArray("entries").getJSONObject(0).getString("text"));
    }
    @Test public void failedFinalCacheWriteStillRefusesPreparedContext()throws Exception {
        Fixture f=new Fixture();f.core.stage(f.context(),record("local","bot-a"));
        f.page.put("entries",new JSONArray().put(text("remote","user","fresh",NOW-3000)));
        f.duringRead=()->f.memory.broken=true;rejected(()->f.core.prepare(f.current));assertEquals(0,f.memory.value.getJSONObject("pages").length());
    }
    @Test public void nativeFinalTurnBoundaryAccepts256AndRejects257WithoutLosingStorageBound()throws Exception {
        Fixture f=new Fixture();JSONObject saved=record("boundary-256","bot-a");JSONArray turns=new JSONArray();
        for(int i=0;i<256;i++)turns.put(new JSONObject().put("id","turn-"+i).put("speaker",i%2==0?"user":"assistant").put("text","Short final text").put("atMs",NOW-1000+i));
        saved.put("turns",turns);assertEquals("pending",f.core.stage(f.context(),saved).getString("syncStatus"));
        assertEquals(256,f.memory.value.getJSONObject("outbox").getJSONObject("boundary-256").getJSONObject("record").getJSONArray("turns").length());
        turns.put(new JSONObject().put("id","turn-256").put("speaker","user").put("text","Extra").put("atMs",NOW));saved.put("callId","boundary-257");
        rejected(()->f.core.stage(f.context(),saved));assertFalse(f.memory.value.getJSONObject("outbox").has("boundary-257"));assertEquals(0,f.count("recordVoiceCall"));
        Fixture expansion=new Fixture();JSONObject remote=record("remote-256","bot-a");JSONArray remoteTurns=new JSONArray();for(int i=0;i<256;i++)remoteTurns.put(turns.getJSONObject(i));remote.put("turns",remoteTurns);expansion.remoteRecord=remote;
        expansion.page.put("entries",new JSONArray().put(new JSONObject().put("id","voice-row").put("kind","voice-call").put("call",new JSONObject().put("callId","remote-256"))));
        assertEquals(256,expansion.core.page(expansion.current,null,20).getJSONArray("entries").length());
    }
    @Test public void persistenceFailureCannotDispatchARecord()throws Exception {
        Fixture f=new Fixture();f.memory.broken=true;try{f.core.record(f.context(),record("not-saved","bot-a"));fail();}catch(Exception expected){}assertEquals(0,f.count("recordVoiceCall"));
    }
}
