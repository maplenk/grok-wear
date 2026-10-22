package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Watch-owned messaging state. No account discovery, retries of sends, or approvals. */
final class GrokCore {
    interface Store { JSONObject read() throws Exception; void write(JSONObject value) throws Exception; }
    interface Transport { JSONObject post(JSONObject config, String method, JSONObject body) throws Exception; }
    interface ConfigProvider { JSONObject current(JSONObject root) throws Exception; }
    static final class Failure extends Exception {
        final boolean rejected;
        Failure(String safeMessage, boolean rejected) { super(safeMessage); this.rejected = rejected; }
    }
    // Activities and the voice service own separate core instances over the same vault.
    // Serialize their local roster/account transactions, never renewal or transport calls.
    private static final Object ACCOUNT_STATE_LOCK=new Object();
    final Store store;
    final Transport transport;
    final ConfigProvider configs;
    GrokCore(Store store, Transport transport) { this(store, transport, root -> root.getJSONObject("config")); }
    GrokCore(Store store, Transport transport, ConfigProvider configs) {
        this.store = store; this.transport = transport; this.configs = configs;
    }

    static JSONObject validateConfig(JSONObject input) throws Exception {
        String url = required(input, "gatewayUrl", 2048);
        URI u = new URI(url);
        String host = u.getHost();
        if (host == null || !"https".equals(u.getScheme()) || u.getRawUserInfo() != null
                || u.getRawQuery() != null || u.getRawFragment() != null
                || (u.getPort() != -1 && u.getPort() != 443)
                || !(host.endsWith(".cursor.sh") || host.endsWith(".cursor.com") || host.endsWith(".cursorvm.com"))
                || !u.normalize().equals(u) || url.contains("%") || url.contains("\\"))
            throw new Failure("Unrecognized Grok server. Nothing was linked.", true);
        String token = required(input, "gatewayToken", 32768);
        if (token.contains("\n") || token.contains("\r")) throw new Failure("Invalid Grok credential.", true);
        JSONObject source = input.optJSONObject("headers"), headers = new JSONObject();
        if (source != null) {
            if (source.length() > 16) throw new Failure("Too many Grok routing headers.", true);
            Iterator<String> names = source.keys();
            while (names.hasNext()) {
                String raw = names.next(), key = raw.toLowerCase(java.util.Locale.ROOT);
                String value = required(source, raw, 16384);
                if (!key.matches("x-[a-z0-9-]{1,80}") || value.contains("\n") || value.contains("\r"))
                    throw new Failure("Unrecognized Grok routing header.", true);
                headers.put(key, value);
            }
        }
        return new JSONObject().put("gatewayUrl", url.replaceAll("/+$", ""))
                .put("gatewayToken", token).put("headers", headers);
    }
    static String required(JSONObject o, String name, int max) throws Exception {
        Object v = o.opt(name);
        if (!(v instanceof String) || ((String)v).trim().isEmpty() || ((String)v).length() > max)
            throw new Failure("Grok returned an unsupported response.", true);
        return (String)v;
    }
    static String text(JSONObject o, String name) { return o != null && o.opt(name) instanceof String ? o.optString(name) : ""; }
    synchronized void link(JSONObject config) throws Exception {
        JSONObject root = store.read();
        if (root.has("config")) throw new Failure("Unlink the existing Grok connection first.", true);
        JSONObject draft = root.optJSONObject("draft");
        root = new JSONObject().put("config", validateConfig(config)).put("profile", UUID.randomUUID().toString())
                .put("jobs", new JSONArray()).put("voice", false);
        if (draft != null) root.put("draft", draft);
        store.write(root);
    }
    synchronized void unlink() throws Exception { synchronized(ACCOUNT_STATE_LOCK){store.write(new JSONObject());} }
    synchronized void linkBrowser(JSONObject config, String account, String generation) throws Exception {
        synchronized(ACCOUNT_STATE_LOCK){
        if (account == null || account.isEmpty() || generation == null || generation.isEmpty())
            throw new Failure("Grok sign-in could not be verified. Nothing was linked.", true);
        JSONObject root = store.read();
        if (root.has("config")) {
            if (account.equals(root.optString("auth_account")) && generation.equals(root.optString("auth_generation"))) return;
            throw new Failure("Unlink the existing Grok connection first.", true);
        }
        JSONObject draft = root.optJSONObject("draft");
        // A new browser identity gets a new inbox and review identity; manual credentials are never silently migrated.
        root = new JSONObject().put("config", validateConfig(config)).put("profile", UUID.randomUUID().toString())
                .put("jobs", new JSONArray()).put("voice", false).put("auth_account", account).put("auth_generation", generation);
        if (draft != null) root.put("draft", draft);
        store.write(root);
        }
    }
    synchronized JSONObject draft() throws Exception {
        JSONObject draft = store.read().optJSONObject("draft");
        return draft == null ? new JSONObject().put("text", "").put("gateway", true) : copy(draft);
    }
    synchronized void saveDraft(String text, boolean gateway) throws Exception {
        if (text == null || text.length() > 4096) throw new Failure("Keep the draft within 4,096 characters.", true);
        JSONObject root = store.read();
        if (text.isEmpty()) root.remove("draft");
        else root.put("draft", new JSONObject().put("text", text).put("gateway", gateway));
        store.write(root);
    }
    private JSONObject post(JSONObject root, String method, JSONObject body) throws Exception {
        return transport.post(currentConfig(root), method, body);
    }
    private JSONObject currentConfig(JSONObject root) throws Exception {
        try { return validateConfig(configs.current(copy(root))); }
        catch(Failure failure) { throw failure; }
        catch(Exception invalid) {
            // No gateway request has started. Stop observation and never present this as an uncertain send.
            throw new Failure("Could not prepare the Grok connection. Check sign-in; nothing was sent.", true);
        }
    }
    synchronized void enableVoice(boolean enabled) throws Exception {
        JSONObject root = linked(); root.put("voice", enabled); store.write(root);
    }
    JSONArray bots() throws Exception {
        JSONObject root;
        synchronized(this){synchronized(ACCOUNT_STATE_LOCK){root=linked();}}
        // Roster discovery must not hold up selecting a saved Bot. Only local snapshots and
        // the final merge hold the monitor; account renewal and the network run outside it.
        JSONObject body = post(root, "listAgents", new JSONObject());
        JSONArray bots = body.optJSONArray("agents");
        if(bots==null&&body.optJSONObject("agent")!=null)bots=new JSONArray().put(body.getJSONObject("agent"));
        if (bots == null) throw new Failure("Bot list format is not recognized. No Bot was selected.", true);
        JSONArray safe = new JSONArray();
        int photoBudget = 262144;
        for (int i = 0; i < Math.min(200, bots.length()); i++) {
            JSONObject b = bots.optJSONObject(i);
            if (b == null || b.optBoolean("isGroup", false)) continue;
            JSONArray members=b.optJSONArray("memberIds");if(members==null)members=b.optJSONArray("memberAgentIds");
            if(!b.has("isGroup")&&members!=null&&members.length()>0)continue;
            String id = text(b, "id"); if (id.isEmpty()) id = text(b, "agentId");
            if (!id.isEmpty() && id.length() <= 512 && text(b,"name").length()<=512) {
                JSONObject candidate=new JSONObject().put("id", id)
                    .put("name", text(b, "name").isEmpty() ? "Unnamed Bot" : text(b, "name"));
                // Optional, bounded design identifiers from the verified listAgents schema.
                // Never load account-supplied paths, fonts or arbitrary color strings.
                String avatarShape=text(b,"avatarShape"),avatarColor=text(b,"avatarColor");
                if(AvatarPolicy.validShape(avatarShape))candidate.put("avatarShape",avatarShape);
                if(AvatarPolicy.validColor(avatarColor))candidate.put("avatarColor",avatarColor);
                // Native entity metadata is optional: absence never implies read/idle status.
                for(String key:new String[]{"hasUnread","isRunning","isRunningTurn","isComposingMessage"})
                    if(b.opt(key) instanceof Boolean)candidate.put(key,b.opt(key));
                for(String key:new String[]{"unreadCount","lastActivityAt","lastViewedAt"}) {
                    Object value=b.opt(key);
                    if(value instanceof Number){double n=((Number)value).doubleValue();
                        if(Double.isFinite(n)&&n>=0&&n<=9007199254740991d&&n==Math.floor(n))candidate.put(key,((Number)value).longValue());}
                }
                String preview=text(b,"lastMessagePreview");
                if(!preview.isEmpty()&&preview.length()<=4096)
                    candidate.put("lastMessagePreview",preview.replaceAll("[\\s\\p{Cc}]+"," ").trim());
                // Inline raster data is already part of native entities. No URL/protocol fetching.
                String photo=text(b,"avatarDataUrl");
                if(photo.length()<=65536&&photo.length()<=photoBudget&&photo.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}")){
                    candidate.put("avatarDataUrl",photo);photoBudget-=photo.length();
                }
                // Native roster mapper defaults absent ownership to owner; only explicit false
                // selects viewerSessionId. Malformed supplied ownership is never treated as owner.
                Object ownership=b.opt("viewerIsOwner");
                if(ownership==null||ownership==JSONObject.NULL)candidate.put("viewerIsOwner",true);
                else if(ownership instanceof Boolean)candidate.put("viewerIsOwner",ownership);
                else candidate.put("viewerIsOwner",false).put("viewerMetadataInvalid",true);
                Object session=b.opt("viewerSessionId");
                if(session instanceof String&&safeSession((String)session))candidate.put("viewerSessionId",session);
                else if(session!=null&&session!=JSONObject.NULL)candidate.put("viewerMetadataInvalid",true);
                safe.put(candidate);
            }
        }
        synchronized(this){synchronized(ACCOUNT_STATE_LOCK){
            JSONObject current=linked();
            for(String key:new String[]{"profile","auth_account","auth_generation"})
                if(!root.optString(key).equals(current.optString(key)))
                    throw new Failure("Grok sign-in changed. Refresh Bots.",true);
            // Merge into current local state, preserving a Bot selected while the fetch ran.
            // Only select a Bot actually returned by this connection, with an explicit tap.
            JSONObject previous=current.optJSONObject("bot");
            if(previous!=null){JSONObject refreshed=null;for(int i=0;i<safe.length();i++){JSONObject candidate=safe.getJSONObject(i);if(previous.optString("id").equals(candidate.optString("id"))){refreshed=candidate;break;}}
                if(refreshed==null)current.remove("bot");else current.put("bot",refreshed);
            }
            current.put("candidates",safe).put("roster_fetched_at_ms",System.currentTimeMillis());
            store.write(current);
        }}
        return safe;
    }
    /** Local-only view; candidates from older app versions are already a valid saved roster. */
    synchronized JSONObject cachedRoster(String account,String generation)throws Exception {
        synchronized(ACCOUNT_STATE_LOCK){
        JSONObject root=rosterRoot(account,generation);
        JSONArray bots=root.optJSONArray("candidates");JSONObject preferences=root.optJSONObject("bot_preferences");
        return new JSONObject().put("present",bots!=null)
            .put("bots",bots==null?new JSONArray():new JSONArray(bots.toString()))
            .put("preferences",preferences==null?new JSONObject():copy(preferences))
            .put("fetchedAtMs",Math.max(0,root.optLong("roster_fetched_at_ms")));
        }
    }
    synchronized void saveBotPreferences(JSONObject preferences,String account,String generation)throws Exception {
        synchronized(ACCOUNT_STATE_LOCK){
        JSONObject root=rosterRoot(account,generation);
        root.put("bot_preferences",copy(preferences)).put("preferences_fetched_at_ms",System.currentTimeMillis());
        store.write(root);
        }
    }
    private JSONObject rosterRoot(String account,String generation)throws Exception {
        JSONObject root=linked();
        if(account==null||account.isEmpty()||generation==null||generation.isEmpty()
            ||!account.equals(root.optString("auth_account"))||!generation.equals(root.optString("auth_generation")))
            throw new Failure("Grok sign-in changed. Refresh Bots.",true);
        return root;
    }
    /** Explicit account runtime settings pointer/order; never derived from Bot names. */
    static JSONObject botPreferences(JSONObject response)throws Exception {
        JSONObject result=new JSONObject(),settings=response.optJSONObject("settings");
        if(settings==null)return result;
        String primary=text(settings,"mainAgentId");
        if(!primary.isEmpty()&&safeSession(primary))result.put("mainAgentId",primary);
        JSONObject pins=settings.optJSONObject("pinnedAgents");
        JSONArray ids=pins==null?null:pins.optJSONArray("agentIds");
        if(ids!=null&&ids.length()<=200){JSONArray safe=new JSONArray();java.util.HashSet<String> seen=new java.util.HashSet<>();boolean valid=true;
            for(int i=0;i<ids.length();i++){Object id=ids.opt(i);if(!(id instanceof String)||((String)id).isEmpty()||!safeSession((String)id)){valid=false;break;}
                if(seen.add((String)id))safe.put(id);}
            if(valid)result.put("pinnedAgentIds",safe);
        }
        return result;
    }
    private static boolean safeSession(String value){if(value.length()>512)return false;for(int i=0;i<value.length();i++)if(value.charAt(i)<32)return false;return true;}
    synchronized void select(String id) throws Exception {
        synchronized(ACCOUNT_STATE_LOCK){
        JSONObject root = linked(); JSONArray bots = root.optJSONArray("candidates");
        if (bots != null) for (int i=0;i<bots.length();i++) {
            JSONObject b=bots.getJSONObject(i);
            if (id.equals(b.getString("id"))) { root.put("bot",b); store.write(root); return; }
        }
        throw new Failure("Refresh the Bot list before selecting.", true);
        }
    }
    synchronized JSONObject view() throws Exception {
        synchronized(ACCOUNT_STATE_LOCK){
        JSONObject r=store.read(); JSONArray jobs=r.optJSONArray("jobs");
        return new JSONObject().put("linked", r.has("config")).put("browser_auth", r.has("auth_generation")).put("voice",r.optBoolean("voice"))
                .put("profile",r.optString("profile")).put("bot",r.optJSONObject("bot")).put("jobs", jobs==null?new JSONArray():jobs);
        }
    }
    synchronized String prepare(String prompt, String profile, String botId) throws Exception {
        JSONObject root=linked(),bot=root.optJSONObject("bot");
        if(!profile.equals(root.optString("profile"))||bot==null||!botId.equals(bot.optString("id")))
            throw new Failure("Grok connection or Bot changed. Review the message again. Nothing was sent.",true);
        return prepare(prompt);
    }
    synchronized String prepare(String prompt) throws Exception {
        if (prompt == null || prompt.trim().isEmpty() || prompt.length()>4096)
            throw new Failure("Use a message between 1 and 4,096 characters.",true);
        JSONObject root=linked(); JSONObject bot=root.optJSONObject("bot");
        if (bot==null) throw new Failure("Choose your Grok Bot first.",true);
        JSONArray jobs=root.getJSONArray("jobs");
        if(jobs.length()>=50) throw new Failure("Grok inbox is full. Delete an old request first.",true);
        for(int i=0;i<jobs.length();i++) if(pending(jobs.getJSONObject(i).optString("state")))
            throw new Failure("Check the pending Grok request before sending another.",true);
        String id=UUID.randomUUID().toString();
        JSONObject job=new JSONObject().put("id",id).put("nonce",UUID.randomUUID().toString())
                .put("botId",bot.getString("id")).put("botName",bot.getString("name"))
                .put("text",prompt.trim()).put("state","prepared").put("created",System.currentTimeMillis())
                .put("reply","").put("notice","Prepared locally; not sent. Delete this draft and review a new message to send it.");
        jobs.put(job);
        JSONObject draft=root.optJSONObject("draft");
        if(draft!=null&&draft.optBoolean("gateway")&&prompt.trim().equals(draft.optString("text").trim()))root.remove("draft");
        store.write(root);return id;
    }
    static boolean pending(String s) { return s.equals("prepared")||s.equals("submitting")||s.equals("unknown")||s.equals("waiting"); }
    synchronized JSONObject submit(String id) throws Exception {
        return submit(id,()->true);
    }
    synchronized JSONObject submit(String id, BooleanSupplier admitted) throws Exception {
        JSONObject root=linked(), job=find(root,id);
        if(!job.getString("state").equals("prepared")) return copy(job); // Never resend, including after process death.
        job.put("state","submitting");store.write(root); // Durable uncertainty before touching the network.
        // Final admission boundary: cancellation during disk I/O must not reach transport.
        // Once admitted, a transmitted request cannot be recalled by closing the screen/call.
        if(!admitted.getAsBoolean()) {
            job.put("state","failed").put("notice","Cancelled before sending. Nothing was sent to Grok.");
            store.write(root);return copy(job);
        }
        try {
            JSONObject config=currentConfig(root);
            // Renewal/bootstrap may take time. A cancelled screen must not send once it finishes.
            if(!admitted.getAsBoolean()) {
                job.put("state","failed").put("notice","Cancelled before sending. Nothing was sent to Grok.");
                store.write(root);return copy(job);
            }
            JSONObject result=transport.post(config,"sendPrompt",new JSONObject()
                    .put("agentId",job.getString("botId")).put("prompt",job.getString("text"))
                    .put("clientNonce",job.getString("nonce")));
            String receipt=text(result,"messageId");
            if(receipt.isEmpty()||receipt.length()>512) job.put("state","unknown").put("notice","Delivery is uncertain. Do not resend; check Grok.");
            else job.put("receipt",receipt).put("state","waiting").put("notice","Grok accepted the request.");
        } catch(Exception e) {
            boolean rejected=e instanceof Failure && ((Failure)e).rejected;
            job.put("state",rejected?"failed":"unknown").put("notice",rejected?safe(e):"Delivery is uncertain. Check Grok before sending again.");
        }
        store.write(root);return copy(job);
    }
    synchronized JSONObject poll(String id) throws Exception {
        JSONObject root=linked(),job=find(root,id);String state=job.getString("state");
        if(state.equals("submitting")){job.put("state","unknown");state="unknown";}
        if(state.equals("prepared")||state.equals("failed")) return copy(job);
        job.remove("polling_finished");job.remove("poll_error");
        try {
            if(state.equals("unknown")) {
                JSONObject tail=post(root,"getAgentTranscriptTail",new JSONObject()
                        .put("id",job.getString("botId")).put("limit",200));
                String receipt=reconcile(tail,job);
                if(receipt.isEmpty()) {job.put("notice","No matching receipt yet. Check Grok; this request will not be resent.");store.write(root);return copy(job);}
                job.put("receipt",receipt).put("state","waiting");store.write(root);
            }
            String receipt=job.getString("receipt");
            JSONObject thread=post(root,"getAgentThread",new JSONObject()
                    .put("id",job.getString("botId")).put("rootId",receipt));
            String reply=matchedReply(thread,receipt,job.getString("text"));
            if(reply.isEmpty()) {
                JSONObject tail=post(root,"getAgentTranscriptTail",new JSONObject()
                        .put("id",job.getString("botId")).put("limit",200));
                reply=matchedTailReply(tail,receipt,job.getString("text"),job.getString("nonce"));
            }
            if(!reply.isEmpty()) job.put("reply",reply).put("state","reply").put("notice","Reply received. Grok may still be working.");
            else job.put("notice","Waiting for a reply linked to this request. Check Grok if it already answered.");
        } catch(Exception e) {job.put("notice",safe(e));if(e instanceof Failure&&((Failure)e).rejected)job.put("polling_finished",true).put("poll_error",true);}
        store.write(root);return copy(job);
    }
    static String reconcile(JSONObject tail,JSONObject job) throws Exception {
        JSONArray entries=tail.optJSONArray("entries");String found="";
        if(entries==null||entries.length()>200)return "";
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);if(e==null)continue;
            if(job.getString("nonce").equals(text(e,"clientNonce")) && job.getString("text").equals(entryText(e))
                    && isUser(e) && !text(e,"id").isEmpty()) {
                if(!found.isEmpty())return "";found=text(e,"id");
            }
        }
        return found;
    }
    static boolean isUser(JSONObject e) {return "user".equals(text(e,"role"))||"user-message".equals(text(e,"kind"))||"prompt".equals(text(e,"kind"));}
    static String entryText(JSONObject e) {
        for(String k:new String[]{"text","prompt","content"}) if(!text(e,k).isEmpty())return text(e,k);
        return text(e.optJSONObject("message"),"content");
    }
    static String matchedReply(JSONObject payload,String receipt,String prompt) throws Exception {
        JSONObject thread=payload.optJSONObject("thread");if(thread==null)thread=payload;
        JSONArray entries=thread.optJSONArray("entries");if(entries==null||entries.length()>200)return "";
        boolean anchored=false;
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);
            if(e!=null&&receipt.equals(text(e,"id"))&&isUser(e)&&prompt.equals(entryText(e))) anchored=true;
        }
        StringBuilder answer=new StringBuilder();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);if(e==null||!"send-message".equals(text(e,"kind")))continue;
            String id=text(e,"id");if(id.isEmpty()||id.equals(receipt)||!seen.add(id))continue;
            String root=text(e,"rootId"),parent=text(e,"replyToId");
            if((!root.isEmpty()&&!root.equals(receipt))||(!parent.isEmpty()&&!parent.equals(receipt)))continue;
            if(!anchored&&!receipt.equals(root)&&!receipt.equals(parent))continue;
            String text=entryText(e);if(text.isEmpty())continue;
            if(answer.length()+text.length()>32768)throw new Failure("Grok reply is too long. Read the complete answer in Grok.",true);
            if(answer.length()>0)answer.append("\n\n");answer.append(text);
        }
        return answer.toString();
    }
    /** Bounded tail correlation observed on the watch: exact origin -> native requestId -> Bot reply. */
    static String matchedTailReply(JSONObject payload,String receipt,String prompt,String nonce)throws Exception {
        JSONArray entries=payload.optJSONArray("entries");
        if(entries==null||entries.length()>200||!validCorrelationId(receipt)||nonce==null||nonce.isEmpty()||prompt==null||prompt.isEmpty())return "";
        String request="";int origins=0;
        for(int i=0;i<entries.length();i++){
            JSONObject e=entries.optJSONObject(i);if(e==null)continue;
            boolean idMatch=receipt.equals(text(e,"id")),nonceMatch=nonce.equals(text(e,"clientNonce"));
            if(!idMatch&&!nonceMatch)continue;
            // Receipt or nonce collisions cannot be resolved by choosing a convenient row.
            if(!idMatch||!nonceMatch||!"user".equals(text(e,"role"))||!"message".equals(text(e,"kind"))
                    ||!prompt.equals(entryText(e))||!validCorrelationId(text(e,"requestId"))||++origins!=1)return "";
            request=text(e,"requestId");
        }
        if(origins!=1)return "";
        java.util.Map<String,JSONObject> identities=new java.util.HashMap<>();java.util.Set<String> conflictingIds=new java.util.HashSet<>();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;String id=text(entry,"id");
            JSONObject previous=identities.put(id,entry);
            if(previous!=null&&!sameCorrelationEntry(previous,entry))conflictingIds.add(id);
        }
        StringBuilder answer=new StringBuilder();java.util.Map<String,String> seen=new java.util.HashMap<>();
        for(int i=0;i<entries.length();i++){
            JSONObject e=entries.optJSONObject(i);if(e==null||!request.equals(text(e,"requestId")))continue;
            String id=text(e,"id");
            if("user".equals(text(e,"role"))){if(!receipt.equals(id))return "";continue;}
            if(!"send-message".equals(text(e,"kind")))continue;
            if(!validCorrelationId(id)||receipt.equals(id)||conflictingIds.contains(id))return "";
            String root=text(e,"rootId"),parent=text(e,"replyToId");
            if((!root.isEmpty()&&!receipt.equals(root))||(!parent.isEmpty()&&!receipt.equals(parent)))return "";
            JSONObject message=e.optJSONObject("message");String type=text(message,"type");
            if("auto-review-approval".equals(type)||"local-tool-permission".equals(type))continue;
            String body=entryText(e);if(body.isEmpty())continue;
            String previous=seen.put(id,body);if(previous!=null){if(!previous.equals(body))return "";continue;}
            if(answer.length()+body.length()+(answer.length()>0?2:0)>32768)throw new Failure("Grok reply is too long. Read the complete answer in Grok.",true);
            if(answer.length()>0)answer.append("\n\n");answer.append(body);
        }
        return answer.toString();
    }
    private static boolean sameCorrelationEntry(JSONObject a,JSONObject b){
        for(String key:new String[]{"requestId","kind","role","rootId","replyToId","clientNonce"})if(!text(a,key).equals(text(b,key)))return false;
        return entryText(a).equals(entryText(b))&&text(a.optJSONObject("message"),"type").equals(text(b.optJSONObject("message"),"type"));
    }
    private static boolean validCorrelationId(String value){return value!=null&&!value.isEmpty()&&value.length()<=512&&!value.matches("(?s).*[\\p{Cntrl}].*");}
    synchronized JSONObject job(String id) throws Exception { return copy(find(linked(),id)); }
    synchronized void delete(String id) throws Exception {
        JSONObject root=linked();JSONArray rows=root.getJSONArray("jobs"), keep=new JSONArray();
        for(int i=0;i<rows.length();i++)if(!id.equals(rows.getJSONObject(i).getString("id")))keep.put(rows.getJSONObject(i));
        root.put("jobs",keep);store.write(root);
    }
    private JSONObject linked() throws Exception {
        JSONObject r=store.read();if(!r.has("config"))throw new Failure("Link Grok in More → Grok first.",true);return r;
    }
    private static JSONObject find(JSONObject r,String id) throws Exception {
        JSONArray jobs=r.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)if(id.equals(jobs.getJSONObject(i).getString("id")))return jobs.getJSONObject(i);
        throw new Failure("This request is no longer in the Grok inbox.",true);
    }
    static JSONObject copy(JSONObject o) throws Exception {return new JSONObject(o.toString());}
    static String safe(Exception e) {return e instanceof Failure?e.getMessage():"Grok is unavailable. Check the connection or link again.";}
}
