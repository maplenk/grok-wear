package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RosterCacheTest {
    private static final String ACCOUNT="synthetic-account",GENERATION="synthetic-generation";
    private static JSONObject roster(String suffix)throws Exception {
        return new JSONObject().put("agents",new JSONArray()
            .put(new JSONObject().put("id","first").put("name","First "+suffix).put("avatarColor","blue"))
            .put(new JSONObject().put("id","second").put("name","Second "+suffix)));
    }
    private static GrokCore linked(GrokCoreTest.Memory store,GrokCore.Transport transport)throws Exception {
        GrokCore core=new GrokCore(store,transport);core.linkBrowser(GrokCoreTest.config(),ACCOUNT,GENERATION);return core;
    }
    private static JSONObject preferences()throws Exception {
        return GrokCore.botPreferences(new JSONObject().put("settings",new JSONObject()
            .put("mainAgentId","first").put("pinnedAgents",new JSONObject().put("agentIds",new JSONArray().put("second")))));
    }
    private static JSONObject cache(GrokCore core)throws Exception{return core.cachedRoster(ACCOUNT,GENERATION);}

    @Test public void savedRosterAndPreferencesSurviveRestartWithoutNetwork()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();
        GrokCore original=linked(store,(config,method,body)->roster("saved"));
        assertFalse(cache(original).getBoolean("present"));
        original.bots();original.saveBotPreferences(preferences(),ACCOUNT,GENERATION);original.select("second");
        GrokCore restarted=new GrokCore(store,(config,method,body)->{throw new AssertionError("Cache must not use network");});
        JSONObject cached=cache(restarted);
        assertTrue(cached.getBoolean("present"));assertTrue(cached.getLong("fetchedAtMs")>0);
        assertEquals(2,cached.getJSONArray("bots").length());assertEquals("blue",cached.getJSONArray("bots").getJSONObject(0).getString("avatarColor"));
        assertEquals("first",cached.getJSONObject("preferences").getString("mainAgentId"));
        assertEquals("second",cached.getJSONObject("preferences").getJSONArray("pinnedAgentIds").getString(0));
        assertEquals("second",restarted.view().getJSONObject("bot").getString("id"));
        cached.getJSONArray("bots").getJSONObject(0).put("name","Changed local copy");
        cached.getJSONObject("preferences").put("mainAgentId","changed");
        assertEquals("First saved",cache(restarted).getJSONArray("bots").getJSONObject(0).getString("name"));
        assertEquals("first",cache(restarted).getJSONObject("preferences").getString("mainAgentId"));
    }

    @Test public void existingCandidatesMigrateWithoutFetchAndEmptyRosterIsStillCached()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();
        GrokCore core=linked(store,(config,method,body)->new JSONObject().put("agents",new JSONArray()));
        store.data.put("candidates",roster("old").getJSONArray("agents"));
        assertTrue(cache(core).getBoolean("present"));assertEquals(0,cache(core).getLong("fetchedAtMs"));
        assertEquals(2,cache(core).getJSONArray("bots").length());
        core.bots();assertTrue(cache(core).getBoolean("present"));assertEquals(0,cache(core).getJSONArray("bots").length());
    }

    @Test public void failedMalformedOrUnwritableRefreshKeepsGoodCacheAndPreferences()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();int[] outcome={0};
        GrokCore core=linked(store,(config,method,body)->{
            if(outcome[0]==1)throw new java.io.IOException("synthetic offline");
            return outcome[0]==2?new JSONObject():roster(outcome[0]==3?"new":"saved");
        });
        core.bots();core.saveBotPreferences(preferences(),ACCOUNT,GENERATION);
        String saved=store.read().toString();
        for(int test:new int[]{1,2,3}){
            outcome[0]=test;store.fail=test==3;
            try{core.bots();fail("Refresh should fail");}catch(Exception expected){}
            assertEquals(saved,store.read().toString());
        }
        store.fail=true;
        try{core.saveBotPreferences(new JSONObject(),ACCOUNT,GENERATION);fail();}catch(Exception expected){}
        assertEquals(saved,store.read().toString());
    }

    @Test public void accountGenerationAndUnlinkCannotExposeOrRestoreAnotherCache()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();GrokCore core=linked(store,(config,method,body)->roster("saved"));
        core.bots();core.saveBotPreferences(preferences(),ACCOUNT,GENERATION);
        String saved=store.read().toString();
        for(String[] identity:new String[][]{{"other-account",GENERATION},{ACCOUNT,"other-generation"},{"",GENERATION},{ACCOUNT,""}}){
            try{core.cachedRoster(identity[0],identity[1]);fail();}catch(GrokCore.Failure expected){}
            try{core.saveBotPreferences(new JSONObject(),identity[0],identity[1]);fail();}catch(GrokCore.Failure expected){}
            assertEquals(saved,store.read().toString());
        }
        core.unlink();try{cache(core);fail();}catch(GrokCore.Failure expected){}
        core.linkBrowser(GrokCoreTest.config(),ACCOUNT,"new-generation");
        JSONObject fresh=core.cachedRoster(ACCOUNT,"new-generation");
        assertFalse(fresh.getBoolean("present"));assertEquals(0,fresh.getJSONArray("bots").length());assertEquals(0,fresh.getJSONObject("preferences").length());
    }

    @Test public void slowRosterFetchDoesNotBlockCachedSelectionAndMergesLatestLocalState()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();CountDownLatch networkEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean slow=new AtomicBoolean();
        GrokCore core=linked(store,(config,method,body)->{
            if(slow.get()){networkEntered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Test release missing");}
            return roster(slow.get()?"updated":"saved");
        });
        core.bots();core.select("first");slow.set(true);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<JSONArray> fetch=workers.submit(()->core.bots());assertTrue(networkEntered.await(5,TimeUnit.SECONDS));
            Future<String> selection=workers.submit(()->{
                assertEquals(2,cache(core).getJSONArray("bots").length());core.select("second");
                core.saveDraft("A newer local draft",true);return core.view().getJSONObject("bot").getString("id");
            });
            assertEquals("second",selection.get(2,TimeUnit.SECONDS));
            release.countDown();fetch.get(5,TimeUnit.SECONDS);
            assertEquals("second",core.view().getJSONObject("bot").getString("id"));
            assertEquals("Second updated",core.view().getJSONObject("bot").getString("name"));
            assertEquals("A newer local draft",core.draft().getString("text"));
        }finally{release.countDown();workers.shutdownNow();}
    }

    @Test public void staleFetchAfterUnlinkAndRelinkCannotPopulateNewLogin()throws Exception {
        GrokCoreTest.Memory store=new GrokCoreTest.Memory();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        GrokCore core=linked(store,(config,method,body)->{
            entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Test release missing");return roster("stale");
        });
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<JSONArray> fetch=workers.submit(()->core.bots());assertTrue(entered.await(5,TimeUnit.SECONDS));
            workers.submit(()->{core.unlink();core.linkBrowser(GrokCoreTest.config(),ACCOUNT,"new-generation");return true;}).get(2,TimeUnit.SECONDS);
            release.countDown();
            try{fetch.get(5,TimeUnit.SECONDS);fail("Stale response accepted");}
            catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof GrokCore.Failure);}
            JSONObject current=core.cachedRoster(ACCOUNT,"new-generation");assertFalse(current.getBoolean("present"));
            assertFalse(store.read().has("bot"));assertFalse(store.read().has("candidates"));
        }finally{release.countDown();workers.shutdownNow();}
    }

    @Test public void recreatedActivitySelectionCannotBeOverwrittenInsideOldRosterCommit()throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> data=new java.util.concurrent.atomic.AtomicReference<>("{}");
        CountDownLatch mergeRead=new CountDownLatch(1),releaseMerge=new CountDownLatch(1),selectionStarted=new CountDownLatch(1),selectionRead=new CountDownLatch(1);
        AtomicBoolean pauseMerge=new AtomicBoolean(),refresh=new AtomicBoolean();
        GrokCore.Store store=new GrokCore.Store(){
            public JSONObject read()throws Exception {
                JSONObject snapshot=new JSONObject(data.get());
                if(Thread.currentThread().getName().equals("old-roster")&&pauseMerge.compareAndSet(true,false)){
                    mergeRead.countDown();if(!releaseMerge.await(10,TimeUnit.SECONDS))throw new AssertionError("Missing merge release");
                }
                if(Thread.currentThread().getName().equals("new-selection"))selectionRead.countDown();
                return snapshot;
            }
            public void write(JSONObject root){data.set(root.toString());}
        };
        GrokCore oldActivity=new GrokCore(store,(config,method,body)->{
            if(refresh.get())pauseMerge.set(true);return roster(refresh.get()?"updated":"saved");
        });
        oldActivity.linkBrowser(GrokCoreTest.config(),ACCOUNT,GENERATION);oldActivity.bots();oldActivity.select("first");
        GrokCore newActivity=new GrokCore(store,(config,method,body)->{throw new AssertionError("Selection must stay local");});
        ExecutorService workers=Executors.newFixedThreadPool(2);refresh.set(true);
        try{
            Future<JSONArray> fetch=workers.submit(()->{Thread.currentThread().setName("old-roster");return oldActivity.bots();});
            assertTrue(mergeRead.await(5,TimeUnit.SECONDS));
            Future<?> selection=workers.submit(()->{Thread.currentThread().setName("new-selection");selectionStarted.countDown();newActivity.select("second");return null;});
            assertTrue(selectionStarted.await(5,TimeUnit.SECONDS));
            // The merge has captured an old snapshot. Another instance must wait for this
            // short local transaction, rather than write a choice that it would overwrite.
            assertFalse("Selection entered a half-finished roster transaction",selectionRead.await(250,TimeUnit.MILLISECONDS));
            releaseMerge.countDown();fetch.get(5,TimeUnit.SECONDS);selection.get(5,TimeUnit.SECONDS);
            assertEquals("second",newActivity.view().getJSONObject("bot").getString("id"));
            assertEquals("Second updated",newActivity.view().getJSONObject("bot").getString("name"));
            assertEquals(2,cache(newActivity).getJSONArray("bots").length());
        }finally{releaseMerge.countDown();workers.shutdownNow();}
    }
}
