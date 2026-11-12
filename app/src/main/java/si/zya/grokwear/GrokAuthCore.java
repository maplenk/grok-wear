package si.zya.grokwear;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Experimental native Grok Bot login, with a watch-owned verifier and refresh token.
 * Contracts were inspected in Grok Bot 0.71.2 (2026-10-20), not an OAuth API promised
 * to third-party apps. A lost rotating response is never retried automatically.
 */
final class GrokAuthCore {
    interface Store { JSONObject read() throws Exception; void write(JSONObject value) throws Exception; }
    interface Clock { long now(); }
    interface Transport { Reply request(String operation, JSONObject input) throws Exception; }
    static final class Reply {
        final int status;
        final JSONObject body;
        /** Safe details of a refusal (status, Connect code, failure hint, scope reason); null on success. */
        final ServiceRefusal refusal;
        Reply(int status, JSONObject body) { this(status, body, null); }
        Reply(int status, JSONObject body, ServiceRefusal refusal) {
            this.status=status; this.body=body==null?new JSONObject():body;
            this.refusal=status==200?null:refusal!=null?refusal:new ServiceRefusal(status,"","","");
        }
    }
    /** One short line for Report a problem's log; never credentials or account data. */
    interface Log { void line(String text); }
    /** ACCESS: DashboardService/GetSandAccessStatus, read-only, asked when the backend refuses the account. */
    static final String POLL="poll", REFRESH="refresh", BOOTSTRAP="bootstrap", ACCESS="access";
    static final long LOGIN_MS=5*60_000L, POLL_MS=3_000L, REFRESH_LEAD_MS=300_000L, CONFIG_CACHE_MS=300_000L;
    static final String WEBSITE="https://cursor.com", BACKEND="https://api2.cursor.sh";
    /**
     * The Grok Bot desktop version Grok Wear's backend requests match (x-cursor-client-version). The backend
     * refuses versions it no longer supports with SAND_CLIENT_UPDATE_REQUIRED (0.71.2 was refused from
     * 2026-11-11, about three weeks after its release), so when that happens the watch asks the desktop app's
     * own update feed (VERSION) for the newest version and uses that instead.
     */
    static final String CLIENT_VERSION="1.0.1";
    /** VERSION: Grok Bot desktop's update check, read-only and without credentials. Reply body: {"version":"x.y.z"}. */
    static final String VERSION="version";
    private static volatile String clientVersion=CLIENT_VERSION;
    /** The version requests send: CLIENT_VERSION, or a newer one from Grok's update feed. */
    static String clientVersion(){return clientVersion;}
    /** Moves to version when it's a plain x.y.z newer than the one in use; never back. */
    static synchronized boolean useClientVersion(String version){
        if(!newer(version,clientVersion))return false;
        clientVersion=version;return true;
    }
    static boolean newer(String version,String than){
        String plain="\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}";
        if(version==null||than==null||!version.matches(plain)||!than.matches(plain))return false;
        String[] a=version.split("\\."),b=than.split("\\.");
        for(int i=0;i<3;i++){long x=Long.parseLong(a[i]),y=Long.parseLong(b[i]);if(x!=y)return x>y;}
        return false;
    }
    // Public client identifier shipped in the native app, not a client secret.
    static final String CLIENT_ID="KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB";
    static final Object LOCK=new Object();
    // Only live, nonrotating bootstrap requests may be joined. Rotation uncertainty
    // remains durable even when there is no corresponding request in this process.
    private static final Map<String,BootstrapFlight> BOOTSTRAPS=new HashMap<>();
    // Token refreshes in flight in this process. Other callers wait for one instead of failing; a refresh
    // without a live owner (the process died mid-request) still blocks, since its token may be spent.
    private static final Map<String,BootstrapFlight> REFRESHES=new HashMap<>();
    private static final class BootstrapFlight {
        final Thread owner=Thread.currentThread();
        boolean finished,succeeded;
        /** A refusal that explains itself (a failure hint), for callers who waited on this request. */
        GrokCore.Failure failure;
    }
    private final Store store;
    private final Transport transport;
    private final Clock clock;
    private final Log log;
    GrokAuthCore(Store store, Transport transport, Clock clock) { this(store,transport,clock,text->{}); }
    GrokAuthCore(Store store, Transport transport, Clock clock, Log log) { this.store=store; this.transport=transport; this.clock=clock; this.log=log; }
    /**
     * Like the desktop client's access hold: once GetSandAccessStatus says this sign-in can't use Grok Bot,
     * background callers (live updates, reply alerts) get that answer for 15 minutes instead of asking again.
     * Try again and a new sign-in release it.
     */
    static final long ACCESS_HOLD_MS=15*60_000L;
    private static final class Hold { final String access; final long until; final GrokCore.Failure failure;
        Hold(String access,long until,GrokCore.Failure failure){this.access=access;this.until=until;this.failure=failure;} }
    private static volatile Hold HOLD;
    static void releaseHold(){HOLD=null;}
    /** Tests only: forgets this process's access hold, forced renewal and newer client version. */
    static void forgetRecoveries(){HOLD=null;FORCED_TO="";clientVersion=CLIENT_VERSION;}
    /** Digest of the token a forced renewal produced: if Grok refuses that one too, renewing again won't help. */
    private static volatile String FORCED_TO="";

