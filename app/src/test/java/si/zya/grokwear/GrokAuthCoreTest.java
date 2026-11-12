package si.zya.grokwear;

import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class GrokAuthCoreTest {
    private static final long NOW=1_800_000_000_000L;
    static final class Memory implements GrokAuthCore.Store {
        JSONObject value=new JSONObject();boolean failWrite;
        public JSONObject read()throws Exception{return new JSONObject(value.toString());}
        public void write(JSONObject next)throws Exception {if(failWrite)throw new java.io.IOException("synthetic private text");value=new JSONObject(next.toString());}
    }
    static final class Fixture {
        final Memory memory=new Memory();long now=NOW;final List<String> calls=new ArrayList<>();
        GrokAuthCore.Reply poll=new GrokAuthCore.Reply(404,new JSONObject()),refresh,bootstrap,access;
        /** Grok's update feed: nothing newer unless a test says otherwise. */
        GrokAuthCore.Reply version=new GrokAuthCore.Reply(204,new JSONObject());
        /** Bootstrap replies to give first, in order, before falling back to bootstrap. */
        final java.util.ArrayDeque<GrokAuthCore.Reply> bootstraps=new java.util.ArrayDeque<>();
        final List<JSONObject> requests=new ArrayList<>();final List<String> lines=new ArrayList<>();
        JSONObject lastRequest;boolean interrupt,failAfterResponse;Runnable inFlight;
        GrokAuthCore core;
        Fixture()throws Exception {
            bootstrap=new GrokAuthCore.Reply(200,new JSONObject().put("gatewayUrl","https://watch.cursorvm.com")
                    .put("gatewayToken","synthetic-gateway").put("networkToken","synthetic-route"));
            refresh=new GrokAuthCore.Reply(200,new JSONObject().put("access_token",token("owner",now+3_600_000)).put("refresh_token","rotated-secret"));
            GrokAuthCore.forgetRecoveries();
            core=new GrokAuthCore(memory,(operation,input)->{
                // The access check is read-only and marks no operation.
                if(!operation.equals(GrokAuthCore.ACCESS)&&!operation.equals(GrokAuthCore.VERSION))assertTrue(memory.value.has("operation"));
                calls.add(operation);lastRequest=new JSONObject(input.toString());requests.add(lastRequest);
                if(inFlight!=null)inFlight.run();
                if(interrupt)throw new java.io.IOException("synthetic-secret");
                if(failAfterResponse)memory.failWrite=true;
                if(operation.equals(GrokAuthCore.POLL))return poll;
                if(operation.equals(GrokAuthCore.REFRESH))return refresh;
                if(operation.equals(GrokAuthCore.BOOTSTRAP))return bootstraps.isEmpty()?bootstrap:bootstraps.poll();
                if(operation.equals(GrokAuthCore.ACCESS)&&access!=null)return access;
                if(operation.equals(GrokAuthCore.VERSION))return version;
                throw new AssertionError(operation);
            },()->now,lines::add);
        }
        void signIn(long expires)throws Exception {
            core.begin();poll=new GrokAuthCore.Reply(200,login("owner",expires));core.poll();calls.clear();requests.clear();
        }
    }
    static String token(String sub,long expiry)throws Exception {
        return "header."+Base64.getUrlEncoder().withoutPadding().encodeToString(new JSONObject().put("sub",sub)
                .put("exp",expiry/1000).toString().getBytes(StandardCharsets.UTF_8))+".signature";
    }
    static JSONObject login(String subject,long expiry)throws Exception {
        return new JSONObject().put("accessToken",token(subject,expiry)).put("refreshToken","synthetic-refresh");
    }
    interface Throwing {void run()throws Exception;}
    static void refused(Throwing work)throws Exception {
        try{work.run();fail("Expected refusal");}catch(GrokCore.Failure expected){}
    }

    @Test public void backendContextRenewsBeforeReturningAndNeverExportsRefresh()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);
        JSONObject context=f.core.backendContext();
        assertEquals(1,f.calls.size());assertEquals(GrokAuthCore.REFRESH,f.calls.get(0));
        assertEquals(token("owner",NOW+3_600_000),context.getString("accessToken"));
        assertFalse(context.has("refreshToken"));assertFalse(context.has("refresh"));
        assertFalse(f.core.state().toString().contains(context.getString("accessToken")));
    }
    @Test public void backendContextRefusesUnlinkedAndUncertainRenewal()throws Exception {
        Fixture f=new Fixture();refused(()->f.core.backendContext());
        f.signIn(NOW+1000);f.interrupt=true;refused(()->f.core.backendContext());
        f.interrupt=false;refused(()->f.core.backendContext());assertEquals(1,f.calls.size());
    }

    @Test public void displayedAttemptCancelsNewlyCompletedLogin()throws Exception {
        Fixture f=new Fixture();JSONObject displayed=f.core.begin();
        String attempt=displayed.getString("login_attempt");assertFalse(attempt.isEmpty());
        f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000));f.core.poll();
        assertTrue(f.core.state().getBoolean("linked"));assertTrue(f.core.cancelAttempt(attempt));
        assertEquals("unlinked",f.core.state().getString("status"));
        assertFalse(f.memory.value.has("tokens"));assertFalse(f.core.cancelAttempt(attempt));
    }
    @Test public void staleAttemptCannotCancelReplacementPendingOrLinkedSession()throws Exception {
        Fixture f=new Fixture();String stale=f.core.begin().getString("login_attempt");
        String current=f.core.begin().getString("login_attempt");assertNotEquals(stale,current);
        assertFalse(f.core.cancelAttempt(stale));assertEquals(current,f.core.pendingLogin().getString("login_attempt"));
        f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000));f.core.poll();
        assertFalse(f.core.cancelAttempt(stale));assertTrue(f.core.state().getBoolean("linked"));
        assertFalse(f.core.cancelAttempt(""));assertFalse(f.core.cancelAttempt(null));
    }
    @Test public void attemptCancellationTombstonesInflightPoll()throws Exception {
        Fixture f=new Fixture();String attempt=f.core.begin().getString("login_attempt");
        f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000));
        f.inFlight=()->{try{assertTrue(f.core.cancelAttempt(attempt));}catch(Exception failure){throw new AssertionError(failure);}};
        refused(()->f.core.poll());assertEquals("unlinked",f.core.state().getString("status"));
    }

    @Test public void qrCarriesOnlyChallengeAndUuid()throws Exception {
        Fixture f=new Fixture();JSONObject shown=f.core.begin(),privateLogin=f.memory.value.getJSONObject("login");
        URI u=URI.create(shown.getString("login_url"));assertEquals("https",u.getScheme());assertEquals("cursor.com",u.getHost());
        assertEquals("/loginDeepControl",u.getPath());assertTrue(u.getQuery().contains("redirectTarget=sand"));
        assertTrue(u.getQuery().contains("supportsSelectedTeamLogin=true"));
        String verifier=privateLogin.getString("verifier");assertEquals(43,verifier.length());
        String expected=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(u.getQuery().contains("challenge="+expected));assertFalse(shown.toString().contains(verifier));
        assertEquals(NOW+GrokAuthCore.LOGIN_MS,shown.getLong("expires_at_ms"));assertTrue(f.calls.isEmpty());
    }
    @Test public void beginningAgainReplacesLoginAndVerifier()throws Exception {
        Fixture f=new Fixture();JSONObject first=f.core.begin();String old=f.memory.value.toString();
        JSONObject second=f.core.begin();assertNotEquals(first.getString("login_url"),second.getString("login_url"));assertNotEquals(old,f.memory.value.toString());
    }
    @Test public void failedPersistenceDoesNotCreateVisibleLogin()throws Exception {
        Fixture f=new Fixture();f.memory.failWrite=true;
        try{f.core.begin();fail();}catch(java.io.IOException expected){}assertTrue(f.calls.isEmpty());assertEquals(0,f.memory.value.length());
    }
    @Test public void pollsOnlyAfterIntervalAndDoesNotAutoLoop()throws Exception {
        Fixture f=new Fixture();f.core.begin();assertEquals("waiting",f.core.poll().getString("status"));assertEquals(1,f.calls.size());
        f.core.poll();assertEquals(1,f.calls.size());f.now+=GrokAuthCore.POLL_MS;f.core.poll();assertEquals(2,f.calls.size());
        assertEquals(2,f.lastRequest.length());assertTrue(f.lastRequest.has("uuid"));assertTrue(f.lastRequest.has("verifier"));
    }
    @Test public void expiredLoginAndClockRollbackNeverPoll()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.now+=GrokAuthCore.LOGIN_MS;refused(()->f.core.poll());assertTrue(f.calls.isEmpty());
        f.now=NOW;f.core.begin();f.now--;refused(()->f.core.poll());assertTrue(f.calls.isEmpty());
    }
    @Test public void pendingLoginCanBeRecoveredWithoutVerifierExport()throws Exception {
        Fixture f=new Fixture();JSONObject first=f.core.begin();assertEquals(first.toString(),f.core.pendingLogin().toString());
        f.core.cancel();assertEquals(0,f.core.pendingLogin().length());assertEquals("unlinked",f.core.state().getString("status"));
    }
    @Test public void successfulLoginIsPrivateAndNeedsNoBootstrapUntilRequested()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);JSONObject state=f.core.state();
        assertTrue(state.getBoolean("linked"));assertEquals("ready",state.getString("status"));assertFalse(state.getBoolean("refresh_due"));
        assertFalse(state.toString().contains("synthetic-refresh"));assertFalse(state.toString().contains("owner"));
        assertEquals(64,state.getString("account_key").length());assertTrue(f.calls.isEmpty());assertFalse(f.memory.value.has("login"));
    }
    @Test public void cannotReplaceLiveBrowserSession()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);String before=f.memory.value.toString();
        refused(()->f.core.begin());assertEquals(before,f.memory.value.toString());
    }
    @Test public void selectedTeamIsKeptForBootstrapAndAccountBinding()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000).put("selectedTeamId",42));f.core.poll();
        String teamKey=f.core.state().getString("account_key");f.core.currentConfig();assertEquals(42,f.lastRequest.getLong("selectedTeamId"));
        Fixture other=new Fixture();other.signIn(NOW+3_600_000);assertNotEquals(teamKey,other.core.state().getString("account_key"));
    }
    @Test public void nullOrZeroSelectedTeamMeansPersonalAccount()throws Exception {
        Fixture personal=new Fixture();personal.signIn(NOW+3_600_000);
        String expectedAccount=personal.core.state().getString("account_key");
        for(Object selectedTeam:new Object[]{JSONObject.NULL,0}) {
            Fixture f=new Fixture();f.core.begin();
            f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000).put("selectedTeamId",selectedTeam));
            assertEquals("ready",f.core.poll().getString("status"));
            assertEquals(expectedAccount,f.core.state().getString("account_key"));
            f.core.currentConfig();assertFalse(f.lastRequest.has("selectedTeamId"));
            assertFalse(f.memory.value.getJSONObject("tokens").has("team"));
        }
    }
    @Test public void browserLoginStopsAtNativeFiveMinuteDeadline()throws Exception {
        Fixture f=new Fixture();JSONObject login=f.core.begin();
        assertEquals(NOW+300_000L,login.getLong("expires_at_ms"));
        f.now=NOW+299_999L;assertEquals("waiting",f.core.state().getString("status"));
        f.now=NOW+300_000L;assertEquals("unlinked",f.core.state().getString("status"));
        assertEquals(0,f.core.pendingLogin().length());refused(()->f.core.poll());assertTrue(f.calls.isEmpty());
    }
    @Test public void malformedSelectedTeamIsNotIgnored()throws Exception {
        for(Object team:new Object[]{"42","0",false,-1,1.5,9_007_199_254_740_992L}){
            Fixture f=new Fixture();f.core.begin();JSONObject payload=login("owner",NOW+3_600_000);
            payload.put("selectedTeamId",team);f.poll=new GrokAuthCore.Reply(200,payload);
            refused(()->f.core.poll());assertFalse(f.core.state().getBoolean("linked"));
        }
    }
    @Test public void failedPollIsNeverAnAccount()throws Exception {
        for(int status:new int[]{301,401,403,429,500}){
            Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(status,login("owner",NOW+3_600_000));
            refused(()->f.core.poll());assertEquals("unlinked",f.core.state().getString("status"));assertEquals(1,f.calls.size());
        }
    }
    @Test public void interruptedPollCannotRepeatAfterRestart()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.interrupt=true;refused(()->f.core.poll());
        assertEquals("login_uncertain",f.core.state().getString("status"));assertEquals(0,f.core.pendingLogin().length());
        GrokAuthCore restarted=new GrokAuthCore(f.memory,(op,in)->{throw new AssertionError("Must not retry");},()->f.now);
        refused(()->restarted.poll());restarted.cancel();assertEquals("unlinked",restarted.state().getString("status"));
    }
    @Test public void cancelledInFlightLoginCannotResurrectCredentials()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000));
        f.inFlight=()->{try{f.core.cancel();}catch(Exception e){throw new AssertionError(e);}};
        refused(()->f.core.poll());assertFalse(f.core.state().getBoolean("linked"));
    }
    @Test public void unlinkCreatesTombstoneWithoutNetwork()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);f.core.unlink();
        assertEquals("unlinked",f.core.state().getString("status"));assertEquals(1,f.memory.value.length());assertTrue(f.calls.isEmpty());
    }
    @Test public void cancelDoesNotDeleteAnExistingSession()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);String prior=f.memory.value.toString();f.core.cancel();assertEquals(prior,f.memory.value.toString());
    }
    @Test public void abandonedBootstrapAfterRestartPreservesSessionAndCanConnect()throws Exception {
        for(boolean legacy:new boolean[]{true,false}) {
            Fixture f=new Fixture();f.signIn(NOW+3_600_000);
            String tokens=f.memory.value.getJSONObject("tokens").toString();
            String generation=f.memory.value.getString("generation");
            JSONObject op=new JSONObject().put("kind",GrokAuthCore.BOOTSTRAP);
            if(!legacy)op.put("id","abandoned-before-process-restart");
            f.memory.value.put("operation",op);
            GrokAuthCore restarted=new GrokAuthCore(f.memory,(kind,input)->{
                assertEquals(GrokAuthCore.BOOTSTRAP,kind);f.calls.add(kind);return f.bootstrap;
            },()->f.now);
            restarted.currentConfig();
            assertEquals(List.of(GrokAuthCore.BOOTSTRAP),f.calls);
            assertEquals(tokens,f.memory.value.getJSONObject("tokens").toString());
            assertEquals(generation,f.memory.value.getString("generation"));
            assertEquals("ready",restarted.state().getString("status"));
        }
    }
    @Test public void restartNeverRecoversPollRefreshOrUnknownOperations()throws Exception {
        for(String kind:new String[]{GrokAuthCore.POLL,GrokAuthCore.REFRESH,"unknown"}) {
            Fixture f=new Fixture();f.signIn(NOW+3_600_000);
            f.memory.value.put("operation",new JSONObject().put("kind",kind).put("id","abandoned"));
            String before=f.memory.value.toString();
            GrokAuthCore restarted=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("Must not retry");},()->f.now);
            refused(()->restarted.currentConfig());refused(()->restarted.backendContext());refused(()->restarted.poll());
            assertEquals(before,f.memory.value.toString());
        }
    }
    @Test public void reentrantBootstrapCannotClearOrReplayLiveOperation()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        GrokAuthCore other=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("Must not replay");},()->f.now);
        f.inFlight=()->{try {
            String before=f.memory.value.toString();
            try {other.currentConfig();fail();}catch(GrokCore.Failure busy){assertFalse(busy.getMessage().contains("Relink"));}
            assertEquals(before,f.memory.value.toString());
        }catch(Exception e){throw new AssertionError(e);}};
        f.core.currentConfig();assertEquals(List.of(GrokAuthCore.BOOTSTRAP),f.calls);
        assertEquals("ready",f.core.state().getString("status"));
    }
    @Test public void simultaneousBootstrapSharesSuccessAndNeverReplaysFailure()throws Exception {
        for(boolean failLeader:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.signIn(NOW+3_600_000);
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            GrokAuthCore leader=new GrokAuthCore(f.memory,(op,input)->{
                calls.incrementAndGet();entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                if(failLeader)throw new java.io.IOException();return f.bootstrap;
            },()->f.now);
            GrokAuthCore follower=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("Must not replay");},()->f.now);
            java.util.concurrent.atomic.AtomicReference<Throwable> leaderError=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> followerError=new java.util.concurrent.atomic.AtomicReference<>();
            Thread first=new Thread(()->{try{leader.currentConfig();}catch(Throwable e){leaderError.set(e);}});
            Thread second=new Thread(()->{try{follower.currentConfig();}catch(Throwable e){followerError.set(e);}});
            first.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));second.start();
            try {
                long deadline=System.nanoTime()+5_000_000_000L;
                while(second.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield();
                assertEquals(Thread.State.TIMED_WAITING,second.getState());
            }finally{release.countDown();}
            first.join(5000);second.join(5000);assertFalse(first.isAlive());assertFalse(second.isAlive());
            assertEquals(1,calls.get());assertTrue(f.core.state().getBoolean("linked"));
            if(failLeader) {
                assertTrue(leaderError.get() instanceof GrokCore.Failure);assertTrue(followerError.get() instanceof GrokCore.Failure);
                assertFalse(followerError.get().getMessage().contains("Relink"));
            }else {assertNull(leaderError.get());assertNull(followerError.get());assertTrue(f.memory.value.has("config"));}
        }
    }
    @Test public void tokenReadsDoNotWaitForOrFailWithABootstrapInFlight()throws Exception {
        for(boolean failLeader:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.signIn(NOW+3_600_000);
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            GrokAuthCore leader=new GrokAuthCore(f.memory,(op,input)->{
                entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                if(failLeader)throw new java.io.IOException();return f.bootstrap;
            },()->f.now);
            GrokAuthCore caller=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("No request for fresh tokens: "+op);},()->f.now);
            Thread first=new Thread(()->{try{leader.currentConfig();}catch(Throwable ignored){}});
            first.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try {
                // A call's backend steps read the tokens while the Bot's sandbox is still starting.
                long started=System.nanoTime();JSONObject context=caller.backendContext();
                assertTrue("didn't wait for the bootstrap",System.nanoTime()-started<2_000_000_000L);
                assertFalse(context.getString("accessToken").isEmpty());
            }finally{release.countDown();}
            first.join(5000);assertFalse(first.isAlive());
            assertNotNull(caller.backendContext());assertTrue(f.core.state().getBoolean("linked"));
        }
    }
    @Test public void simultaneousRenewalIsSharedAndNeverReplayed()throws Exception {
        for(boolean failLeader:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.signIn(NOW+1000);
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger refreshes=new java.util.concurrent.atomic.AtomicInteger();
            GrokAuthCore leader=new GrokAuthCore(f.memory,(op,input)->{
                if(!op.equals(GrokAuthCore.REFRESH))throw new AssertionError(op);
                refreshes.incrementAndGet();entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                if(failLeader)throw new java.io.IOException();return f.refresh;
            },()->f.now);
            GrokAuthCore follower=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("Must not replay");},()->f.now);
            java.util.concurrent.atomic.AtomicReference<Throwable> leaderError=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> followerError=new java.util.concurrent.atomic.AtomicReference<>();
            Thread first=new Thread(()->{try{leader.backendContext();}catch(Throwable e){leaderError.set(e);}});
            Thread second=new Thread(()->{try{follower.backendContext();}catch(Throwable e){followerError.set(e);}});
            first.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            // A renewal this process is running is routine: the app keeps showing the account, not an error.
            assertEquals("ready",f.core.state().getString("status"));assertTrue(f.core.state().getBoolean("refresh_pending"));
            second.start();
            try {
                long deadline=System.nanoTime()+5_000_000_000L;
                while(second.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield();
                assertEquals(Thread.State.TIMED_WAITING,second.getState());
            }finally{release.countDown();}
            first.join(5000);second.join(5000);assertFalse(first.isAlive());assertFalse(second.isAlive());
            assertEquals(1,refreshes.get());
            if(failLeader) {
                assertNotNull(leaderError.get());assertTrue(followerError.get() instanceof GrokCore.Failure);
                assertEquals("an uncertain renewal still blocks","renewal_uncertain",f.core.state().getString("status"));
            }else {
                assertNull(leaderError.get());assertNull(followerError.get());
                assertEquals("rotated-secret",f.memory.value.getJSONObject("tokens").getString("refresh"));assertEquals("ready",f.core.state().getString("status"));
            }
        }
    }
    @Test public void bootstrapUsesAccessTokenAndMapsNetworkRouting()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);JSONObject config=f.core.currentConfig();
        assertEquals("synthetic-route",config.getJSONObject("headers").getString("x-anyrun-network-token"));
        assertTrue(f.lastRequest.has("accessToken"));assertFalse(f.lastRequest.has("refreshToken"));
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP),f.calls);f.core.currentConfig();assertEquals(1,f.calls.size());
    }
    @Test public void emptyOrNullOptionalRoutingTokenOmitsHeader()throws Exception {
        for(String alias:new String[]{"anyrunNetworkToken","anyrun_network_token","networkToken","network_token"})
            for(Object token:new Object[]{"",JSONObject.NULL}) {
                JSONObject response=new JSONObject().put("gatewayUrl","https://watch.cursorvm.com")
                        .put("gatewayToken","synthetic-gateway").put(alias,token);
                JSONObject config=GrokAuthCore.configFrom(response);
                assertEquals(0,config.getJSONObject("headers").length());
                assertEquals("synthetic-gateway",config.getString("gatewayToken"));
            }
    }
    @Test public void optionalRoutingTokenNeverReplacesReturnedRoutingHeaders()throws Exception {
        for(Object token:new Object[]{"",JSONObject.NULL,"other-token"}) {
            JSONObject response=new JSONObject().put("gatewayUrl","https://watch.cursorvm.com").put("gatewayToken","synthetic-gateway")
                    .put("gatewayHeaders",new JSONObject().put("x-anyrun-network-token","returned-route")).put("networkToken",token);
            assertEquals("returned-route",GrokAuthCore.configFrom(response).getJSONObject("headers").getString("x-anyrun-network-token"));
        }
    }
    @Test public void malformedRoutingTokenAndMissingGatewayCredentialsStayRejected()throws Exception {
        for(Object token:new Object[]{42,false,new JSONObject(),"line\nbreak"}) {
            JSONObject response=new JSONObject().put("gatewayUrl","https://watch.cursorvm.com").put("gatewayToken","synthetic-gateway").put("networkToken",token);
            refused(()->GrokAuthCore.configFrom(response));
        }
        for(String key:new String[]{"gatewayUrl","gatewayToken"}) {
            JSONObject response=new JSONObject().put("gatewayUrl","https://watch.cursorvm.com").put("gatewayToken","synthetic-gateway").put("networkToken",JSONObject.NULL);
            response.remove(key);refused(()->GrokAuthCore.configFrom(response));
        }
    }
    @Test public void descriptorCacheExpiresAndClockRollbackDoesNotExtendIt()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);f.core.currentConfig();f.now+=GrokAuthCore.CONFIG_CACHE_MS;f.core.currentConfig();assertEquals(2,f.calls.size());
        f.now-=GrokAuthCore.CONFIG_CACHE_MS+1;f.core.currentConfig();assertEquals(3,f.calls.size());
    }
    @Test public void expiredGatewayCredentialTriggersBootstrap()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);f.core.currentConfig();
        f.memory.value.getJSONObject("config").put("gatewayToken",token("gateway",NOW+1000));f.core.currentConfig();assertEquals(2,f.calls.size());
    }
    @Test public void bootstrapServerErrorDoesNotRotateOrRetry()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);f.bootstrap=new GrokAuthCore.Reply(503,new JSONObject());refused(()->f.core.currentConfig());
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP),f.calls);assertEquals("ready",f.core.state().getString("status"));
    }
    static GrokAuthCore.Reply refusal(int status,String hint,String scope){
        return new GrokAuthCore.Reply(status,new JSONObject(),new ServiceRefusal(status,status==401?"unauthenticated":"permission_denied",hint,scope));
    }
    static GrokAuthCore.Reply accessReply(String state,String reason,String href)throws Exception {
        JSONObject body=new JSONObject().put("state","SAND_ACCESS_STATE_"+state).put("blockReason","SAND_ACCESS_BLOCK_REASON_"+reason);
        if(href!=null)body.put("unifiedAccessPaywall",new JSONObject().put("title","Get Grok Bot").put("body","Subscribe").put("actionLabel","Subscribe").put("href",href));
        return new GrokAuthCore.Reply(200,body);
    }
    @Test public void staleTeamIsDroppedAndAskedAgainWithoutIt()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000).put("selectedTeamId",42));f.core.poll();f.calls.clear();f.requests.clear();
        String before=f.core.state().getString("account_key"),generation=f.core.state().getString("auth_generation");
        f.bootstraps.add(refusal(401,"","stale_team"));
        JSONObject config=f.core.currentConfig();
        assertEquals("synthetic-gateway",config.getString("gatewayToken"));
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.BOOTSTRAP),f.calls);
        assertEquals(42,f.requests.get(0).getLong("selectedTeamId"));assertFalse(f.requests.get(1).has("selectedTeamId"));
        assertFalse(f.memory.value.getJSONObject("tokens").has("team"));
        // Same sign-in, new account binding: callers relink like a new account.
        assertNotEquals(before,f.core.state().getString("account_key"));assertEquals(generation,f.core.state().getString("auth_generation"));
        assertFalse(f.core.state().getBoolean("team"));
        assertTrue(f.lines.contains("Bot sandbox start: ok after dropping a stale team"));
    }
    @Test public void anotherRefusedTokenIsRenewedOnceAndAskedAgain()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.refresh=new GrokAuthCore.Reply(200,new JSONObject().put("access_token",token("owner",NOW+7_200_000)).put("refresh_token","rotated-secret"));
        f.bootstraps.add(refusal(401,"",""));
        f.core.currentConfig();
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.REFRESH,GrokAuthCore.BOOTSTRAP),f.calls);
        assertEquals(token("owner",NOW+7_200_000),f.requests.get(2).getString("accessToken"));
        assertEquals("rotated-secret",f.memory.value.getJSONObject("tokens").getString("refresh"));
        assertEquals("ready",f.core.state().getString("status"));
    }
    @Test public void aStillRefusedAccountIsExplainedByItsAccessAndHeld()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.refresh=new GrokAuthCore.Reply(200,new JSONObject().put("access_token",token("owner",NOW+7_200_000)).put("refresh_token","rotated-secret"));
        f.bootstrap=refusal(401,"","");f.access=accessReply("PAYMENT_REQUIRED","PAYWALL_INDIVIDUAL","https://cursor.com/bot/subscribe?from=watch");
        try{f.core.currentConfig();fail();}
        catch(GrokCore.Failure refused){
            assertEquals("Grok Bot needs a plan on this account. Subscribe on your phone, then try again.",refused.getMessage());
            assertEquals("https://cursor.com/bot/subscribe?from=watch",refused.link);assertEquals("paywall_individual",refused.code);assertEquals(401,refused.status);
        }
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.REFRESH,GrokAuthCore.BOOTSTRAP,GrokAuthCore.ACCESS),f.calls);
        assertTrue(f.lines.contains("Access: payment_required paywall_individual"));
        assertEquals("ready",f.core.state().getString("status"));
        // Background callers get the same answer for a while without asking Grok again.
        f.calls.clear();refused(()->f.core.currentConfig());assertTrue(f.calls.isEmpty());
        // Try again releases it, and a token renewed once isn't renewed again for the same refusal.
        GrokAuthCore.releaseHold();refused(()->f.core.currentConfig());
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.ACCESS),f.calls);
    }
    @Test public void aGrantedAccountStillRefusedSaysSignInWasRefused()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.refresh=new GrokAuthCore.Reply(200,new JSONObject().put("access_token",token("owner",NOW+7_200_000)).put("refresh_token","rotated-secret"));
        f.bootstrap=refusal(403,"","");f.access=accessReply("GRANTED","NONE",null);
        try{f.core.currentConfig();fail();}
        catch(GrokCore.Failure refused){assertEquals("Grok refused this sign-in. No message was sent.",refused.getMessage());assertEquals("",refused.link);}
        // A 403 isn't a rejected token: no renewal.
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.ACCESS),f.calls);
    }
    @Test public void aFailureHintIsExplainedWithoutAnAccessCheck()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.bootstrap=refusal(403,"SAND_CLIENT_UPDATE_REQUIRED","");
        try{f.core.currentConfig();fail();}
        catch(GrokCore.Failure refused){assertEquals("Grok needs a newer Grok Wear. Update the app, then try again.",refused.getMessage());assertEquals("sand_client_update_required",refused.code);}
        // The update feed had nothing newer, so the app itself needs updating.
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.VERSION),f.calls);
        assertTrue(f.lines.contains("Client version check: nothing newer than "+GrokAuthCore.CLIENT_VERSION));
    }
    @Test public void aClientVersionGrokCallsTooOldIsUpdatedFromTheFeedAndAskedAgain()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.bootstraps.add(refusal(401,"SAND_CLIENT_UPDATE_REQUIRED",""));
        f.version=new GrokAuthCore.Reply(200,new JSONObject().put("version","9.4.2").put("url","https://downloads.cursor.com/x"));
        assertEquals("synthetic-gateway",f.core.currentConfig().getString("gatewayToken"));
        // Not a token problem: no renewal, just the feed and the same request again.
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.VERSION,GrokAuthCore.BOOTSTRAP),f.calls);
        assertEquals(GrokAuthCore.CLIENT_VERSION,f.requests.get(1).getString("current"));
        assertEquals("9.4.2",GrokAuthCore.clientVersion());
        assertTrue(f.lines.contains("Client version: "+GrokAuthCore.CLIENT_VERSION+" to 9.4.2 from Grok's update feed"));
        assertTrue(f.lines.contains("Bot sandbox start: ok after updating the client version"));
    }
    @Test public void stillTooOldAfterTheFeedIsAskedOnceAndExplained()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.bootstrap=refusal(401,"SAND_CLIENT_UPDATE_REQUIRED","");
        f.version=new GrokAuthCore.Reply(200,new JSONObject().put("version","9.4.2"));
        try{f.core.currentConfig();fail();}
        catch(GrokCore.Failure refused){assertEquals("Grok needs a newer Grok Wear. Update the app, then try again.",refused.getMessage());}
        assertEquals(List.of(GrokAuthCore.BOOTSTRAP,GrokAuthCore.VERSION,GrokAuthCore.BOOTSTRAP),f.calls);
    }
    @Test public void onlyANewerPlainVersionIsUsed()throws Exception {
        GrokAuthCore.forgetRecoveries();
        assertTrue(GrokAuthCore.newer("1.0.2","1.0.1"));assertTrue(GrokAuthCore.newer("1.10.0","1.9.9"));assertTrue(GrokAuthCore.newer("2.0.0","1.99.99"));
        assertFalse(GrokAuthCore.newer("1.0.1","1.0.1"));assertFalse(GrokAuthCore.newer("0.79.3","1.0.1"));
        for(String odd:new String[]{"","1.0","1.0.2-beta","1.0.2 ","../1.0.2","1.0.2\nx","99999.0.0",null})
            assertFalse(String.valueOf(odd),GrokAuthCore.useClientVersion(odd));
        assertEquals(GrokAuthCore.CLIENT_VERSION,GrokAuthCore.clientVersion());
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.bootstrap=refusal(401,"SAND_CLIENT_UPDATE_REQUIRED","");f.version=new GrokAuthCore.Reply(200,new JSONObject().put("version","0.9.0"));
        refused(()->f.core.currentConfig());assertEquals(GrokAuthCore.CLIENT_VERSION,GrokAuthCore.clientVersion());
        assertTrue(f.lines.contains("Client version check: nothing newer than "+GrokAuthCore.CLIENT_VERSION));
    }
    @Test public void otherRequestsUpdateATooOldClientVersion()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);JSONObject used=f.core.backendContext();
        f.version=new GrokAuthCore.Reply(200,new JSONObject().put("version","9.4.2"));
        ServiceRefusal tooOld=new ServiceRefusal(401,"unauthenticated","SAND_CLIENT_UPDATE_REQUIRED","");
        assertEquals(GrokAuthCore.VERSION_UPDATED,f.core.recoverRejected(used,tooOld));
        assertEquals(GrokAuthCore.NOT_RECOVERED,f.core.recoverRejected(used,tooOld));
        // Another failure hint isn't about the token: no renewal.
        assertEquals(GrokAuthCore.NOT_RECOVERED,f.core.recoverRejected(used,new ServiceRefusal(401,"unauthenticated","USER_BLOCKED","")));
        assertEquals(List.of(GrokAuthCore.VERSION,GrokAuthCore.VERSION),f.calls);
    }
    @Test public void aCallerWaitingOnARefusedStartGetsItsReasonNotStillConnecting()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        GrokAuthCore leader=new GrokAuthCore(f.memory,(op,input)->{
            if(!op.equals(GrokAuthCore.BOOTSTRAP))throw new AssertionError(op);
            entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
            return refusal(401,"USER_BLOCKED","");
        },()->f.now);
        GrokAuthCore follower=new GrokAuthCore(f.memory,(op,input)->{throw new AssertionError("Must not replay");},()->f.now);
        java.util.concurrent.atomic.AtomicReference<Throwable> followerError=new java.util.concurrent.atomic.AtomicReference<>();
        Thread first=new Thread(()->{try{leader.currentConfig();}catch(Throwable ignored){}});
        Thread second=new Thread(()->{try{follower.poll();}catch(Throwable e){followerError.set(e);}});
        first.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));second.start();
        try {
            long deadline=System.nanoTime()+5_000_000_000L;
            while(second.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING,second.getState());
        }finally{release.countDown();}
        first.join(5000);second.join(5000);assertFalse(first.isAlive());assertFalse(second.isAlive());
        assertTrue(String.valueOf(followerError.get()),followerError.get() instanceof GrokCore.Failure);
        assertEquals("Grok has blocked this account from Grok Bot. Check your account on your phone.",followerError.get().getMessage());
    }
    @Test public void otherRequestsRecoverLikeTheSandboxStart()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(200,login("owner",NOW+3_600_000).put("selectedTeamId",7));f.core.poll();f.calls.clear();
        JSONObject used=f.core.backendContext();
        assertEquals(GrokAuthCore.TEAM_DROPPED,f.core.recoverRejected(used,new ServiceRefusal(401,"unauthenticated","","stale_team")));
        assertFalse(f.core.backendContext().has("selectedTeamId"));
        f.refresh=new GrokAuthCore.Reply(200,new JSONObject().put("access_token",token("owner",NOW+7_200_000)).put("refresh_token","rotated-secret"));
        JSONObject current=f.core.backendContext();
        assertEquals(GrokAuthCore.RENEWED,f.core.recoverRejected(current,new ServiceRefusal(401,"unauthenticated","","")));
        assertEquals(token("owner",NOW+7_200_000),f.core.backendContext().getString("accessToken"));
        // The renewed token refused again: renewing won't help.
        assertEquals(GrokAuthCore.NOT_RECOVERED,f.core.recoverRejected(f.core.backendContext(),new ServiceRefusal(401,"unauthenticated","","")));
        assertEquals(GrokAuthCore.NOT_RECOVERED,f.core.recoverRejected(f.core.backendContext(),new ServiceRefusal(403,"permission_denied","","")));
        assertEquals(List.of(GrokAuthCore.REFRESH),f.calls);
    }
    @Test public void bootstrapCannotSendCredentialsToForeignHost()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);f.bootstrap.body.put("gatewayUrl","https://cursorvm.com.evil.test");
        refused(()->f.core.currentConfig());assertFalse(f.memory.value.has("config"));
    }
    @Test public void refreshOccursOnlyWhenNaturallyDueBeforeBootstrap()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+GrokAuthCore.REFRESH_LEAD_MS+1000);f.core.currentConfig();assertEquals(List.of(GrokAuthCore.BOOTSTRAP),f.calls);
        f.calls.clear();f.now+=1000;String generation=f.core.state().getString("auth_generation");f.core.currentConfig();
        assertEquals(List.of(GrokAuthCore.REFRESH,GrokAuthCore.BOOTSTRAP),f.calls);
        assertEquals(generation,f.core.state().getString("auth_generation"));
        assertEquals("rotated-secret",f.memory.value.getJSONObject("tokens").getString("refresh"));
        assertFalse(f.core.state().getBoolean("refresh_pending"));
    }
    @Test public void omittedRefreshTokenRetainsCurrentToken()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);f.refresh.body.remove("refresh_token");f.core.currentConfig();
        assertEquals("synthetic-refresh",f.memory.value.getJSONObject("tokens").getString("refresh"));
    }
    @Test public void interruptedRefreshHasDurableUncertaintyAndCannotRepeat()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);f.interrupt=true;refused(()->f.core.currentConfig());
        assertEquals("renewal_uncertain",f.core.state().getString("status"));
        f.interrupt=false;refused(()->f.core.currentConfig());assertEquals(List.of(GrokAuthCore.REFRESH),f.calls);
    }
    @Test public void malformedOrRefusedRefreshNeverBootstraps()throws Exception {
        for(int mode=0;mode<4;mode++){
            Fixture f=new Fixture();f.signIn(NOW+1000);
            if(mode==0)f.refresh=new GrokAuthCore.Reply(401,new JSONObject());
            if(mode==1)f.refresh.body.put("shouldLogout",true);
            if(mode==2)f.refresh.body.put("access_token","malformed");
            if(mode==3)f.refresh.body.put("access_token",token("different-account",NOW+3_600_000));
            refused(()->f.core.currentConfig());assertEquals("renewal_uncertain",f.core.state().getString("status"));
            assertEquals(List.of(GrokAuthCore.REFRESH),f.calls);
        }
    }
    @Test public void diskFailureBeforeRefreshPreventsNetwork()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);f.memory.failWrite=true;
        try{f.core.currentConfig();fail();}catch(java.io.IOException expected){}assertTrue(f.calls.isEmpty());
    }
    @Test public void diskFailureAfterRefreshNeverReusesOldToken()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);f.failAfterResponse=true;
        try{f.core.currentConfig();fail();}catch(java.io.IOException expected){}
        f.memory.failWrite=false;f.failAfterResponse=false;refused(()->f.core.currentConfig());assertEquals(1,f.calls.size());
        assertTrue(f.core.state().getBoolean("refresh_pending"));
    }
    @Test public void unlinkDuringRefreshPreventsResurrection()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+1000);
        f.inFlight=()->{try{f.core.unlink();}catch(Exception e){throw new AssertionError(e);}};
        refused(()->f.core.currentConfig());assertEquals("unlinked",f.core.state().getString("status"));
        assertEquals(List.of(GrokAuthCore.REFRESH),f.calls);
    }
    @Test public void cancellationBeforeBootstrapCommitCannotRestoreConfig()throws Exception {
        Fixture f=new Fixture();f.signIn(NOW+3_600_000);
        f.inFlight=()->{try{f.core.unlink();}catch(Exception e){throw new AssertionError(e);}};
        refused(()->f.core.currentConfig());assertFalse(f.memory.value.has("config"));assertEquals("unlinked",f.core.state().getString("status"));
    }
    @Test public void transportExceptionCannotLeakPrivateData()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.interrupt=true;
        try{f.core.poll();fail();}catch(Exception e){assertFalse(e.getMessage().contains("synthetic-secret"));assertNull(e.getCause());}
    }
    @Test public void unknownExpiryOrExpiredLoginDoesNotBecomeReady()throws Exception {
        Fixture f=new Fixture();f.core.begin();f.poll=new GrokAuthCore.Reply(200,login("owner",NOW-1000));refused(()->f.core.poll());
        assertFalse(f.core.state().getBoolean("linked"));
    }
}
