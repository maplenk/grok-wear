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
        /** Connect error code from the service (for example "resource_exhausted"), or "". */
        final String code;
        Failure(String safeMessage, boolean rejected) { this(safeMessage, rejected, ""); }
        Failure(String safeMessage, boolean rejected, String code) { super(safeMessage); this.rejected = rejected; this.code = code; }
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
                // Which harness runs the Bot decides the end-of-call handoff (see CallHandoff).
                String harness=text(b,"harness");
                if(harness.matches("[a-z][a-z0-9_-]{0,31}"))candidate.put("harness",harness);
                // The Bot's role line ("platform ops"), shown when there is no message preview.
                String title=text(b,"title").replaceAll("\\s+"," ").trim();if(!title.isEmpty()&&title.length()<=80&&safeSession(title))candidate.put("title",title);
                // The Bot's own voice, speed and language, which the official apps send in session.update.
                String voiceId=text(b,"voiceId");if(voiceId.matches("[A-Za-z0-9_.-]{1,64}"))candidate.put("voiceId",voiceId);
                double speed=voiceSpeed(b.opt("voiceSpeed"));if(speed>0)candidate.put("voiceSpeed",speed);
                String language=text(b,"voiceLanguage");if(language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")&&!language.equalsIgnoreCase("auto"))candidate.put("voiceLanguage",language);
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
    /**
     * The pin list to write back, as the official clients build it: pinning appends to the end,
     * unpinning removes; every other id keeps its place. Null when the stored list can't be read
     * safely, so a write never drops pins this watch doesn't understand.
     */
    static JSONArray withPin(JSONObject response,String id,boolean pinned){
        JSONObject settings=response.optJSONObject("settings"),pins=settings==null?null:settings.optJSONObject("pinnedAgents");
        // Connect JSON leaves out empty lists, so a missing list is an empty one.
        JSONArray ids=pins==null?null:pins.optJSONArray("agentIds");
        if(pins!=null&&pins.has("agentIds")&&ids==null)return null;
        if(ids==null)ids=new JSONArray();
        if(ids.length()>200||id.isEmpty()||!safeSession(id))return null;
        JSONArray result=new JSONArray();boolean present=false;
        for(int i=0;i<ids.length();i++){Object value=ids.opt(i);
            if(!(value instanceof String)||((String)value).isEmpty()||!safeSession((String)value))return null;
            if(value.equals(id)){present=true;if(!pinned)continue;}
            result.put(value);}
        if(pinned&&!present)result.put(id);
        return result;
    }
    /** Preferences after a pin write: the list the server stored, keeping the Primary Bot from the read before it. */
    static JSONObject storedPins(JSONObject before,JSONObject after,JSONArray sent)throws Exception {
        JSONObject settings=after.optJSONObject("settings"),pins=settings==null?null:settings.optJSONObject("pinnedAgents");
        boolean listed=pins!=null&&pins.optJSONArray("agentIds")!=null;
        if(!listed&&sent.length()>0)throw new Failure("Couldn't save. Try again",true);
        JSONObject result=botPreferences(after),previous=botPreferences(before);
        if(!listed)result.put("pinnedAgentIds",new JSONArray());
        else if(!result.has("pinnedAgentIds"))throw new Failure("Couldn't save. Try again",true);
        if(!result.has("mainAgentId")&&previous.has("mainAgentId"))result.put("mainAgentId",previous.getString("mainAgentId"));
        return result;
    }
    static final double[] VOICE_SPEEDS={.5,.75,1,1.25,1.5,2};
    /** Snaps to the official speed steps, as the apps do; 0 when there is no usable speed. */
    static double voiceSpeed(Object raw){
        double value=Double.NaN;
        if(raw instanceof Number)value=((Number)raw).doubleValue();else if(raw instanceof String)try{value=Double.parseDouble(((String)raw).trim());}catch(NumberFormatException ignored){}
        if(!Double.isFinite(value)||value<=0)return 0;
        double best=VOICE_SPEEDS[0];for(double step:VOICE_SPEEDS)if(Math.abs(step-value)<Math.abs(best-value))best=step;return best;
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
    /** A message reviewed for this account and Bot: refused if either changed since the review. */
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
        JSONArray jobs=pruned(root.getJSONArray("jobs"));root.put("jobs",jobs);
        for(int i=0;i<jobs.length();i++) if(pending(jobs.getJSONObject(i).optString("state")))
            throw new Failure("Wait until your last message is confirmed before sending another.",true);
        String id=UUID.randomUUID().toString();
        JSONObject job=new JSONObject().put("id",id).put("nonce",UUID.randomUUID().toString())
                .put("botId",bot.getString("id")).put("botName",bot.getString("name"))
                .put("text",prompt.trim()).put("state","prepared").put("created",System.currentTimeMillis())
                .put("notice","Not sent yet.");
        // A teammate's Bot answers in the viewer's own session, as the desktop client addresses it.
        if(!bot.optBoolean("viewerIsOwner",true)&&!bot.optString("viewerSessionId").isEmpty())job.put("sessionId",bot.getString("viewerSessionId"));
        jobs.put(job);
        JSONObject draft=root.optJSONObject("draft");
        if(draft!=null&&draft.optBoolean("gateway")&&prompt.trim().equals(draft.optString("text").trim()))root.remove("draft");
        store.write(root);return id;
    }
    /** A message being sent or not yet confirmed blocks the next one, so nothing is sent twice by mistake. */
    static boolean pending(String s) { return s.equals("prepared")||s.equals("submitting")||s.equals("unknown"); }
    /** Keeps every unresolved message and the 20 newest settled ones. */
    private static JSONArray pruned(JSONArray jobs)throws Exception {
        int settled=0;for(int i=0;i<jobs.length();i++)if(!pending(jobs.getJSONObject(i).optString("state")))settled++;
        JSONArray keep=new JSONArray();
        for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);if(!pending(job.optString("state"))&&settled-->20)continue;keep.put(job);}
        return keep;
    }
    synchronized JSONObject submit(String id) throws Exception {
        return submit(id,()->true);
    }
    synchronized JSONObject submit(String id, BooleanSupplier admitted) throws Exception {
        JSONObject root=linked(), job=find(root,id);
        if(!job.getString("state").equals("prepared")) return copy(job); // Never resend, including after process death.
        return send(root,job,admitted);
    }
    /**
     * Sends an unconfirmed message again with the same nonce and text, as the official Android client's
     * retry does: the service keeps one copy per nonce. At most three attempts in all.
     */
    synchronized JSONObject retry(String id) throws Exception {
        JSONObject root=linked(),job=find(root,id);
        if(!job.getString("state").equals("unknown")||job.optInt("attempts",1)>=3)return copy(job);
        return send(root,job,()->true);
    }
    private JSONObject send(JSONObject root,JSONObject job,BooleanSupplier admitted)throws Exception {
        job.put("state","submitting").put("attempts",job.optInt("attempts",0)+1);store.write(root); // Durable uncertainty before touching the network.
        // Final admission boundary: cancellation during disk I/O must not reach transport.
        // Once admitted, a transmitted request cannot be recalled by closing the screen.
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
            // The official Android client's body; sessionId addresses a teammate's Bot.
            JSONObject body=new JSONObject().put("agentId",job.getString("botId")).put("prompt",job.getString("text"))
                    .put("clientNonce",job.getString("nonce")).put("source","mobile").put("firstSentAtMs",job.getLong("created"));
            if(job.has("sessionId"))body.put("sessionId",job.getString("sessionId"));
            JSONObject result=transport.post(config,"sendPrompt",body);
            // The service answers with nothing or {accepted:true}; a repeated nonce is accepted, not doubled.
            if(result!=null&&result.has("accepted")&&!result.optBoolean("accepted"))job.put("state","unknown").put("notice","Not confirmed yet. It won't be sent again on its own.");
            else job.put("state","sent").put("notice","Sent");
        } catch(Exception e) {
            // A refusal is final; a timeout or a dropped connection leaves delivery unknown.
            boolean rejected=e instanceof Failure && ((Failure)e).rejected;
            job.put("state",rejected?"failed":"unknown").put("notice",rejected?safe(e):"Not confirmed yet. It won't be sent again on its own.");
        }
        store.write(root);return copy(job);
    }
    /** Confirms an unknown message from the chat: its own nonce and text there means it arrived. Never sends. */
    synchronized JSONObject poll(String id) throws Exception {
        JSONObject root=linked(),job=find(root,id);String state=job.getString("state");
        if(state.equals("submitting")){job.put("state","unknown");state="unknown";store.write(root);} // Interrupted mid-send.
        if(!state.equals("unknown")) return copy(job);
        job.remove("polling_finished");job.remove("poll_error");
        try {
            JSONObject body=new JSONObject().put("id",job.getString("botId")).put("limit",50);
            if(job.has("sessionId"))body.put("sessionId",job.getString("sessionId"));
            if(echoed(post(root,"getAgentTranscriptTail",body),job))job.put("state","sent").put("notice","Sent");
            else job.put("notice","Not confirmed yet. It won't be sent again on its own.");
        } catch(Exception e) {job.put("notice",safe(e));if(e instanceof Failure&&((Failure)e).rejected)job.put("polling_finished",true).put("poll_error",true);}
        store.write(root);return copy(job);
    }
    /** Exactly one chat row from the caller carries this message's nonce and text. */
    static boolean echoed(JSONObject tail,JSONObject job) throws Exception {
        JSONArray entries=tail.optJSONArray("entries");
        if(entries==null||entries.length()>200)return false;
        int found=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);if(e==null)continue;
            if(job.getString("nonce").equals(text(e,"clientNonce"))&&job.getString("text").equals(entryText(e))&&!"send-message".equals(text(e,"kind"))&&!"assistant".equals(text(e,"role")))found++;
        }
        return found==1;
    }
    static String entryText(JSONObject e) {
        for(String k:new String[]{"text","prompt","content"}) if(!text(e,k).isEmpty())return text(e,k);
        return text(e.optJSONObject("message"),"content");
    }
    synchronized JSONObject job(String id) throws Exception { return copy(find(linked(),id)); }
    /** This Bot's messages from the watch, oldest first. */
    synchronized JSONArray messages(String botId) throws Exception {
        JSONArray jobs=linked().getJSONArray("jobs"),mine=new JSONArray();
        for(int i=0;i<jobs.length();i++)if(botId.equals(jobs.getJSONObject(i).optString("botId")))mine.put(copy(jobs.getJSONObject(i)));
        return mine;
    }
    /**
     * Marks the Bot's chat read, as the official apps do when it is opened (setAgentUnread), and clears
     * the saved roster row so the badge goes at once.
     */
    synchronized void markRead(String botId) throws Exception {
        JSONObject root=linked();post(root,"setAgentUnread",new JSONObject().put("id",botId).put("isUnread",false));
        synchronized(ACCOUNT_STATE_LOCK){
            root=linked();JSONArray rows=root.optJSONArray("candidates");boolean changed=false;
            if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&botId.equals(row.optString("id"))){row.put("hasUnread",false).put("unreadCount",0);changed=true;}}
            JSONObject bot=root.optJSONObject("bot");if(bot!=null&&botId.equals(bot.optString("id"))){bot.put("hasUnread",false).put("unreadCount",0);changed=true;}
            if(changed)store.write(root);
        }
    }
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