    JSONObject begin() throws Exception {
        synchronized(LOCK) {
            JSONObject root=store.read();
            if(root.has("tokens"))throw failure("Unlink the existing Grok browser sign-in before signing in again.");
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String uuid=UUID.randomUUID().toString();long now=clock.now();
            JSONObject login=new JSONObject().put("uuid",uuid).put("verifier",verifier).put("challenge",challenge)
                    .put("started_at_ms",now).put("expires_at_ms",now+LOGIN_MS).put("next_poll_ms",now);
            String attempt=UUID.randomUUID().toString();login.put("attempt",attempt);
            root=new JSONObject().put("epoch",attempt).put("login",login);
            store.write(root); // Never display a challenge that was not saved privately first.
            return loginView(login);
        }
    }

    JSONObject pendingLogin() throws Exception {
        synchronized(LOCK) {
            JSONObject root=store.read();JSONObject login=root.optJSONObject("login");
            if(login==null||root.has("operation")||expired(login))return new JSONObject();
            return loginView(login);
        }
    }

    JSONObject poll() throws Exception {
        JSONObject request;String epoch,id;
        synchronized(LOCK) {
            JSONObject root=pendingRoot();
            JSONObject login=root.optJSONObject("login");
            if(login==null)return stateOf(root);
            if(expired(login)){root.remove("login");store.write(root);throw failure("Grok sign-in expired. Start a new sign-in.");}
            if(clock.now()<login.optLong("next_poll_ms"))return stateOf(root);
            request=new JSONObject().put("uuid",login.getString("uuid")).put("verifier",login.getString("verifier"));
            epoch=root.getString("epoch");id=mark(root,POLL);
        }
        Reply response=invoke(POLL,request);
        synchronized(LOCK) {
            JSONObject root=active(epoch,id);
            JSONObject login=root.getJSONObject("login");
            if(expired(login)) {root.remove("login");root.remove("operation");store.write(root);throw failure("Grok sign-in expired. Start a new sign-in.");}
            if(response.status==404) {
                root.remove("operation");login.put("next_poll_ms",clock.now()+POLL_MS);store.write(root);return stateOf(root);
            }
            if(response.status!=200) {
                root.remove("operation");root.remove("login");store.write(root);
                throw failure(response.status==403?"Grok sign-in was refused. Use an account allowed by your organization.":"Grok sign-in failed (HTTP "+response.status+"). Start a new sign-in.");
            }
            JSONObject tokens=readLoginTokens(response.body);
            root.put("tokens",tokens).put("generation",UUID.randomUUID().toString());
            root.remove("login");root.remove("operation");store.write(root);
            return stateOf(root);
        }
    }

    /** Local cancellation never revokes a previously saved session or reconstructs a cancelled login. */
    void cancel() throws Exception {
        synchronized(LOCK) {
            JSONObject root=store.read();
            if(!root.has("login"))return;
            root.put("epoch",UUID.randomUUID().toString());root.remove("login");root.remove("operation");store.write(root);
        }
    }

    /** Cancel exactly the displayed login, even if its successful poll just completed. */
    boolean cancelAttempt(String attempt) throws Exception {
        synchronized(LOCK) {
            JSONObject root=store.read();
            if(attempt==null||attempt.isEmpty()||!attempt.equals(root.optString("epoch")))return false;
            store.write(new JSONObject().put("epoch",UUID.randomUUID().toString()));
            return true;
        }
    }

