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
        Reply(int status, JSONObject body) { this.status=status; this.body=body==null?new JSONObject():body; }
    }
    static final String POLL="poll", REFRESH="refresh", BOOTSTRAP="bootstrap";
    static final long LOGIN_MS=5*60_000L, POLL_MS=3_000L, REFRESH_LEAD_MS=300_000L, CONFIG_CACHE_MS=300_000L;
    static final String WEBSITE="https://cursor.com", BACKEND="https://api2.cursor.sh";
    // Public client identifier shipped in the native app, not a client secret.
    static final String CLIENT_ID="KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB";
    static final Object LOCK=new Object();
    // Only live, nonrotating bootstrap requests may be joined. Rotation uncertainty
    // remains durable even when there is no corresponding request in this process.
    private static final Map<String,BootstrapFlight> BOOTSTRAPS=new HashMap<>();
    private static final class BootstrapFlight {
        final Thread owner=Thread.currentThread();
        boolean finished,succeeded;
    }
    private final Store store;
    private final Transport transport;
    private final Clock clock;
    GrokAuthCore(Store store, Transport transport, Clock clock) { this.store=store; this.transport=transport; this.clock=clock; }

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

    /** Called before a new request, never in response to an uncertain prompt submission. */
    JSONObject currentConfig() throws Exception {
        renewIfDue();
        JSONObject request;String epoch,id;BootstrapFlight flight;
        synchronized(LOCK) {
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            JSONObject config=root.optJSONObject("config");long now=clock.now(),saved=root.optLong("config_saved_ms");
            if(config!=null&&saved<=now&&now-saved<CONFIG_CACHE_MS&&!gatewayDue(config,now))return copy(config);
            request=new JSONObject().put("accessToken",tokens.getString("access"));
            if(tokens.has("team"))request.put("selectedTeamId",tokens.getLong("team"));
            epoch=root.getString("epoch");id=mark(root,BOOTSTRAP);
            flight=new BootstrapFlight();BOOTSTRAPS.put(id,flight);
        }
        try {
        Reply response;
        try {response=invoke(BOOTSTRAP,request);}
        catch(Exception e) {clearNonRotating(epoch,id);throw e;}
        synchronized(LOCK) {
            JSONObject root=active(epoch,id);
            // This operation is read/bootstrap, not a rotating-token request. A later explicit
            // user action may try it again; no automatic retry happens here.
            root.remove("operation");store.write(root);
            if(response.status!=200)throw failure(response.status==401||response.status==403
                    ?"Grok refused this sign-in. No message was sent."
                    :"Could not connect to Grok (HTTP "+response.status+"). No message was sent.");
            JSONObject config=configFrom(response.body);
            root.put("config",config).put("config_saved_ms",clock.now());store.write(root);
            flight.succeeded=true;
            return copy(config);
        }
        } finally {
            synchronized(LOCK) {flight.finished=true;BOOTSTRAPS.remove(id);LOCK.notifyAll();}
        }
    }

    /** Memory-only backend credential context; never expose this to UI or persist elsewhere. */
    JSONObject backendContext() throws Exception {
        renewIfDue();
        synchronized(LOCK) {
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            JSONObject context=new JSONObject().put("accessToken",tokens.getString("access"));
            if(tokens.has("team"))context.put("selectedTeamId",tokens.getLong("team"));
            return context;
        }
    }

    private void renewIfDue() throws Exception {
        JSONObject request;String epoch,id,subject;long team;
        synchronized(LOCK) {
            JSONObject root=pendingRoot();JSONObject tokens=needTokens(root);
            if(tokens.getLong("expires_at_ms")-clock.now()>REFRESH_LEAD_MS)return;
            request=new JSONObject().put("refreshToken",tokens.getString("refresh"));
            subject=tokens.getString("subject");team=tokens.optLong("team",0);
            epoch=root.getString("epoch");id=mark(root,REFRESH);
        }
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
        String status=REFRESH.equals(kind)?"renewal_uncertain":POLL.equals(kind)?"login_uncertain":BOOTSTRAP.equals(kind)?"connecting":
                login!=null&&!expired(login)?"waiting":tokens!=null?"ready":"unlinked";
        JSONObject view=new JSONObject().put("status",status).put("linked",tokens!=null)
                .put("refresh_pending",REFRESH.equals(kind)).put("waiting",status.equals("waiting"));
        if(tokens!=null) {
            view.put("expires_at_ms",tokens.getLong("expires_at_ms"))
                    .put("refresh_due",tokens.getLong("expires_at_ms")-clock.now()<=REFRESH_LEAD_MS)
                    .put("account_key",hexDigest(tokens.getString("subject")+"\n"+tokens.optLong("team",0)))
                    .put("auth_generation",root.optString("generation")).put("author_key",hexDigest(tokens.getString("subject")));
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
    /** Called under LOCK. A persisted bootstrap without a live owner is safe to repeat. */
    private JSONObject pendingRoot() throws Exception {
        JSONObject root=store.read(),op=root.optJSONObject("operation");
        if(!root.has("operation"))return root;
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
        if(!flight.succeeded)throw bootstrapBusy();
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
