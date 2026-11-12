package si.zya.grokwear;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Blocking account API. Use background workers, never the UI thread; roster refresh may run separately. */
final class BotAccount {
    private final GrokAuthClient auth;
    private final GrokCore core;
    final HistoryCoordinator continuity;
    private static final java.util.concurrent.atomic.AtomicBoolean RECOVERING=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService RECOVERY=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"grok-history-recovery");t.setDaemon(true);return t;});
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    // History reads are small. A transfer that stalls this long on a weak watch link is
    // usually a dead connection, so the single read retry below starts a fresh one.
    private static final OkHttpClient HISTORY=HTTP.newBuilder().readTimeout(8,TimeUnit.SECONDS).build();
    // An account with many Bots or large Bot photos sends a Bot list of several megabytes.
    private static final OkHttpClient ROSTER=HTTP.newBuilder().callTimeout(60,TimeUnit.SECONDS).build();
    /**
     * The network changed: close idle pooled connections so the next request dials fresh instead of
     * waiting out a read timeout on a connection that died with the old network.
     */
    static void networkChanged(){try{HTTP.connectionPool().evictAll();}catch(RuntimeException ignored){}GrokAuthClient.networkChanged();NativeVoiceSession.networkChanged();}
    BotAccount(Context context) {
        auth=new GrokAuthClient(context);
        core=new GrokCore(new GrokVault(context),this::coreRequest,new GrokCore.ConfigProvider(){
            public JSONObject current(JSONObject root)throws Exception{
                JSONObject before=auth.state();verify(root,before);JSONObject config=auth.currentConfig();JSONObject after=auth.state();
                // Grok dropped a stale team during this sign-in: the account binding changed, so relink cleanly
                // and let the caller start over rather than finish with the old binding.
                if(teamChanged(before,after)){relink(config,after.optString("auth_generation"));throw new GrokCore.Failure("Grok dropped an old team from this sign-in. Loading again.",true,TEAM_CHANGED);}
                verify(root,after);return config;}
            public void stale(JSONObject config)throws Exception{auth.forgetConfig(config.optString("gatewayToken"));}
        });
        GrokVault privateHistory=new GrokVault(context,".continuity-v1");
        continuity=new HistoryCoordinator(new HistoryCoordinator.Store(){
            public JSONObject read()throws Exception{return privateHistory.read();}
            public void write(JSONObject root)throws Exception{privateHistory.write(root);}
        },this::historyGateway,this::verifyHistoryBinding,System::currentTimeMillis);
    }
    JSONObject beginLogin() throws Exception {return auth.begin();}
    JSONObject pendingLogin() throws Exception {return auth.pendingLogin();}
    JSONObject authState() throws Exception {return auth.state();}
    JSONObject pollLogin() throws Exception {
        JSONObject state=auth.poll();
        if("ready".equals(state.optString("status"))) {
            String expectedGeneration=state.optString("auth_generation");
            JSONObject config=auth.currentConfig();
            state=relink(config,expectedGeneration);
        }
        return state;
    }
    static final String TEAM_CHANGED="team_changed";
    /** Same sign-in, different account binding: Grok dropped the team chosen at sign-in. */
    private static boolean teamChanged(JSONObject before,JSONObject after){
        return before.optBoolean("linked")&&after.optBoolean("linked")&&before.optString("auth_generation").equals(after.optString("auth_generation"))
            &&!before.optString("account_key").equals(after.optString("account_key"));
    }
    /**
     * Links the core to the current sign-in. When the same sign-in's binding changed because a stale team was
     * dropped, the old binding's local state (selection, outbox, saved Bots) is cleared first, like a new account.
     */
    private JSONObject relink(JSONObject config,String expectedGeneration) throws Exception {
        synchronized(core){synchronized(GrokAuthCore.LOCK){
            JSONObject state=auth.state();
            if(!"ready".equals(state.optString("status"))||!expectedGeneration.equals(state.optString("auth_generation")))throw new GrokCore.Failure("Grok sign-in was cancelled. Start a new sign-in.",true);
            JSONObject root=core.store.read();String account=state.getString("account_key");
            if(root.has("config")&&expectedGeneration.equals(root.optString("auth_generation"))&&!account.equals(root.optString("auth_account")))core.unlink();
            core.linkBrowser(config,account,state.getString("auth_generation"));
            return state;
        }}
    }
    /** Debug builds: asks GetSandAccessStatus with this sign-in and logs the answer. */
    SandAccess accessProbe() throws Exception {auth.core.backendContext();return auth.core.accessStatus();}
    /**
     * Debug builds: asks backend ListGrokBotAgents (read-only) and describes its reply's shape: field names,
     * types, array sizes and the values of a few enum-like fields. Never names, messages or links.
     */
    String serverRosterProbe(String method,JSONObject body) throws Exception {
        if(!BuildConfig.DEBUG)throw new GrokCore.Failure("Debug only.",true);
        bound();
        // "@bot" stands for the selected Bot's id (or the first Bot's), so a probe needs no ids typed in.
        if("@bot".equals(body.optString("agentId"))){String id=selectedBot().optString("id");if(id.isEmpty()){JSONArray rows=core.cachedRoster(auth.state().getString("account_key"),auth.state().getString("auth_generation")).optJSONArray("bots");if(rows!=null&&rows.length()>0)id=rows.getJSONObject(0).optString("id");}body.put("agentId",id);}
        JSONObject context=auth.core.backendContext();bound();
        return shapeOf(execute(backendRequest(method,context),body,false),"");
    }
    private static final java.util.Set<String> SHOWN=new java.util.HashSet<>(java.util.Arrays.asList("harness","visibility","isGroup","viewerIsOwner","hiddenFromSidebar","removedFromSidebar","notifyOnUpdatesEnabled","kind","state","status","entryId","entryKind","seq","updatedSeq","role","id","nextBeforeSeq"));
    /** Debug builds: the same shape for one read-only gateway history call (getAgentTranscriptTail). */
    String gatewayProbe(JSONObject body) throws Exception {
        if(!BuildConfig.DEBUG)throw new GrokCore.Failure("Debug only.",true);
        bound();if("@bot".equals(body.optString("id")))body.put("id",selectedBot().optString("id"));
        JSONObject config=GrokCore.validateConfig(auth.currentConfig());
        return shapeOf(gateway(config,"getAgentTranscriptTail",body),"");
    }
    static String shapeOf(Object value,String key){
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;StringBuilder out=new StringBuilder("{");
            java.util.List<String> keys=new java.util.ArrayList<>();o.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);
            for(String k:keys)out.append(k).append(':').append(shapeOf(o.opt(k),k)).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;
            // Union of the elements' shapes, with how often each enum-like value appears.
            java.util.TreeMap<String,Integer> shapes=new java.util.TreeMap<>();
            for(int i=0;i<a.length();i++)shapes.merge(shapeOf(a.opt(i),key),1,Integer::sum);
            return "["+a.length()+"x "+shapes+"]";}
        if(value instanceof String)return SHOWN.contains(key)?"\""+((String)value).replaceAll("[^A-Za-z0-9_.-]","")+"\"":"s"+(((String)value).isEmpty()?"0":"");
        if(value instanceof Boolean)return SHOWN.contains(key)?value.toString():"b";
        if(value instanceof Number)return SHOWN.contains(key)?value.toString():"n";
        return value==JSONObject.NULL?"null":"?";
    }
    void cancelLogin(String attempt) throws Exception {
        synchronized(core){synchronized(GrokAuthCore.LOCK){if(auth.core.cancelAttempt(attempt)){core.unlink();}}}
    }
    void unlink() throws Exception {auth.unlink();core.unlink();continuity.clear();}
    JSONArray bots() throws Exception {bound();JSONArray result=core.bots();bound();return result;}
    /** A text message reviewed for this Bot; refused if the account or selected Bot changed since. */
    String prepareMessage(String text,String botId) throws Exception {bound();String id=core.prepare(text,core.view().getString("profile"),botId);bound();return id;}
    JSONObject sendMessage(String id,java.util.function.BooleanSupplier admitted) throws Exception {bound();return core.submit(id,admitted);}
    JSONObject confirmMessage(String id) throws Exception {bound();return core.poll(id);}
    JSONObject retryMessage(String id) throws Exception {bound();return core.retry(id);}
    JSONArray messages(String botId) throws Exception {bound();return core.messages(botId);}
    JSONObject replyAfter(String messageId) throws Exception {bound();JSONObject reply=core.replyAfter(messageId);bound();return reply;}
    /**
     * Marks a Bot's chat read. Bots run on Grok's server keep that state there (SetGrokBotAgentClientState), as
     * Grok Bot Android 1.25.0 does; others, and a server that doesn't take it, use the gateway.
     */
    void markRead(String botId) throws Exception {
        bound();
        if(serverRouted(botId)){
            try{serverCall("SetGrokBotAgentClientState",new JSONObject().put("agentId",botId).put("markRead",true));
                core.clearUnread(botId);return;}
            catch(GrokCore.Failure refused){if(!serverPathMissing(refused))throw refused;}
        }
        core.markRead(botId);
    }
    boolean serverRouted(String botId) throws Exception {return GrokCore.SERVER_HARNESS.equals(core.harnessOf(botId));}
    /**
     * Grok's server doesn't handle this Bot's request there (not admitted, a failed precondition or a method it
     * lacks): like the official app, use the gateway instead. Only a refusal counts, never a lost reply.
     */
    static boolean serverPathMissing(GrokCore.Failure refused){
        ServiceRefusal r=refused.refusal;
        return r!=null&&(r.status==403||r.status==404||r.status==412||r.status==501||"failed_precondition".equals(r.code)||"unimplemented".equals(r.code));
    }
    /** One GrokBotService request with this sign-in, recovering a 401 the way the sandbox start does. */
    private JSONObject serverCall(String method,JSONObject body) throws Exception {
        JSONObject context=auth.core.backendContext();bound();
        return recovering(HTTP,method,body,context,this::bound);
    }
    /** Disk-only account-scoped roster; never renews credentials or contacts Grok. */
    JSONObject cachedRoster() throws Exception {
        synchronized(core){synchronized(GrokAuthCore.LOCK){
            JSONObject state=auth.state();verify(core.store.read(),state);
            return core.cachedRoster(state.getString("account_key"),state.getString("auth_generation"));
        }}
    }
    JSONObject chooseBot(String id) throws Exception {bound();core.select(id);return selectedBot();}
    JSONObject selectedBot() throws Exception {bound();JSONObject bot=core.view().optJSONObject("bot");return bot==null?new JSONObject():new JSONObject(bot.toString());}
    private void bound() throws Exception {verify(core.store.read(),auth.state());}
    private static void verify(JSONObject root,JSONObject state) throws Exception {
        if(!state.optBoolean("linked")||!root.optString("auth_account").equals(state.optString("account_key"))
            ||!root.optString("auth_generation").equals(state.optString("auth_generation")))
            throw new GrokCore.Failure("Grok sign-in changed. Sign in again before continuing.",true);
    }
    JSONObject backend(String method,JSONObject body) throws Exception {return backend(HTTP,method,body);}
    /**
     * For a dropped call's redial: a fresh connection every time, so the request can't ride a pooled
     * connection that died with the old network, and a 10 s deadline instead of 30 s.
     */
    JSONObject redialBackend(String method,JSONObject body) throws Exception {
        return backend(HTTP.newBuilder().connectionPool(new okhttp3.ConnectionPool(0,1,TimeUnit.SECONDS)).callTimeout(10,TimeUnit.SECONDS).build(),method,body);
    }
    private JSONObject backend(OkHttpClient client,String method,JSONObject body) throws Exception {
        if(!java.util.Arrays.asList("MintSandVoiceCallSecret","VoiceCallHarnessSession","VoiceCallHarnessTool").contains(method))
            throw new GrokCore.Failure("This Grok voice operation is not enabled.",true);
        bound();String selected=selectedBot().optString("id");
        if(selected.isEmpty())throw new GrokCore.Failure("Choose a Bot first.",true);
        if(!"MintSandVoiceCallSecret".equals(method)&&!selected.equals(body.optString("agentId")))
            throw new GrokCore.Failure("Grok Bot changed. Start the call again.",true);
        JSONObject context=auth.core.backendContext();bound();
        return recovering(client,method,body,context,this::bound);
    }
    interface Check { void run() throws Exception; }
    /**
     * Grok's account-wide usage numbers (DashboardService/GetSandUsageStatus, body {}), read once for an out-of-usage
     * notice's reset time (UsageTrays.resetMs). Read-only, with the usual headers and 401 recovery.
     */
    JSONObject usageStatus() throws Exception {
        bound();JSONObject context=auth.core.backendContext();bound();
        return recovering(HTTP,"DashboardService","GetSandUsageStatus",new JSONObject(),context,this::bound);
    }
    /** A GrokBotService request with this sign-in's token and team. */
    private static Request.Builder backendRequest(String method,JSONObject context)throws Exception {return backendRequest("GrokBotService",method,context);}
    /** The same request to another aiserver.v1 service (DashboardService). */
    private static Request.Builder backendRequest(String service,String method,JSONObject context)throws Exception {
        Request.Builder request=new Request.Builder().url(GrokAuthCore.BACKEND+"/aiserver.v1."+service+"/"+method)
            .header("Authorization","Bearer "+context.getString("accessToken"))
            .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
            .header("x-cursor-client-version",GrokAuthCore.clientVersion()).header("x-sand-box-namespace","prod");
        if(context.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(context.getLong("selectedTeamId")));
        // The official app sends its time zone with a message as this header, never in the body.
        if("SendGrokBotUserMessage".equals(method))request.header("x-cursor-timezone",java.time.ZoneId.systemDefault().getId());
        return request;
    }
    /**
     * Like Grok Bot Android 1.18.0: a 401 refused the request before it acted, so after dropping a stale team
     * or renewing the sign-in once it is asked once more. A refusal that remains is explained (failure hint
     * or GetSandAccessStatus). A dropped team changes the account binding: the call starts over.
     */
    private JSONObject recovering(OkHttpClient client,String method,JSONObject body,JSONObject context,Check check)throws Exception {
        return recovering(client,"GrokBotService",method,body,context,check);
    }
    private JSONObject recovering(OkHttpClient client,String service,String method,JSONObject body,JSONObject context,Check check)throws Exception {
        try{return execute(client,backendRequest(service,method,context),body,false);}
        catch(GrokCore.Failure refused){
            if(refused.refusal==null)throw refused;
            int recovered=auth.core.recoverRejected(context,refused.refusal);
            if(recovered==GrokAuthCore.TEAM_DROPPED){
                JSONObject state=auth.state();relink(auth.currentConfig(),state.optString("auth_generation"));
                throw new GrokCore.Failure("Grok dropped an old team from this sign-in. Start again.",true,TEAM_CHANGED,401);
            }
            if(recovered==GrokAuthCore.RENEWED||recovered==GrokAuthCore.VERSION_UPDATED){
                JSONObject fresh=auth.core.backendContext();check.run();
                try{return execute(client,backendRequest(service,method,fresh),body,false);}
                catch(GrokCore.Failure again){throw explained(again);}
            }
            throw explained(refused);
        }
    }
    private GrokCore.Failure explained(GrokCore.Failure refused)throws Exception {
        if(refused.refusal==null||(refused.refusal.status!=401&&refused.refusal.status!=403&&refused.refusal.hintMessage().isEmpty()))return refused;
        return auth.core.refusalFailure(refused.refusal,"Grok refused this sign-in. Sign in again.");
    }
    /** The finished call's own Bot, not the current selection: the user may switch Bots right after hanging up. */
    JSONObject handoffTool(JSONObject binding,JSONObject body) throws Exception {
        if(!CallHandoff.TOOL.equals(body.optString("name")))throw new GrokCore.Failure("Only the end-of-call handoff is enabled.",true);
        verifyHistoryBinding(binding,false);
        if(!binding.optString("agentId").equals(body.optString("agentId")))throw new GrokCore.Failure("Grok Bot changed. Nothing was sent.",true);
        JSONObject context=auth.core.backendContext();verifyHistoryBinding(binding,false);
        return recovering(HTTP,"VoiceCallHarnessTool",body,context,()->verifyHistoryBinding(binding,false));
    }
    /** A call-scoped lease, created after normal renewal, never used for later tool calls. */
    NativeVoiceSession.Backend startupBackend(JSONObject requestedBinding) throws Exception {
        final JSONObject binding=new JSONObject(requestedBinding.toString());
        verifyHistoryBinding(binding,true);
        final JSONObject credentials=auth.core.backendContext();
        verifyHistoryBinding(binding,true);
        return (method,body)->{
            if(!method.equals("VoiceCallHarnessSession")&&!method.equals("MintSandVoiceCallSecret"))
                throw new GrokCore.Failure("This startup operation is not enabled.",true);
            verifyHistoryBinding(binding,true);
            if(method.equals("VoiceCallHarnessSession")&&!binding.getString("agentId").equals(body.optString("agentId")))
                throw new GrokCore.Failure("Grok Bot changed. Start the call again.",true);
            JSONObject result=recovering(HTTP,method,body,credentials,()->verifyHistoryBinding(binding,true));verifyHistoryBinding(binding,true);return result;
        };
    }
    /** Read-only account settings. Optional UI enhancement; failure must not block Bots. */
    JSONObject botPreferences() throws Exception {
        bound();JSONObject identity=auth.state(),context=auth.core.backendContext();bound();sameIdentity(identity);
        JSONObject response=execute(settings(context,"GetGrokBotUserRuntimeSettings"),new JSONObject(),false);
        return savePreferences(identity,GrokCore.botPreferences(response));
    }
    /**
     * Pins or unpins one Bot the way the official clients do: re-read the account's list, change
     * only this Bot, write the whole list back and keep the list the server stored.
     */
    JSONObject setPinned(String botId,boolean pinned) throws Exception {
        bound();JSONObject identity=auth.state(),context=auth.core.backendContext();bound();sameIdentity(identity);
        JSONObject before=execute(settings(context,"GetGrokBotUserRuntimeSettings"),new JSONObject(),false);
        JSONArray ids=GrokCore.withPin(before,botId,pinned);
        if(ids==null)throw new GrokCore.Failure("Couldn't save. Try again",true);
        sameIdentity(identity);
        JSONObject after;
        try{after=execute(settings(context,"UpdateGrokBotUserRuntimeSettings"),new JSONObject().put("pinnedAgents",new JSONObject().put("agentIds",ids)),false);}
        catch(GrokCore.Failure refused){
            if("resource_exhausted".equals(refused.code))throw new GrokCore.Failure("Pins are full. Unpin one first.",true,PINS_FULL);
            throw refused;
        }
        return savePreferences(identity,GrokCore.storedPins(before,after,ids));
    }
    static final String PINS_FULL="pins_full";
    private Request.Builder settings(JSONObject context,String method)throws Exception {
        Request.Builder request=new Request.Builder().url(GrokAuthCore.BACKEND+"/aiserver.v1.GrokBotService/"+method)
            .header("Authorization","Bearer "+context.getString("accessToken"))
            .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
            .header("x-cursor-client-version",GrokAuthCore.clientVersion()).header("x-sand-box-namespace","prod");
        if(context.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(context.getLong("selectedTeamId")));
        return request;
    }
    private void sameIdentity(JSONObject identity)throws Exception {
        JSONObject current=auth.state();
        if(!identity.optString("account_key").equals(current.optString("account_key"))||!identity.optString("auth_generation").equals(current.optString("auth_generation")))
            throw new GrokCore.Failure("Grok sign-in changed. Refresh Bots.",true);
    }
    private JSONObject savePreferences(JSONObject identity,JSONObject preferences)throws Exception {
        synchronized(core){synchronized(GrokAuthCore.LOCK){
            bound();sameIdentity(identity);JSONObject current=auth.state();
            core.saveBotPreferences(preferences,current.getString("account_key"),current.getString("auth_generation"));
            return preferences;
        }}
    }
    /**
     * Answers a Bot's approval request from its open chat (Approvals), as Grok Bot Android 1.25.0 does: a Bot run on
     * Grok's server through ResolveGrokBotAutoReviewApproval, a box Bot through the gateway's resolveAutoReviewApproval.
     * Allow or deny only. Sent once: a reply that never arrived is not sent again; the chat shows what happened.
     */
    void resolveApproval(String chatBot,JSONObject approval,boolean approved) throws Exception {
        bound();JSONObject bot=selectedBot();
        if(chatBot.isEmpty()||!chatBot.equals(bot.optString("id")))throw new GrokCore.Failure("Grok Bot changed. Nothing was sent.",true);
        // The watch answers only for your own Bots (FEATURES-SPEC §2.4); a teammate's goes through the phone.
        if(!bot.optBoolean("viewerIsOwner",true))throw new GrokCore.Failure("Answer this on your phone.",true,Approvals.ELSEWHERE);
        String harness=core.harnessOf(chatBot);
        if(GrokCore.SERVER_HARNESS.equals(harness)){
            JSONObject reply;
            try{reply=serverCall("ResolveGrokBotAutoReviewApproval",Approvals.serverBody(approval,approved));}
            catch(GrokCore.Failure refused){throw Approvals.problem(refused,true);}
            // The official app treats anything but dispatched:true as a refusal (409).
            if(!reply.optBoolean("dispatched"))throw new GrokCore.Failure("Couldn't answer. Open on phone.",true,"",409);
            return;
        }
        // Another kind of Bot has no answer lane in the official app either.
        if(!harness.isEmpty()&&!harness.equals("box"))throw new GrokCore.Failure("Answer this on your phone.",true,Approvals.ELSEWHERE);
        JSONObject body=Approvals.gatewayBody(approval,approved),config=GrokCore.validateConfig(auth.currentConfig());bound();
        try{gateway(config,"resolveAutoReviewApproval",body);}
        catch(GrokCore.Failure refused){
            // A gateway that moved on from this config refused before acting: once more with a fresh one.
            if(!GrokCore.staleGateway(refused))throw Approvals.problem(refused,false);
            auth.forgetConfig(config.getString("gatewayToken"));config=GrokCore.validateConfig(auth.currentConfig());bound();
            try{gateway(config,"resolveAutoReviewApproval",body);}catch(GrokCore.Failure again){throw Approvals.problem(again,false);}
        }
    }
    /**
     * End call (HangUpGrokBotOutboundCall) or Take over (TransferGrokBotOutboundCall) for a phone call the open chat's
     * Bot is placing (PhoneCalls), on Grok's server for every kind of Bot, as Grok Bot Android 1.25.0 does. Sent once;
     * a refusal comes back in its own words.
     */
    void controlCall(String chatBot,JSONObject call,boolean takeOver) throws Exception {
        bound();JSONObject bot=selectedBot();
        if(chatBot.isEmpty()||!chatBot.equals(bot.optString("id")))throw new GrokCore.Failure("Grok Bot changed. Nothing was sent.",true);
        // Like the official app, only your own Bots' calls are yours to end or take over.
        if(!bot.optBoolean("viewerIsOwner",true))throw new GrokCore.Failure("Only this Bot's owner can change its call.",true);
        JSONObject reply;
        try{reply=serverCall(takeOver?"TransferGrokBotOutboundCall":"HangUpGrokBotOutboundCall",PhoneCalls.body(call));}
        catch(GrokCore.Failure refused){
            // Sign-in trouble already comes in words; anything else reads as the official app's toast.
            if(refused.rejected&&(refused.status==401||refused.status==403))throw refused;
            GrokCore.Failure failed=new GrokCore.Failure(PhoneCalls.failed(takeOver),refused.rejected,refused.code,refused.status);failed.initCause(refused);throw failed;
        }
        String problem=PhoneCalls.refused(reply,takeOver);
        if(problem!=null)throw new GrokCore.Failure(problem,true,"",409);
    }
    JSONObject presence(JSONObject body) throws Exception {
        bound();String selected=selectedBot().optString("id");
        if(selected.isEmpty()||!selected.equals(body.optString("id")))
            throw new GrokCore.Failure("Grok Bot changed. Start the call again.",true);
        JSONObject config=auth.currentConfig();bound();return gateway(config,"setVoiceCallPresence",body);
    }
    private static final String INTERRUPTED="History connection was interrupted. Sync may need attention.";
    JSONObject historyPage(JSONObject binding,Long beforeSeq,int limit)throws Exception{return continuity.page(binding,beforeSeq,limit);}
    JSONObject savedHistory(JSONObject binding)throws Exception{return continuity.savedTail(binding);}
    /** One background outbox attempt on foreground entry, never joined by Talk or history reads. */
    void recoverPendingInBackground(String identity,String botId){
        if(botId.isEmpty()||!RECOVERING.compareAndSet(false,true))return;
        RECOVERY.execute(()->{try{
            if(VoiceService.snapshot().active||VoiceService.historyStaging())return;
            JSONObject binding=historyBinding();
            if(!identity.equals(binding.optString("accountKey")+"/"+binding.optString("authGeneration"))||!botId.equals(binding.optString("agentId")))return;
            continuity.recoverPending(binding);
        }catch(Exception ignored){/* Durable outbox keeps pending/uncertain state; never replay uncertain writes. */}
        finally{RECOVERING.set(false);}});
    }
    JSONObject historyPage(Long beforeSeq,int limit)throws Exception{return historyPage(historyBinding(),beforeSeq,limit);}
    JSONObject historyBinding() throws Exception {
        bound();JSONObject bot=selectedBot();String id=bot.optString("id");
        if(id.isEmpty())throw new GrokCore.Failure("Choose a Bot first.",true);
        if(!bot.has("viewerIsOwner")){core.bots();bound();bot=selectedBot();if(!id.equals(bot.optString("id")))throw new GrokCore.Failure("Choose an available Bot before opening History.",true);}
        if(bot.optBoolean("viewerMetadataInvalid"))throw new GrokCore.Failure("Grok returned unsupported conversation ownership. Refresh Bots.",true);
        JSONObject state=auth.state();
        String session=bot.optString("viewerSessionId","");
        if(!bot.has("viewerIsOwner")||bot.optBoolean("viewerIsOwner",true))session="";
        else if(session.isEmpty())throw new GrokCore.Failure("This Bot's conversation is not available on the watch.",true);
        return new JSONObject().put("accountKey",state.getString("account_key")).put("authGeneration",state.getString("auth_generation"))
            .put("agentId",id).put("sessionId",session).put("authorKey",state.optString("author_key")).put("viewerIsOwner",bot.optBoolean("viewerIsOwner",true));
    }
    void verifyHistoryBinding(JSONObject binding,boolean requireSelected) throws Exception {
        bound();JSONObject state=auth.state();
        if(!state.optString("account_key").equals(binding.optString("accountKey"))
            ||!state.optString("auth_generation").equals(binding.optString("authGeneration"))
            ||binding.optString("agentId").isEmpty())throw new GrokCore.Failure("Grok account changed. Reopen History.",true);
        if(requireSelected){JSONObject current=historyBinding();if(!current.optString("agentId").equals(binding.optString("agentId"))||!current.optString("sessionId").equals(binding.optString("sessionId")))
            throw new GrokCore.Failure("Grok conversation changed. Reopen History.",true);}
    }
    /** Narrow original gateway shapes, used by history and its live contract probe only. */
    Object historyGateway(String method,JSONObject body,JSONObject binding) throws Exception {
        if(!java.util.Arrays.asList("getAgentTranscriptTail","getConversationOutline","getVoiceCall","recordVoiceCall").contains(method))
            throw new GrokCore.Failure("This history operation is not enabled.",true);
        boolean write=method.equals("recordVoiceCall"),selectionRequired=method.equals("getAgentTranscriptTail")||method.equals("getConversationOutline");verifyHistoryBinding(binding,selectionRequired);
        String agent=write?body.getJSONObject("record").optString("agentId"):body.optString("id");
        if(!binding.optString("agentId").equals(agent))throw new GrokCore.Failure("Grok Bot changed. Nothing was synced.",true);
        // A server Bot's chat pages come from the server, in the gateway's shape.
        if(method.equals("getAgentTranscriptTail")&&serverRouted(agent)){
            try{JSONObject page=serverPage(body);verifyHistoryBinding(binding,selectionRequired);return page;}
            catch(GrokCore.Failure refused){if(!serverPathMissing(refused))throw refused;}
        }
        // Bots run on Grok's server keep their call records there (Grok Bot Android 1.18.0 and later). A server that
        // doesn't take this Bot's record sends it to the gateway; a reply that never arrived is never replayed there.
        if((write||method.equals("getVoiceCall"))&&serverRouted(agent)){
            try{
                if(write){serverCall("RecordGrokBotVoiceCall",new JSONObject().put("agentId",agent).put("record",body.getJSONObject("record")));verifyHistoryBinding(binding,false);return null;}
                JSONObject reply=serverCall("GetGrokBotVoiceCall",new JSONObject().put("agentId",agent).put("callId",body.getString("callId")));
                verifyHistoryBinding(binding,false);return reply.optJSONObject("record");
            }catch(GrokCore.Failure refused){if(!serverPathMissing(refused))throw refused;}
        }
        JSONObject config=GrokCore.validateConfig(auth.currentConfig());verifyHistoryBinding(binding,selectionRequired);
        Request.Builder request=historyRequest(config,method);
        long remaining=binding.optLong("deadlineMs",System.currentTimeMillis()+30000)-System.currentTimeMillis();if(remaining<=0)throw new GrokCore.Failure("Shared history took too long to load.",true);
        Object result;
        try{result=historyWire(request,body,method,Math.min(30000,remaining));}
        catch(GrokCore.Failure interrupted){
            // A gateway that moved on from this config refused before acting: one retry with a fresh one.
            long left=binding.optLong("deadlineMs",System.currentTimeMillis()+30000)-System.currentTimeMillis();
            if(GrokCore.staleGateway(interrupted)&&left>=3000){auth.forgetConfig(config.getString("gatewayToken"));config=GrokCore.validateConfig(auth.currentConfig());verifyHistoryBinding(binding,selectionRequired);
                result=historyWire(historyRequest(config,method),body,method,Math.min(30000,left));verifyHistoryBinding(binding,selectionRequired);return result;}
            // Reads are idempotent; one fresh attempt within the same deadline. Writes are never replayed.
            if(write||!INTERRUPTED.equals(interrupted.getMessage())||left<3000)throw interrupted;
            verifyHistoryBinding(binding,selectionRequired);result=historyWire(request,body,method,Math.min(30000,left));
        }
        verifyHistoryBinding(binding,selectionRequired);return result;
    }
    private static Request.Builder historyRequest(JSONObject config,String method)throws Exception{
        Request.Builder request=new Request.Builder().url(config.getString("gatewayUrl")+"/api/"+method).header("Authorization","Bearer "+config.getString("gatewayToken"));
        JSONObject headers=config.getJSONObject("headers");Iterator<String> keys=headers.keys();
        while(keys.hasNext()){String key=keys.next();request.header(key,headers.getString(key));}
        return request;
    }
    private static Object historyWire(Request.Builder request,JSONObject body,String method,long timeoutMs)throws Exception {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if(bytes.length>1024*1024)throw new GrokCore.Failure("History request is too large.",true);
            RequestBody payload=new RequestBody(){public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
                public long contentLength(){return bytes.length;}public boolean isOneShot(){return true;}
                public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.write(bytes);}};
            okhttp3.Call call=(method.equals("recordVoiceCall")?HTTP:HISTORY).newCall(request.post(payload).build());call.timeout().timeout(timeoutMs,TimeUnit.MILLISECONDS);
            try(Response response=call.execute()){
                if(response.code()!=200&&!(method.equals("recordVoiceCall")&&response.code()==204))throw new GrokCore.Failure("History sync failed (HTTP "+response.code()+").",true,"",response.code());
                byte[] received=response.body()==null?new byte[0]:GrokVault.readBounded(response.body().byteStream(),1024*1024);
                try{
                    String raw=new String(received,StandardCharsets.UTF_8).trim();
                    Object result=raw.isEmpty()?null:new org.json.JSONTokener(raw).nextValue();
                    if(method.equals("recordVoiceCall")&&(result==null||result==JSONObject.NULL||(result instanceof JSONObject&&((JSONObject)result).length()==0)))return null;
                    if(method.equals("getVoiceCall")&&(result==JSONObject.NULL||result==null))return null;
                    if(method.equals("getConversationOutline")&&result instanceof JSONArray)return result;
                    if(!method.equals("getConversationOutline")&&!method.equals("recordVoiceCall")&&result instanceof JSONObject)return result;
                    throw new GrokCore.Failure("Grok returned an unsupported history response.",true);
                }finally{java.util.Arrays.fill(received,(byte)0);}
            }
        }catch(GrokCore.Failure safe){throw safe;}
        catch(Exception ignored){throw new GrokCore.Failure(INTERRUPTED,true);}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    /** Receives the live event stream: the call once made, the account once open, then each frame's data until it returns false. */
    static final String SIGNED_OUT="signed_out";
    interface EventSink {
        void opened(okhttp3.Call call);
        void connected(String identity);
        boolean frame(String data) throws Exception;
    }
    // The stream stays open; the gateway sends a keep-alive about every 15 s, so a longer silence is a dead link.
    // Each attempt dials fresh: a pooled connection that died with the last network would hang until that timeout.
    private static final OkHttpClient EVENTS=HTTP.newBuilder().readTimeout(45,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS)
        .connectionPool(new okhttp3.ConnectionPool(0,1,TimeUnit.SECONDS)).build();
    /**
     * Reads the gateway's live event stream with the same credentials as its /api calls, as Grok Bot Android
     * 1.17.0 does, until it ends, the sink stops or the call is cancelled. Blocking.
     */
    void events(String channels,EventSink sink) throws Exception {
        JSONObject state=auth.state();
        // Only a missing sign-in stops the stream. "connecting" (a config fetch in flight, or one a killed process
        // left marked) is still signed in: currentConfig() below fetches the config or fails and the stream retries.
        if(!state.optBoolean("linked"))throw new GrokCore.Failure("Not signed in.",true,SIGNED_OUT);
        String identity=state.optString("account_key","")+"/"+state.optString("auth_generation","");
        JSONObject config=GrokCore.validateConfig(auth.currentConfig());
        Request.Builder request=new Request.Builder().get()
            .url(config.getString("gatewayUrl")+"/events?channels="+java.net.URLEncoder.encode(channels,"UTF-8")+"&surface=mobile")
            .header("Accept","text/event-stream").header("Accept-Encoding","identity").header("Authorization","Bearer "+config.getString("gatewayToken"));
        JSONObject headers=config.getJSONObject("headers");Iterator<String> keys=headers.keys();
        while(keys.hasNext()){String key=keys.next();request.header(key,headers.getString(key));}
        okhttp3.Call call=EVENTS.newCall(request.build());sink.opened(call);
        try(Response response=call.execute()){
            if(response.code()!=200||response.body()==null){
                // Like the gateway's /api calls: a moved gateway gets a fresh config on the next attempt.
                if(response.code()==401||response.code()==404||response.code()==417)auth.forgetConfig(config.getString("gatewayToken"));
                throw new GrokCore.Failure("Live updates refused (HTTP "+response.code()+").",true,"",response.code());
            }
            sink.connected(identity);
            okio.BufferedSource source=response.body().source();BotEvents.Frames frames=new BotEvents.Frames();
            while(true){
                String line;
                // A line longer than any frame is a broken stream; end it and let the caller reconnect.
                try{line=source.readUtf8LineStrict(BotEvents.MAX_FRAME);}catch(java.io.EOFException ended){return;}
                String data=frames.line(line);if(data!=null&&!sink.frame(data))return;
            }
        }
    }
    /** The service's own refusal codes for a message, in words; anything else stays a plain refusal. */
    static GrokCore.Failure sendRefused(String code){
        switch(code==null?"":code){
            case "send/message-too-long":return new GrokCore.Failure("This message is too long for Grok. Nothing was sent.",true,code);
            case "send/agent-unavailable":return new GrokCore.Failure("This Bot can't take messages right now. Nothing was sent.",true,code);
            case "send/agent-no-access":return new GrokCore.Failure("You can't message this Bot. Nothing was sent.",true,code);
            case "send/nonce-digest-mismatch":return new GrokCore.Failure("Grok already has a different message with this id. Nothing new was sent.",true,code);
            default:return new GrokCore.Failure("Grok refused the message. Nothing was sent.",true,code==null?"":code);
        }
    }

    // ---- Bots run on Grok's server (harness "temporal"), as Grok Bot Android 1.25.0 reaches them ----

    /** The live stream's per-Bot state (unread, preview, working), shared by the Bot list and ServerEvents. */
    static final ServerTranscripts.States SERVER_STATES=new ServerTranscripts.States();
    /** Gateway row fields a server row lacks: the photo the watch already shrank, the role line, a teammate's session. */
    private static final String[] GATEWAY_EXTRAS={"avatarDataUrl","title","viewerSessionId","hasUnread","unreadCount","isRunning","isRunningTurn","isComposingMessage","lastMessagePreview","lastActivityAt","lastViewedAt"};

    /**
     * GrokCore's requests. Bots run on Grok's server are listed, read and messaged there, like the official app;
     * everything else, and anything the server doesn't take, goes to the gateway.
     */
    private JSONObject coreRequest(JSONObject config,String method,JSONObject body) throws Exception {
        switch(method){
            case "listAgents":{
                JSONObject box=gateway(config,method,body);
                // Report a problem's timings describe the gateway list, not the server's.
                long[] transfer=LAST_TRANSFER.get().clone();
                try{return withServerRoster(box);}finally{LAST_TRANSFER.set(transfer);}
            }
            case "getAgentTranscriptTail":
                if(serverRouted(body.optString("id")))try{return serverPage(body);}catch(GrokCore.Failure refused){if(!serverPathMissing(refused))throw refused;}
                break;
            case "sendPrompt":
                // The official app sends every message through the server since 1.18.0.
                try{return serverSend(body);}catch(GrokCore.Failure refused){if(!serverPathMissing(refused))throw refused;}
                break;
            default:break;
        }
        return gateway(config,method,body);
    }
    /**
     * The gateway's Bot list with the server's over it: the server's row for a Bot run there (with the gateway's
     * photo and, until the live stream knows the Bot, its state), the gateway's for the rest. A Bot the server
     * runs but no longer lists is gone. If the server list can't be read, the gateway's stands.
     */
    private JSONObject withServerRoster(JSONObject box) throws Exception {
        JSONArray server;
        try{server=serverRoster();}
        catch(Exception unavailable){android.util.Log.i("GrokWearServer","roster from gateway only: "+unavailable.getClass().getSimpleName());return box;}
        JSONArray boxRows=box.optJSONArray("agents");if(boxRows==null)boxRows=new JSONArray();
        java.util.Map<String,JSONObject> byId=new java.util.HashMap<>();
        for(int i=0;i<boxRows.length();i++){JSONObject b=boxRows.optJSONObject(i);String id=rowId(b);if(!id.isEmpty())byId.put(id,b);}
        JSONArray merged=new JSONArray();java.util.Set<String> listed=new java.util.HashSet<>();long now=System.currentTimeMillis();
        for(int i=0;i<server.length();i++){
            // A server row's own "id" is an internal one; its agentId is the Bot id every other request uses.
            JSONObject row=server.optJSONObject(i);String id=GrokCore.text(row,"agentId");if(id.isEmpty())id=GrokCore.text(row,"legacyAgentId");
            if(id.isEmpty()||!listed.add(id))continue;
            JSONObject b=byId.get(id);
            if(!GrokCore.SERVER_HARNESS.equals(GrokCore.text(row,"harness"))){merged.put(b!=null?b:row);continue;}
            JSONObject mine=new JSONObject(row.toString()).put("id",id);
            if(b!=null)for(String key:GATEWAY_EXTRAS)if(b.has(key)&&!mine.has(key))mine.put(key,b.get(key));
            merged.put(SERVER_STATES.over(mine,now));
        }
        for(int i=0;i<boxRows.length();i++){JSONObject b=boxRows.optJSONObject(i);String id=rowId(b);
            if(b!=null&&!id.isEmpty()&&!listed.contains(id)&&!GrokCore.SERVER_HARNESS.equals(GrokCore.text(b,"harness")))merged.put(b);}
        if(BuildConfig.DEBUG)android.util.Log.i("GrokWearServer","roster server="+server.length()+" gateway="+boxRows.length()+" listed="+merged.length());
        return new JSONObject().put("agents",merged);
    }
    private static String rowId(JSONObject row){String id=GrokCore.text(row,"id");return id.isEmpty()?GrokCore.text(row,"agentId"):id;}
    /** ListGrokBotAgents: your own Bots, and with a team the team's Bots you added, as the official app asks. */
    private JSONArray serverRoster() throws Exception {
        JSONArray rows=new JSONArray();
        JSONObject own=serverCall("ListGrokBotAgents",new JSONObject());JSONArray list=own.optJSONArray("agents");
        if(list==null)throw new GrokCore.Failure("Grok returned an unsupported Bot list.",true);
        for(int i=0;i<Math.min(500,list.length());i++)rows.put(list.get(i));
        if(auth.core.backendContext().has("selectedTeamId")){
            JSONArray team=serverCall("ListGrokBotAgents",new JSONObject().put("includeTeamAgents",true).put("addedTeamAgentsOnly",true)).optJSONArray("agents");
            if(team!=null)for(int i=0;i<Math.min(500,team.length());i++)rows.put(team.get(i));
        }
        return rows;
    }
    /** A chat page from the server, in the gateway's shape: {id, limit, beforeSeq?, sessionId?} to {entries, nextBeforeSeq?}. */
    private JSONObject serverPage(JSONObject body) throws Exception {
        if(BuildConfig.DEBUG)android.util.Log.i("GrokWearServer","chat page via server limit="+body.optInt("limit")+" before="+body.has("beforeSeq"));
        int limit=Math.max(1,Math.min(499,body.optInt("limit",50)));
        JSONObject request=new JSONObject().put("agentId",body.getString("id")).put("limit",limit+1);
        if(!body.optString("sessionId").isEmpty())request.put("sessionId",body.getString("sessionId"));
        if(body.has("beforeSeq"))request.put("beforeSeq",Long.toString(body.getLong("beforeSeq")));
        JSONObject reply=serverCall("ListGrokBotTranscriptEntries",request);
        // The newest page of a Bot's main chat: the server stream follows that chat from here.
        if(!body.has("beforeSeq")&&body.optString("sessionId").isEmpty())ServerEvents.follow(body.getString("id"),ServerTranscripts.marks(reply));
        return ServerTranscripts.page(reply,limit);
    }
    /**
     * A message through the server (SendGrokBotUserMessage), from GrokCore's gateway body. A refusal comes back as
     * the service's words; a lost reply stays uncertain for GrokCore, which never sends it again on its own.
     */
    private JSONObject serverSend(JSONObject body) throws Exception {
        String session=body.optString("sessionId","");
        JSONObject request=new JSONObject().put("agentId",body.getString("agentId")).put("messageId",body.getString("clientNonce"))
            .put("text",body.getString("prompt")).put("sentAtMs",Long.toString(body.optLong("firstSentAtMs",System.currentTimeMillis())))
            .put("isFork",false).put("attachmentPaths",new JSONArray()).put("attachmentNames",new JSONArray())
            .put("source","GROK_BOT_CLIENT_SURFACE_MOBILE").put("renderedCardKinds",new JSONArray()).put("sendSource","GROK_BOT_SEND_SOURCE_CHAT");
        if(!session.isEmpty()){request.put("sessionId",session);if(session.startsWith("task:"))request.put("taskId",session.substring(5));}
        String language=java.util.Locale.getDefault().getLanguage();if(language.matches("[a-z]{2,3}"))request.put("language",language);
        JSONObject reply=serverCall("SendGrokBotUserMessage",request);
        if(BuildConfig.DEBUG)android.util.Log.i("GrokWearServer","send via server delivery="+reply.optString("delivery","(none)"));
        if("GROK_BOT_USER_MESSAGE_DELIVERY_REFUSED".equals(reply.optString("delivery"))){
            JSONObject refusal=reply.optJSONObject("refusal");throw sendRefused(refusal==null?"":refusal.optString("failureCode",""));
        }
        return new JSONObject().put("accepted",true);
    }
    /** Whether any listed Bot runs on Grok's server, from the saved Bot list (no network). */
    boolean hasServerBots() throws Exception {
        JSONObject state=auth.state();if(!state.optBoolean("linked"))return false;
        JSONArray rows=core.cachedRoster(state.getString("account_key"),state.getString("auth_generation")).optJSONArray("bots");
        if(rows!=null)for(int i=0;i<rows.length();i++)if(GrokCore.SERVER_HARNESS.equals(rows.getJSONObject(i).optString("harness")))return true;
        return false;
    }
    /** A saved Bot row with the live stream's state over it, for a live update; null for a Bot not listed yet. */
    JSONObject serverRow(String botId,JSONObject changed) throws Exception {
        JSONObject state=auth.state();if(!state.optBoolean("linked"))return null;
        JSONArray rows=core.cachedRoster(state.getString("account_key"),state.getString("auth_generation")).optJSONArray("bots");
        if(rows==null)return null;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!botId.equals(row.optString("id")))continue;
            if(changed!=null){JSONObject fresh=GrokCore.rosterRow(changed,0);
                if(fresh!=null)for(String key:new String[]{"name","title","avatarShape","avatarColor","voiceId","voiceSpeed","voiceLanguage"}){if(fresh.has(key))row.put(key,fresh.get(key));}}
            return SERVER_STATES.over(row,System.currentTimeMillis());}
        return null;
    }
    interface ServerSink {
        void opened(okhttp3.Call call);
        void connected(String identity);
        boolean frame(JSONObject frame) throws Exception;
    }
    /**
     * WatchGrokBotTranscripts: the server's live stream of Bot state for this account, until it ends or the sink
     * stops: Bot state, roster changes and, for the chats in cursors, their new and changed entries.
     */
    void watchServer(JSONArray cursors,ServerSink sink) throws Exception {
        JSONObject state=auth.state();
        if(!state.optBoolean("linked"))throw new GrokCore.Failure("Not signed in.",true,SIGNED_OUT);
        String identity=state.optString("account_key","")+"/"+state.optString("auth_generation","");
        JSONObject context=auth.core.backendContext();bound();
        byte[] bytes=ServerTranscripts.envelope(new JSONObject().put("cursors",cursors)
            .put("includeUnlistedAgents",context.has("selectedTeamId")).put("inlineBodyMaxBytes",65536));
        RequestBody payload=new RequestBody(){
            public MediaType contentType(){return MediaType.get("application/connect+json");}
            public long contentLength(){return bytes.length;}
            public boolean isOneShot(){return true;}
            public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.write(bytes);}
        };
        okhttp3.Call call=SERVER_EVENTS.newCall(backendRequest("WatchGrokBotTranscripts",context).header("Content-Type","application/connect+json").post(payload).build());
        sink.opened(call);
        try(Response response=call.execute()){
            if(response.code()!=200||response.body()==null){
                byte[] raw=null;try{if(response.body()!=null)raw=GrokVault.readBounded(response.body().byteStream(),16384);}catch(Exception unreadable){raw=null;}
                ServiceRefusal refusal=ServiceRefusal.parse(response.code(),response.header(ServiceRefusal.HINT_HEADER),raw);
                // A rejected token or an old client version recovers before the next attempt.
                try{auth.core.recoverRejected(context,refusal);}catch(Exception ignored){}
                GrokCore.Failure refused=new GrokCore.Failure("Live updates refused (HTTP "+response.code()+").",true,"",response.code());refused.refusal=refusal;throw refused;
            }
            sink.connected(identity);
            ServerTranscripts.Frames frames=new ServerTranscripts.Frames(response.body().byteStream());
            while(true){JSONObject frame=frames.next();if(frame==null||!sink.frame(frame))return;}
        }
    }
    // The server sends some frame at least every 35 s (the official client's stall limit), so a longer silence is a dead link.
    private static final OkHttpClient SERVER_EVENTS=HTTP.newBuilder().readTimeout(40,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS)
        .connectionPool(new okhttp3.ConnectionPool(0,1,TimeUnit.SECONDS)).build();

    private static JSONObject gateway(JSONObject config,String method,JSONObject body) throws Exception {
        if(!java.util.Arrays.asList("listAgents","setVoiceCallPresence","sendPrompt","getAgentTranscriptTail","setAgentUnread","resolveAutoReviewApproval").contains(method))throw new GrokCore.Failure("This Grok operation is not enabled.",true);
        JSONObject valid=GrokCore.validateConfig(config);
        Request.Builder request=new Request.Builder().url(valid.getString("gatewayUrl")+"/api/"+method)
            .header("Authorization","Bearer "+valid.getString("gatewayToken"));
        JSONObject headers=valid.getJSONObject("headers");Iterator<String> keys=headers.keys();
        while(keys.hasNext()){String key=keys.next();request.header(key,headers.getString(key));}
        if("listAgents".equals(method))return execute(ROSTER,request,body,true,true);
        if(!"sendPrompt".equals(method))return execute(request,body,true);
        try{return execute(request,body,false);}
        catch(GrokCore.Failure refused){
            if(!refused.rejected)throw refused;
            throw sendRefused(refused.code);
        }
    }
    /** The Connect error code token of a refused call, or "". Nothing else from the body is kept. */
    static String errorCode(byte[] received){
        if(received==null)return "";
        try{JSONObject error=new JSONObject(new String(received,StandardCharsets.UTF_8));String code=error.optString("failureCode",error.optString("code"));return code.matches("[a-z_/-]{1,60}")?code:"";}
        catch(Exception unreadable){return "";}
    }
    private static JSONObject execute(Request.Builder request,JSONObject body,boolean arrayAllowed) throws Exception {return execute(HTTP,request,body,arrayAllowed);}
    /**
     * The last gateway reply on this thread, for Report a problem: ms until the reply started, ms to read
     * it, and its size in bytes; -1 when unknown. Tells a slow server from a slow link.
     */
    static final ThreadLocal<long[]> LAST_TRANSFER=ThreadLocal.withInitial(()->new long[]{-1,-1,-1});
    private static JSONObject execute(OkHttpClient client,Request.Builder request,JSONObject body,boolean arrayAllowed) throws Exception {return execute(client,request,body,arrayAllowed,false);}
    /** roster: the Bot list, read through RosterStream instead of the 1 MB cap. */
    private static JSONObject execute(OkHttpClient client,Request.Builder request,JSONObject body,boolean arrayAllowed,boolean roster) throws Exception {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);LAST_TRANSFER.set(new long[]{-1,-1,-1});long sent=System.nanoTime();
        try {
            if(bytes.length>1024*1024)throw new GrokCore.Failure("Grok request is too large.",true);
            RequestBody payload=new RequestBody(){
                public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
                public long contentLength(){return bytes.length;}
                public boolean isOneShot(){return true;}
                public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.write(bytes);}
            };
            try(Response response=client.newCall(request.post(payload).build()).execute()) {
                // A 4xx is the service refusing; a 5xx may or may not have taken effect.
                if(response.code()!=200){
                    byte[] raw=null;
                    try{if(response.body()!=null)raw=GrokVault.readBounded(response.body().byteStream(),16384);}catch(Exception unreadable){raw=null;}
                    try{
                        String hint=response.header(ServiceRefusal.HINT_HEADER);if(hint==null)hint=response.header("trailer-"+ServiceRefusal.HINT_HEADER);
                        GrokCore.Failure refused=new GrokCore.Failure("Grok request failed (HTTP "+response.code()+").",response.code()<500,errorCode(raw),response.code());
                        refused.refusal=ServiceRefusal.parse(response.code(),hint,raw);throw refused;
                    }finally{if(raw!=null)java.util.Arrays.fill(raw,(byte)0);}
                }
                if(response.body()==null)throw new java.io.IOException();
                long started=System.nanoTime();
                long[] raw={-1};
                byte[] received=roster?RosterStream.read(response.body().byteStream(),raw,PhotoShrink.INSTANCE):GrokVault.readBounded(response.body().byteStream(),1024*1024);
                LAST_TRANSFER.set(new long[]{(started-sent)/1_000_000,(System.nanoTime()-started)/1_000_000,roster?raw[0]:received.length});
                try {String text=new String(received,StandardCharsets.UTF_8);
                    // Some operations answer with nothing (or null) on success.
                    if(text.trim().isEmpty()||text.trim().equals("null"))return new JSONObject();
                    if(arrayAllowed&&text.trim().startsWith("["))return new JSONObject().put("agents",new JSONArray(text));
                    return new JSONObject(text);
                }finally{java.util.Arrays.fill(received,(byte)0);}
            }
        }catch(GrokCore.Failure safe){throw safe;}
        // A dropped or timed-out request may have reached Grok: not a refusal.
        // The cause stays for Report a problem; only this wording is shown.
        catch(Exception failure){GrokCore.Failure lost=new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",false);lost.initCause(failure);throw lost;}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
}