    /** A durable tombstone makes all in-flight responses unable to restore deleted credentials. */
    void unlink() throws Exception {
        synchronized(LOCK) {store.write(new JSONObject().put("epoch",UUID.randomUUID().toString()));}
    }

    JSONObject state() throws Exception {synchronized(LOCK){return stateOf(store.read());}}

    /**
     * Called before a new request, never in response to an uncertain prompt submission.
     * A refused EnsureSandBox is handled like Grok Bot Android 1.18.0 (postWithScopeRecovery): a stale team is
     * dropped and asked again without it, any other 401 renews the sign-in once and asks again, and a refusal
     * that is still about access is explained by GetSandAccessStatus. A client version Grok calls too old is
     * replaced by the newest one from Grok's update feed and asked again.
     */
    JSONObject currentConfig() throws Exception {
        renewIfDue(false,null);
        boolean teamCleared=false,renewed=false,versionChecked=false;String recovered="";
        while(true){
            Attempt attempt=bootstrapOnce();
            if(attempt.config!=null){
                if(!recovered.isEmpty())log.line("Bot sandbox start: ok after "+recovered);
                return attempt.config;
            }
            ServiceRefusal refused=attempt.refusal;
            if(refused.updateRequired()&&!versionChecked){
                versionChecked=true;if(newerVersion()){recovered="updating the client version";continue;}
            }
            if(refused.staleTeam()&&!teamCleared&&attempt.team>0){
                clearTeam(attempt.team);teamCleared=true;recovered="dropping a stale team";log.line("Bot sandbox start: stale team, asking again without it");continue;
            }
            // A failure hint says what's wrong, and it isn't the token: renewing it wouldn't help.
            if(refused.status==401&&refused.hint.isEmpty()&&!renewed&&!digest(attempt.access).equals(FORCED_TO)){
                recovered="renewing sign-in";
                renewIfDue(true,attempt.access);renewed=true;continue;
            }
            throw refusalFailure(refused,"Grok refused this sign-in. No message was sent.");
        }
    }
    private static final class Attempt {
        JSONObject config;ServiceRefusal refusal;String access="";long team;
    }
    /** One EnsureSandBox request, or the saved config while it's fresh. A refusal is returned, not thrown. */
    private Attempt bootstrapOnce() throws Exception {
        Attempt attempt=new Attempt();
        JSONObject request;String epoch,id;BootstrapFlight flight;
        synchronized(LOCK) {
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            JSONObject config=root.optJSONObject("config");long now=clock.now(),saved=root.optLong("config_saved_ms");
            if(config!=null&&saved<=now&&now-saved<CONFIG_CACHE_MS&&!gatewayDue(config,now)){attempt.config=copy(config);return attempt;}
            Hold held=HOLD;
            if(held!=null&&now<held.until&&held.access.equals(digest(tokens.getString("access"))))throw copyFailure(held.failure);
            request=new JSONObject().put("accessToken",tokens.getString("access"));
            if(tokens.has("team"))request.put("selectedTeamId",tokens.getLong("team"));
            attempt.access=tokens.getString("access");attempt.team=tokens.optLong("team",0);
            epoch=root.getString("epoch");id=mark(root,BOOTSTRAP);
            flight=new BootstrapFlight();BOOTSTRAPS.put(id,flight);
        }
        try {
        Reply response;
        try {response=invoke(BOOTSTRAP,request);}
        catch(Exception e) {clearNonRotating(epoch,id);throw e;}
        synchronized(LOCK) {
            JSONObject root=active(epoch,id);
            // This operation is read/bootstrap, not a rotating-token request: it may be asked again.
            root.remove("operation");store.write(root);
            if(response.status!=200){attempt.refusal=response.refusal;
                // Callers waiting on this request get a refusal that explains itself; one this caller may still
                // recover from (a client version to update) reads to them as still connecting.
                if(!response.refusal.hintMessage().isEmpty()&&!response.refusal.updateRequired())flight.failure=hintFailure(response.refusal);
                return attempt;}
            JSONObject config=configFrom(response.body);
            root.put("config",config).put("config_saved_ms",clock.now());store.write(root);
            flight.succeeded=true;
            attempt.config=copy(config);return attempt;
        }
        } finally {
            synchronized(LOCK) {flight.finished=true;BOOTSTRAPS.remove(id);LOCK.notifyAll();}
        }
    }
    /** Drops the team chosen at sign-in after Grok called it stale, unless it already changed. */
    private void clearTeam(long team) throws Exception {
        synchronized(LOCK) {
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            if(tokens.optLong("team",0)!=team)return;
            tokens.remove("team");root.put("tokens",tokens);root.remove("config");root.remove("config_saved_ms");store.write(root);
        }
    }

