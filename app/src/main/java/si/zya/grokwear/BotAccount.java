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
    /**
     * The network changed: close idle pooled connections so the next request dials fresh instead of
     * waiting out a read timeout on a connection that died with the old network.
     */
    static void networkChanged(){try{HTTP.connectionPool().evictAll();}catch(RuntimeException ignored){}GrokAuthClient.networkChanged();NativeVoiceSession.networkChanged();}
    BotAccount(Context context) {
        auth=new GrokAuthClient(context);
        core=new GrokCore(new GrokVault(context),BotAccount::gateway,root->{
            verify(root,auth.state());JSONObject config=auth.currentConfig();verify(root,auth.state());return config;
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
            synchronized(core){synchronized(GrokAuthCore.LOCK){
                state=auth.state();
                if(!"ready".equals(state.optString("status"))||!expectedGeneration.equals(state.optString("auth_generation")))throw new GrokCore.Failure("Grok sign-in was cancelled. Start a new sign-in.",true);
                core.linkBrowser(config,state.getString("account_key"),state.getString("auth_generation"));
            }}
        }
        return state;
    }
    void cancelLogin(String attempt) throws Exception {
        synchronized(core){synchronized(GrokAuthCore.LOCK){if(auth.core.cancelAttempt(attempt)){core.unlink();}}}
    }
    void unlink() throws Exception {auth.unlink();core.unlink();continuity.clear();}
    JSONArray bots() throws Exception {bound();JSONArray result=core.bots();bound();return result;}
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
        Request.Builder request=new Request.Builder().url(GrokAuthCore.BACKEND+"/aiserver.v1.GrokBotService/"+method)
            .header("Authorization","Bearer "+context.getString("accessToken"))
            .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
            .header("x-cursor-client-version","0.71.2").header("x-sand-box-namespace","prod");
        if(context.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(context.getLong("selectedTeamId")));
        return execute(client,request,body,false);
    }
    /** The finished call's own Bot, not the current selection: the user may switch Bots right after hanging up. */
    JSONObject handoffTool(JSONObject binding,JSONObject body) throws Exception {
        if(!CallHandoff.TOOL.equals(body.optString("name")))throw new GrokCore.Failure("Only the end-of-call handoff is enabled.",true);
        verifyHistoryBinding(binding,false);
        if(!binding.optString("agentId").equals(body.optString("agentId")))throw new GrokCore.Failure("Grok Bot changed. Nothing was sent.",true);
        JSONObject context=auth.core.backendContext();verifyHistoryBinding(binding,false);
        Request.Builder request=new Request.Builder().url(GrokAuthCore.BACKEND+"/aiserver.v1.GrokBotService/VoiceCallHarnessTool")
            .header("Authorization","Bearer "+context.getString("accessToken"))
            .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
            .header("x-cursor-client-version","0.71.2").header("x-sand-box-namespace","prod");
        if(context.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(context.getLong("selectedTeamId")));
        return execute(request,body,false);
    }
    /** A call-scoped lease, created after normal renewal, never used for later tool calls. */
    NativeVoiceSession.Backend startupBackend(JSONObject requestedBinding) throws Exception {
        final JSONObject binding=new JSONObject(requestedBinding.toString());
        verifyHistoryBinding(binding,true);
        JSONObject credentials=auth.core.backendContext();
        verifyHistoryBinding(binding,true);
        return (method,body)->{
            if(!method.equals("VoiceCallHarnessSession")&&!method.equals("MintSandVoiceCallSecret"))
                throw new GrokCore.Failure("This startup operation is not enabled.",true);
            verifyHistoryBinding(binding,true);
            if(method.equals("VoiceCallHarnessSession")&&!binding.getString("agentId").equals(body.optString("agentId")))
                throw new GrokCore.Failure("Grok Bot changed. Start the call again.",true);
            Request.Builder request=new Request.Builder().url(GrokAuthCore.BACKEND+"/aiserver.v1.GrokBotService/"+method)
                .header("Authorization","Bearer "+credentials.getString("accessToken"))
                .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
                .header("x-cursor-client-version","0.71.2").header("x-sand-box-namespace","prod");
            if(credentials.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(credentials.getLong("selectedTeamId")));
            JSONObject result=execute(request,body,false);verifyHistoryBinding(binding,true);return result;
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
            .header("x-cursor-client-version","0.72.0").header("x-sand-box-namespace","prod");
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
        JSONObject config=GrokCore.validateConfig(auth.currentConfig());verifyHistoryBinding(binding,selectionRequired);
        Request.Builder request=new Request.Builder().url(config.getString("gatewayUrl")+"/api/"+method)
            .header("Authorization","Bearer "+config.getString("gatewayToken"));
        JSONObject headers=config.getJSONObject("headers");Iterator<String> keys=headers.keys();
        while(keys.hasNext()){String key=keys.next();request.header(key,headers.getString(key));}
        long remaining=binding.optLong("deadlineMs",System.currentTimeMillis()+30000)-System.currentTimeMillis();if(remaining<=0)throw new GrokCore.Failure("Shared history took too long to load.",true);
        Object result;
        try{result=historyWire(request,body,method,Math.min(30000,remaining));}
        catch(GrokCore.Failure interrupted){
            // Reads are idempotent; one fresh attempt within the same deadline. Writes are never replayed.
            long left=binding.optLong("deadlineMs",System.currentTimeMillis()+30000)-System.currentTimeMillis();
            if(write||!INTERRUPTED.equals(interrupted.getMessage())||left<3000)throw interrupted;
            verifyHistoryBinding(binding,selectionRequired);result=historyWire(request,body,method,Math.min(30000,left));
        }
        verifyHistoryBinding(binding,selectionRequired);return result;
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
                if(response.code()!=200&&!(method.equals("recordVoiceCall")&&response.code()==204))throw new GrokCore.Failure("History sync failed (HTTP "+response.code()+").",true);
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
    private static JSONObject gateway(JSONObject config,String method,JSONObject body) throws Exception {
        if(!java.util.Arrays.asList("listAgents","setVoiceCallPresence").contains(method))throw new GrokCore.Failure("This Grok operation is not enabled.",true);
        JSONObject valid=GrokCore.validateConfig(config);
        Request.Builder request=new Request.Builder().url(valid.getString("gatewayUrl")+"/api/"+method)
            .header("Authorization","Bearer "+valid.getString("gatewayToken"));
        JSONObject headers=valid.getJSONObject("headers");Iterator<String> keys=headers.keys();
        while(keys.hasNext()){String key=keys.next();request.header(key,headers.getString(key));}
        return execute(request,body,true);
    }
    /** The Connect error code token of a refused call, or "". Nothing else from the body is kept. */
    private static String errorCode(Response response){
        try{if(response.body()==null)return "";byte[] received=GrokVault.readBounded(response.body().byteStream(),16384);
            try{String code=new JSONObject(new String(received,StandardCharsets.UTF_8)).optString("code");return code.matches("[a-z_]{1,40}")?code:"";}
            finally{java.util.Arrays.fill(received,(byte)0);}
        }catch(Exception unreadable){return "";}
    }
    private static JSONObject execute(Request.Builder request,JSONObject body,boolean arrayAllowed) throws Exception {return execute(HTTP,request,body,arrayAllowed);}
    private static JSONObject execute(OkHttpClient client,Request.Builder request,JSONObject body,boolean arrayAllowed) throws Exception {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if(bytes.length>1024*1024)throw new GrokCore.Failure("Grok request is too large.",true);
            RequestBody payload=new RequestBody(){
                public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
                public long contentLength(){return bytes.length;}
                public boolean isOneShot(){return true;}
                public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.write(bytes);}
            };
            try(Response response=client.newCall(request.post(payload).build()).execute()) {
                if(response.code()!=200)throw new GrokCore.Failure("Grok request failed (HTTP "+response.code()+").",true,errorCode(response));
                if(response.body()==null)throw new java.io.IOException();
                byte[] received=GrokVault.readBounded(response.body().byteStream(),1024*1024);
                try {String text=new String(received,StandardCharsets.UTF_8);
                    if(arrayAllowed&&text.trim().startsWith("["))return new JSONObject().put("agents",new JSONArray(text));
                    return new JSONObject(text);
                }finally{java.util.Arrays.fill(received,(byte)0);}
            }
        }catch(GrokCore.Failure safe){throw safe;}
        catch(Exception failure){throw new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",true);}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
}