    static final int NOT_RECOVERED=0,RENEWED=1,TEAM_DROPPED=2,VERSION_UPDATED=3;
    /**
     * For other backend requests refused with a 401 (call setup and tools): update a client version Grok calls
     * too old, drop a stale team, or renew the sign-in once, so the caller can ask once more. NOT_RECOVERED when
     * none applies.
     */
    int recoverRejected(JSONObject used,ServiceRefusal refused) throws Exception {
        if(refused==null)return NOT_RECOVERED;
        if(refused.updateRequired())return newerVersion()?VERSION_UPDATED:NOT_RECOVERED;
        if(refused.status!=401)return NOT_RECOVERED;
        if(refused.staleTeam()&&used.optLong("selectedTeamId",0)>0){clearTeam(used.getLong("selectedTeamId"));log.line("Grok: stale team dropped");return TEAM_DROPPED;}
        if(!refused.hint.isEmpty())return NOT_RECOVERED;
        String access=used.optString("accessToken","");
        if(access.isEmpty()||digest(access).equals(FORCED_TO))return NOT_RECOVERED;
        renewIfDue(true,access);return RENEWED;
    }

    /**
     * The Failure for a refusal: the failure hint's own wording, GetSandAccessStatus's explanation when the
     * refusal is about access (with where to fix it), or the plain fallback.
     */
    GrokCore.Failure refusalFailure(ServiceRefusal refused,String fallback) throws Exception {
        if(!refused.hintMessage().isEmpty())return hintFailure(refused);
        GrokCore.Failure failure;
        {
            SandAccess access=refused.accessRefusal()?accessStatus():null;
            if(access!=null&&!access.granted()){
                failure=new GrokCore.Failure(access.message(),true,access.reason.equals("unspecified")?access.state:access.reason,refused.status,access.link());
                JSONObject tokens;synchronized(LOCK){tokens=needTokens(tokensRoot());}
                HOLD=new Hold(digest(tokens.getString("access")),clock.now()+ACCESS_HOLD_MS,failure);
            }else failure=new GrokCore.Failure(refused.status==401||refused.status==403?fallback
                    :"Could not connect to Grok (HTTP "+refused.status+"). No message was sent.",true,refused.failureCode(),refused.status);
        }
        failure.refusal=refused;return failure;
    }
    /** A refusal whose failure hint says what's wrong, in the hint's own wording. */
    private static GrokCore.Failure hintFailure(ServiceRefusal refused){
        GrokCore.Failure failure=new GrokCore.Failure(refused.hintMessage(),true,refused.failureCode(),refused.status);
        failure.refusal=refused;return failure;
    }
    /**
     * After Grok called this client version too old: asks Grok's update feed for the newest Grok Bot version and
     * uses it when it's newer. Logs one line either way.
     */
    private boolean newerVersion(){
        String before=clientVersion();Reply reply;
        try{reply=transport.request(VERSION,new JSONObject().put("current",before));}
        catch(Exception unavailable){log.line("Client version check: failed");return false;}
        String latest=reply.status==200?reply.body.optString("version",""):"";
        if(useClientVersion(latest)){log.line("Client version: "+before+" to "+latest+" from Grok's update feed");return true;}
        log.line("Client version check: "+(reply.status==200||reply.status==204?"nothing newer than "+before:"HTTP "+reply.status));
        return false;
    }
    /** GetSandAccessStatus with the current sign-in, or null when it can't be read. Logs one line. */
    SandAccess accessStatus() throws Exception {
        JSONObject request;
        synchronized(LOCK) {
            JSONObject tokens=needTokens(tokensRoot());
            request=new JSONObject().put("accessToken",tokens.getString("access"));
            if(tokens.has("team"))request.put("selectedTeamId",tokens.getLong("team"));
        }
        Reply response;
        try{response=transport.request(ACCESS,request);}catch(Exception unreadable){return null;}
        if(response.status!=200)return null;
        SandAccess access=SandAccess.parse(response.body);log.line(access.logLine());return access;
    }
    private static GrokCore.Failure copyFailure(GrokCore.Failure held){
        GrokCore.Failure again=new GrokCore.Failure(held.getMessage(),held.rejected,held.code,held.status,held.link);again.refusal=held.refusal;return again;
    }
    private static String digest(String value){try{return hexDigest(value==null?"":value);}catch(Exception unavailable){return "";}}

    /** Drops the saved gateway config the gateway refused, unless a newer one already replaced it. */
    void forgetConfig(String gatewayToken) throws Exception {
        synchronized(LOCK) {
            JSONObject root=store.read(),config=root.optJSONObject("config");
            if(config==null||gatewayToken==null||!gatewayToken.equals(config.optString("gatewayToken")))return;
            root.remove("config");root.remove("config_saved_ms");store.write(root);
        }
    }

    /** Memory-only backend credential context; never expose this to UI or persist elsewhere. */
    JSONObject backendContext() throws Exception {
        renewIfDue();
        synchronized(LOCK) {
            JSONObject root=tokensRoot();JSONObject tokens=needTokens(root);
            JSONObject context=new JSONObject().put("accessToken",tokens.getString("access"));
            if(tokens.has("team"))context.put("selectedTeamId",tokens.getLong("team"));
            return context;
        }
    }

    private void renewIfDue() throws Exception {renewIfDue(false,null);}
    /**
     * force: Grok refused rejectedAccess before it was due (Android's refreshAfterRejectedBearer). Renews once,
     * unless another caller already replaced that token. The rotation-safety rules are the same either way.
     */
    private void renewIfDue(boolean force,String rejectedAccess) throws Exception {
        JSONObject request;String epoch,id,subject;long team;BootstrapFlight flight;
        synchronized(LOCK) {
            // Tokens that aren't due need no renewal, so no wait for a gateway bootstrap either.
            if(!force&&needTokens(tokensRoot()).getLong("expires_at_ms")-clock.now()>REFRESH_LEAD_MS)return;
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            if(force){if(!tokens.getString("access").equals(rejectedAccess))return;}
            else if(tokens.getLong("expires_at_ms")-clock.now()>REFRESH_LEAD_MS)return;
            request=new JSONObject().put("refreshToken",tokens.getString("refresh"));
            subject=tokens.getString("subject");team=tokens.optLong("team",0);
            epoch=root.getString("epoch");id=mark(root,REFRESH);
            flight=new BootstrapFlight();REFRESHES.put(id,flight);
        }
        try{renew(request,epoch,id,subject,team);flight.succeeded=true;
            if(force){synchronized(LOCK){FORCED_TO=digest(needTokens(tokensRoot()).getString("access"));}log.line("Sign-in renewal: after a refusal, ok");}}
        finally{synchronized(LOCK){flight.finished=true;REFRESHES.remove(id);LOCK.notifyAll();}}
    }
    private void renew(JSONObject request,String epoch,String id,String subject,long team) throws Exception {
        Reply response=invoke(REFRESH,request);
        synchronized(LOCK) {
            JSONObject root=active(epoch,id);
            // Keep the uncertainty marker on every rejected or malformed rotation. Never
            // replay a possibly consumed refresh token, including after process death.
            if(response.status!=200||response.body.optBoolean("shouldLogout"))
                throw failure("Grok could not renew this sign-in. Relink Grok; renewal will not be retried.");
            String access=secret(response.body,"access_token"),refresh=response.body.has("refresh_token")
                    ?secret(response.body,"refresh_token"):request.getString("refreshToken");
            JSONObject tokens=tokenRecord(access,refresh,team);
            if(!subject.equals(tokens.getString("subject")))throw failure("Grok returned a different account. Relink before continuing.");
            // The login generation binds the inbox to this explicit sign-in. A normal
            // token rotation keeps that generation and therefore keeps the same inbox.
            root.put("tokens",tokens);
            root.remove("operation");root.remove("config");root.remove("config_saved_ms");store.write(root);
        }
    }

    private JSONObject readLoginTokens(JSONObject body) throws Exception {
        long team=0;
        if(body.has("selectedTeamId")&&!body.isNull("selectedTeamId")) {
            Object raw=body.opt("selectedTeamId");
            if(!(raw instanceof Number)||((Number)raw).doubleValue()!=((Number)raw).longValue()
                    ||((Number)raw).longValue()<0||((Number)raw).longValue()>9_007_199_254_740_991L)
                throw failure("Grok returned an unsupported account selection. Start a new sign-in.");
            team=((Number)raw).longValue();
        }
        return tokenRecord(secret(body,"accessToken"),secret(body,"refreshToken"),team);
    }

    private JSONObject tokenRecord(String access,String refresh,long team) throws Exception {
        JSONObject claims=claims(access);
        String subject=GrokCore.required(claims,"sub",1024);long expires=expiry(claims);
        if(expires<=clock.now())throw failure("Grok returned an expired sign-in. Start a new sign-in.");
        JSONObject result=new JSONObject().put("access",access).put("refresh",refresh).put("subject",subject).put("expires_at_ms",expires);
        if(team>0)result.put("team",team);return result;
    }

    static JSONObject configFrom(JSONObject body) throws Exception {
        String url=alias(body,"gatewayUrl","gateway_url"),token=alias(body,"gatewayToken","gateway_token");
        JSONObject headers=body.optJSONObject("gatewayHeaders");if(headers==null)headers=body.optJSONObject("gateway_headers");
        if(headers==null||headers.length()==0) {
            headers=new JSONObject();String network="";
            for(String key:new String[]{"anyrunNetworkToken","anyrun_network_token","networkToken","network_token"}) {
                if(!body.has(key)||body.isNull(key))continue;
                Object raw=body.opt(key);
                if(raw instanceof String&&((String)raw).isEmpty())continue;
                network=secret(body,key);break;
            }
            if(!network.isEmpty())headers.put("x-anyrun-network-token",network);
        }
        return GrokCore.validateConfig(new JSONObject().put("gatewayUrl",url).put("gatewayToken",token).put("headers",headers));
    }

    private JSONObject stateOf(JSONObject root) throws Exception {
        JSONObject tokens=root.optJSONObject("tokens"),login=root.optJSONObject("login"),operation=root.optJSONObject("operation");
        String kind=operation==null?"":operation.optString("kind");
        // A renewal another thread of this process is running is routine, not uncertain.
        boolean renewing=REFRESH.equals(kind)&&REFRESHES.containsKey(operation.optString("id"));
        String status=renewing?(tokens!=null?"ready":"unlinked"):REFRESH.equals(kind)?"renewal_uncertain":POLL.equals(kind)?"login_uncertain":BOOTSTRAP.equals(kind)?"connecting":
                login!=null&&!expired(login)?"waiting":tokens!=null?"ready":"unlinked";
        JSONObject view=new JSONObject().put("status",status).put("linked",tokens!=null)
                .put("refresh_pending",REFRESH.equals(kind)).put("waiting",status.equals("waiting"));
        if(tokens!=null) {
            view.put("expires_at_ms",tokens.getLong("expires_at_ms"))
                    .put("refresh_due",tokens.getLong("expires_at_ms")-clock.now()<=REFRESH_LEAD_MS)
                    .put("account_key",hexDigest(tokens.getString("subject")+"\n"+tokens.optLong("team",0)))
                    .put("auth_generation",root.optString("generation")).put("author_key",hexDigest(tokens.getString("subject")))
                    .put("team",tokens.optLong("team",0)>0);
        }
        return view;
    }

    private String mark(JSONObject root,String kind) throws Exception {
        String id=UUID.randomUUID().toString();
        root.put("operation",new JSONObject().put("id",id).put("kind",kind));store.write(root);return id;
    }
    private JSONObject active(String epoch,String id) throws Exception {
        JSONObject root=store.read();JSONObject op=root.optJSONObject("operation");
        if(!epoch.equals(root.optString("epoch"))||op==null||!id.equals(op.optString("id")))
            throw failure("Grok sign-in was cancelled or changed. Nothing was linked.");
        return root;
    }
    private void clearNonRotating(String epoch,String id) throws Exception {
        synchronized(LOCK) {JSONObject root=active(epoch,id);root.remove("operation");store.write(root);}
    }
    /**
     * Called under LOCK, for reading tokens only. A gateway bootstrap this process is running doesn't
     * change them, so a call's backend steps neither wait for it (it can take a while when the Bot's
     * sandbox is starting) nor fail with it. Anything else goes through pendingRoot.
     */
    private JSONObject tokensRoot() throws Exception {
        JSONObject root=store.read(),op=root.optJSONObject("operation");
        if(op!=null&&BOOTSTRAP.equals(op.optString("kind"))&&BOOTSTRAPS.containsKey(op.optString("id")))return root;
        return pendingRoot();
    }
    /** Called under LOCK. A persisted bootstrap without a live owner is safe to repeat. */
    private JSONObject pendingRoot() throws Exception {
        JSONObject root=store.read(),op=root.optJSONObject("operation");
        if(!root.has("operation"))return root;
        BootstrapFlight renewal=op==null||!REFRESH.equals(op.optString("kind"))?null:REFRESHES.get(op.optString("id"));
        if(renewal!=null&&renewal.owner!=Thread.currentThread()) {
            // Another caller in this process is renewing: wait for its result rather than refuse.
            long deadline=System.nanoTime()+30_000_000_000L;
            while(!renewal.finished) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw failure("Grok is still renewing this sign-in. Try again shortly. No message was sent.");
                try {LOCK.wait(Math.max(1,remaining/1_000_000L));}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw failure("Grok is still renewing this sign-in. Try again shortly. No message was sent.");}
            }
            return pendingRoot(); // Renewed, or the marker stays and the next read refuses as before.
        }
        if(op==null||!BOOTSTRAP.equals(op.optString("kind")))
            throw failure("Grok sign-in has an unfinished request. Relink before continuing; it will not be retried.");
        BootstrapFlight flight=BOOTSTRAPS.get(op.optString("id"));
        if(flight==null) {
            root.remove("operation");store.write(root);return root;
        }
        if(flight.owner==Thread.currentThread())throw bootstrapBusy();
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!flight.finished) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw bootstrapBusy();
            try {LOCK.wait(Math.max(1,remaining/1_000_000L));}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw bootstrapBusy();}
        }
        // A joining caller must not replay a failed bootstrap automatically.
        if(!flight.succeeded)throw flight.failure!=null?copyFailure(flight.failure):bootstrapBusy();
        return pendingRoot();
    }
    private static GrokCore.Failure bootstrapBusy() {
        return failure("Grok is still connecting or could not finish connecting. Try again shortly. No message was sent.");
    }
    private static JSONObject needTokens(JSONObject root) throws Exception {
        JSONObject tokens=root.optJSONObject("tokens");if(tokens==null)throw failure("Sign in to Grok first.");return tokens;
    }
    private Reply invoke(String operation,JSONObject input) throws Exception {
        try{return transport.request(operation,input);}catch(Exception e){throw failure("Grok connection was interrupted. Check sign-in before trying again.");}
    }
    private boolean expired(JSONObject login) {long now=clock.now();return now<login.optLong("started_at_ms")||now>=login.optLong("expires_at_ms");}
    private static JSONObject loginView(JSONObject login) throws Exception {
        return new JSONObject().put("login_url",WEBSITE+"/loginDeepControl?challenge="+login.getString("challenge")
                +"&uuid="+login.getString("uuid")+"&mode=login&redirectTarget=sand&supportsSelectedTeamLogin=true")
                .put("expires_at_ms",login.getLong("expires_at_ms")).put("login_attempt",login.optString("attempt"));
    }
    private static String secret(JSONObject body,String key) throws Exception {
        String value=GrokCore.required(body,key,32768);
        if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw failure("Grok returned an invalid credential.");return value;
    }
    private static String alias(JSONObject body,String a,String b)throws Exception {return secret(body,body.has(a)?a:b);}
    private static JSONObject claims(String token) throws Exception {
        try {
            String[] parts=token.split("\\.");if(parts.length!=3||parts[1].length()>32768)throw new Exception();
            return new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
        }catch(Exception e){throw failure("Grok returned an unsupported sign-in. Start a new sign-in.");}
    }
    private static long expiry(JSONObject claims) throws Exception {
        Object value=claims.opt("exp");
        if(!(value instanceof Number))throw failure("Grok returned an unknown sign-in expiry.");
        double n=((Number)value).doubleValue();
        if(!Double.isFinite(n)||n<=0||n>Long.MAX_VALUE/1000.0)throw failure("Grok returned an invalid sign-in expiry.");return (long)(n*1000);
    }
    private static boolean gatewayDue(JSONObject config,long now) {
        try{return expiry(claims(config.getString("gatewayToken")))-now<=REFRESH_LEAD_MS;}catch(Exception ignored){return false;}
    }
    private static String hexDigest(String value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static JSONObject copy(JSONObject value) throws Exception{return new JSONObject(value.toString());}
    private static GrokCore.Failure failure(String message){return new GrokCore.Failure(message,true);}
}
