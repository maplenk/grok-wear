package si.zya.grokwear;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Account/Bot-scoped final text only. A lost write response is never replayed automatically. */
final class HistoryCoordinator {
    interface Store {JSONObject read()throws Exception;void write(JSONObject root)throws Exception;}
    interface Transport {Object request(String method,JSONObject body,JSONObject binding)throws Exception;}
    interface Guard {void check(JSONObject binding,boolean selected)throws Exception;}
    interface Clock {long now();}
    static final class Context {
        final String accountKey,authGeneration,agentId,sessionId;
        private final String identity,recent;
        Context(JSONObject binding,JSONArray turns)throws Exception {accountKey=binding.getString("accountKey");authGeneration=binding.getString("authGeneration");agentId=binding.getString("agentId");sessionId=binding.optString("sessionId","");identity=binding.toString();recent=turns.toString();}
        JSONArray turns()throws Exception{return new JSONArray(recent);}
        JSONObject binding()throws Exception{return new JSONObject(identity);}
    }
    private static final Object LOCK=new Object();
    private static final Set<String> INFLIGHT=new HashSet<>();
    private static final int MAX_STORE=1500*1024,MAX_CALL=128*1024,MAX_TEXT=8192;
    /** How old a saved page may be and still seed a call when the network cannot refresh it. */
    static final long SAVED_CONTEXT_MS=15*60_000L;
    private final Store store;private final Transport transport;private final Guard guard;private final Clock clock;
    HistoryCoordinator(Store store,Transport transport,Guard guard,Clock clock){this.store=store;this.transport=transport;this.guard=guard;this.clock=clock;}
    static Context prepare(BotAccount account,JSONObject bot)throws Exception {
        JSONObject binding=account.historyBinding();if(!binding.getString("agentId").equals(bot.optString("id")))throw fail("Grok Bot changed. Start the call again.");
        return account.continuity.prepare(binding);
    }
    static JSONObject stage(BotAccount account,Context context,JSONObject record)throws Exception{return account.continuity.stage(context,record);}
    static JSONObject record(BotAccount account,Context context,JSONObject record)throws Exception{return account.continuity.record(context,record);}
    private static final class CacheBatch {
        final String revision;final Map<String,JSONObject> pages=new LinkedHashMap<>();
        CacheBatch(String revision){this.revision=revision;}
    }
    Context prepare(JSONObject binding)throws Exception {
        long deadline=clock.now()+45000;
        validateBinding(binding);guard.check(binding,true);CacheBatch batch;
        synchronized(LOCK){guard.check(binding,true);batch=new CacheBatch(scope(binding).getString("revision"));}
        Map<String,JSONObject> voiceRecords=new HashMap<>();Long before=null;List<JSONObject> eligible=new ArrayList<>();Set<String> seen=new HashSet<>();boolean more=false;
        for(int pageNumber=0;pageNumber<3;pageNumber++){
            JSONObject page;
            // Older pages only deepen context. A weak watch link must not refuse a call
            // that already has the newest page.
            if(pageNumber==0)page=readPage(binding,before,50,deadline,voiceRecords,batch);
            else try{page=readPage(binding,before,50,deadline,voiceRecords,batch);}catch(GrokCore.Failure weak){break;}
            if(page.optBoolean("stale")){
                // A recent saved page of this exact conversation can start the call; an old one cannot.
                if(pageNumber>0)break;
                long age=clock.now()-page.optLong("fetchedAtMs",0);
                if(age<0||age>SAVED_CONTEXT_MS)throw fail("Couldn't load this chat on the current connection. Try again with a stronger signal.");
            }
            // A missing voice-call detail leaves its summary row; the remaining text is still current.
            JSONArray entries=page.getJSONArray("entries");List<JSONObject> older=new ArrayList<>();
            for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);String role=row.optString("role");if(!role.equals("user")&&!role.equals("assistant"))continue;String key=row.getString("id")+"@"+row.optString("seq");if(seen.add(key))older.add(row);}
            older.addAll(eligible);eligible=older;more=page.optBoolean("hasMore");
            List<JSONObject> window=recent(eligible);
            if(window.size()>=20||!more||page.optBoolean("stale"))break;
            before=cursor(page.opt("nextBeforeSeq"));if(before==null)break;
        }
        List<JSONObject> selected=recent(eligible);
        if(selected.isEmpty()&&more)throw fail("Recent shared history needs more pages. Open History before starting voice.");
        boolean hasUser=false;for(JSONObject row:eligible)if(row.optString("role").equals("user")){hasUser=true;break;}
        if(selected.isEmpty()&&hasUser)throw fail("Recent shared history is too large to load safely.");
        JSONArray turns=new JSONArray();for(JSONObject row:selected)turns.put(new JSONObject().put("id",row.getString("id")).put("speaker",row.getString("role")).put("text",row.getString("text")).put("atMs",row.optLong("atMs")));
        guard.check(binding,true);
        try {synchronized(LOCK){
            guard.check(binding,true);JSONObject root=active(binding,batch.revision),pages=root.getJSONObject("pages");
            for(Map.Entry<String,JSONObject> entry:batch.pages.entrySet()){
                JSONObject current=pages.optJSONObject(entry.getKey()),incoming=entry.getValue();
                if(current==null||current.optLong("fetchedAtMs")<=incoming.optLong("fetchedAtMs"))pages.put(entry.getKey(),copy(incoming).put("pendingCount",pending(root,binding)));
            }
            trimPages(pages);write(root);
        }}catch(GrokCore.Failure safe){throw safe;}
        catch(Exception ignored){throw fail("Could not save shared history safely. Try again before starting voice.");}
        guard.check(binding,true);return new Context(binding,turns);
    }
    private static List<JSONObject> recent(List<JSONObject> rows)throws Exception {
        List<JSONObject> selected=new ArrayList<>();int chars=0;
        for(int i=rows.size()-1;i>=0&&selected.size()<20;i--){JSONObject row=rows.get(i);String text=row.getString("text");if(chars+text.length()>12000)break;chars+=text.length();selected.add(row);}
        Collections.reverse(selected);while(!selected.isEmpty()&&!selected.get(0).optString("role").equals("user"))selected.remove(0);return selected;
    }
    JSONObject page(JSONObject binding,Long before,int limit)throws Exception {return readPage(binding,before,limit,clock.now()+45000,new HashMap<>(),null);}
    /** Blocking bounded background work; history reads never upload or reconcile. */
    void recoverPending(JSONObject binding)throws Exception {
        validateBinding(binding);guard.check(binding,true);
        JSONObject recovery=copy(binding).put("deadlineMs",clock.now()+30000);recoverOne(recovery);guard.check(binding,true);
    }
    private JSONObject readPage(JSONObject binding,Long before,int limit,long deadline,Map<String,JSONObject> voiceRecords,CacheBatch batch)throws Exception {
        validateBinding(binding);if(limit<1||limit>100||before!=null&&before<0)throw fail("Invalid history page request.");guard.check(binding,true);
        String revision;JSONObject cached;
        synchronized(LOCK){guard.check(binding,false);JSONObject root=batch==null?scope(binding):active(binding,batch.revision);revision=root.getString("revision");cached=cached(root,binding,before,limit);
            if(cached==null&&before==null)cached=newest(root,binding);}
        JSONObject requestBinding=copy(binding).put("deadlineMs",deadline);
        try {
            if(clock.now()>=deadline)throw fail("Shared history took too long to load.");
            JSONObject body=new JSONObject().put("id",binding.getString("agentId")).put("limit",limit);if(before!=null)body.put("beforeSeq",before);if(!binding.optString("sessionId").isEmpty())body.put("sessionId",binding.getString("sessionId"));
            Object reply=transport.request("getAgentTranscriptTail",body,requestBinding);if(clock.now()>deadline)throw fail("Shared history took too long to load.");if(!(reply instanceof JSONObject))throw fail("Grok returned an unsupported history page.");
            JSONObject raw=(JSONObject)reply;JSONArray rawEntries=raw.optJSONArray("entries");if(rawEntries==null)throw fail("Grok returned an unsupported history page.");
            boolean[] incomplete={false};JSONArray entries=normalize(rawEntries,requestBinding,incomplete,voiceRecords);if(clock.now()>deadline)throw fail("Shared history took too long to load.");Long cursor=cursor(raw.opt("nextBeforeSeq"));if(before!=null&&cursor!=null&&cursor>=before)throw fail("Grok returned a repeated history cursor.");
            JSONObject page=new JSONObject().put("binding",copy(binding)).put("entries",entries).put("nextBeforeSeq",cursor==null?JSONObject.NULL:cursor).put("hasMore",cursor!=null).put("stale",false).put("syncStatus",incomplete[0]?"partial":"synced").put("fetchedAtMs",clock.now());
            guard.check(binding,true);synchronized(LOCK){JSONObject root=active(binding,revision);page.put("pendingCount",pending(root,binding));
                // Keep only the remote baseline: local pending turns are merged for
                // this caller, never embedded in a later cached/offline baseline.
                if(batch==null){JSONObject pages=root.getJSONObject("pages");pages.put(pageKey(binding,before,limit),copy(page));trimPages(pages);write(root);}
                else batch.pages.put(pageKey(binding,before,limit),copy(page));
                if(before==null)mergeLocal(page,binding,root);
            }
            return copy(page);
        }catch(Exception problem){guard.check(binding,true);synchronized(LOCK){JSONObject root=active(binding,revision);if(cached!=null){JSONObject fallback=copy(cached).put("stale",true).put("syncStatus","offline").put("pendingCount",pending(root,binding));if(before==null)mergeLocal(fallback,binding,root);return fallback;}
                JSONObject local=new JSONObject().put("binding",copy(binding)).put("entries",new JSONArray()).put("nextBeforeSeq",JSONObject.NULL).put("hasMore",false).put("stale",true).put("syncStatus","offline").put("pendingCount",pending(root,binding));
                if(before==null){mergeLocal(local,binding,root);if(local.getJSONArray("entries").length()>0)return local;}
            }throw fail("Could not load shared history. Check the connection and try again.");}
    }
    private void recoverOne(JSONObject binding)throws Exception {
        JSONObject record=null,owner=null;
        synchronized(LOCK){guard.check(binding,true);JSONObject root=store.read();if(!same(root.optJSONObject("binding"),binding))return;JSONObject out=root.optJSONObject("outbox");if(out==null)return;Iterator<String> ids=out.keys();JSONObject chosen=null;long oldest=Long.MAX_VALUE;
            while(ids.hasNext()){JSONObject item=out.getJSONObject(ids.next()),candidate=item.getJSONObject("binding");if(item.optString("state").equals("confirmed")||!candidate.optString("agentId").equals(binding.optString("agentId"))||!candidate.optString("sessionId").equals(binding.optString("sessionId")))continue;long order=item.optLong("recoveryOrder");if(order<oldest){oldest=order;chosen=item;}}
            if(chosen!=null){long order=root.optLong("recoveryCounter")+1;root.put("recoveryCounter",order);chosen.put("recoveryOrder",order);write(root);record=copy(chosen.getJSONObject("record"));owner=copy(chosen.getJSONObject("binding")).put("deadlineMs",binding.optLong("deadlineMs"));}}
        if(record!=null)record(new Context(owner,new JSONArray()),record);
    }
    private static void mergeLocal(JSONObject page,JSONObject binding,JSONObject root)throws Exception {
        JSONArray entries=page.getJSONArray("entries");Set<String> ids=new HashSet<>();List<JSONObject> rows=new ArrayList<>();for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);rows.add(row);ids.add(row.getString("id"));}
        JSONObject out=root.getJSONObject("outbox");Iterator<String> names=out.keys();while(names.hasNext()){
            JSONObject item=out.getJSONObject(names.next()),owner=item.getJSONObject("binding");if(!same(owner,binding)||!owner.optString("agentId").equals(binding.optString("agentId"))||!owner.optString("sessionId").equals(binding.optString("sessionId")))continue;
            JSONObject record=item.getJSONObject("record");String call=record.getString("callId");JSONArray turns=record.getJSONArray("turns");for(int i=0;i<turns.length();i++){JSONObject turn=turns.getJSONObject(i);String id=call+"/"+turn.getString("id");if(ids.add(id))rows.add(new JSONObject().put("id",id).put("role",turn.getString("speaker")).put("text",turn.getString("text")).put("atMs",turn.getLong("atMs")).put("source","voice").put("callId",call).put("syncStatus",item.optString("state").equals("confirmed")?"synced":"pending"));}
        }
        rows.sort((a,b)->Long.compare(a.optLong("atMs"),b.optLong("atMs")));JSONArray merged=new JSONArray();for(JSONObject row:rows)merged.put(row);page.put("entries",merged);
    }
    private JSONArray normalize(JSONArray raw,JSONObject binding,boolean[] incomplete,Map<String,JSONObject> voiceRecords)throws Exception {
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>(),cards=new HashSet<>();Set<String> expandIds=new HashSet<>();
        for(int i=raw.length()-1;i>=0&&expandIds.size()<2;i--){JSONObject e=raw.optJSONObject(i);if(e!=null&&e.optString("kind").equals("voice-call")&&e.optJSONObject("call")!=null)expandIds.add(e.getJSONObject("call").optString("callId"));}
        for(int i=0;i<raw.length();i++){JSONObject e=raw.optJSONObject(i);if(e!=null&&e.optString("kind").equals("voice-call")&&e.optJSONObject("call")!=null)cards.add(e.getJSONObject("call").optString("callId"));}
        // Details are independent reads. Start both before consuming either, then
        // normalize in transcript order; a failed detail still makes the page partial.
        Map<String,Future<Object>> details=new HashMap<>();ExecutorService reads=null;
        try {
        for(int i=0;i<Math.min(raw.length(),500);i++){
            JSONObject e=raw.optJSONObject(i);if(e==null||e.optString("id").isEmpty()||e.optString("id").length()>512)continue;
            if(e.opt("suppressed")!=null&&e.opt("suppressed")!=JSONObject.NULL||e.opt("fromAgent")!=null&&e.opt("fromAgent")!=JSONObject.NULL||e.opt("toAgent")!=null&&e.opt("toAgent")!=JSONObject.NULL||!e.optString("channel").isEmpty())continue;
            JSONObject summary=e.optJSONObject("call");if(!e.optString("kind").equals("voice-call")||summary==null)continue;
            String call=summary.optString("callId");if(call.isEmpty()||call.length()>512||!expandIds.contains(call)||details.containsKey(call)||voiceRecords.containsKey(call))continue;
            if(reads==null)reads=Executors.newFixedThreadPool(2);
            JSONObject requestBinding=copy(binding);String callId=call;
            details.put(call,reads.submit(()->{
                guard.check(requestBinding,true);
                if(clock.now()>=requestBinding.optLong("deadlineMs"))throw fail("Shared history took too long to load.");
                Object response=transport.request("getVoiceCall",new JSONObject().put("id",requestBinding.getString("agentId")).put("callId",callId),requestBinding);
                guard.check(requestBinding,true);
                return response instanceof JSONObject?copy((JSONObject)response):response;
            }));
        }
        for(int i=0;i<Math.min(raw.length(),500);i++){
            JSONObject e=raw.optJSONObject(i);if(e==null||e.optString("id").isEmpty()||e.optString("id").length()>512)continue;
            if(e.opt("suppressed")!=null&&e.opt("suppressed")!=JSONObject.NULL)continue;
            if(e.opt("fromAgent")!=null&&e.opt("fromAgent")!=JSONObject.NULL||e.opt("toAgent")!=null&&e.opt("toAgent")!=JSONObject.NULL)continue;
            if(!e.optString("channel").isEmpty())continue;
            String kind=e.optString("kind"),text="",role="system",source="text",call="";
            if(kind.equals("message")){if(e.opt("suppressed")!=null&&e.opt("suppressed")!=JSONObject.NULL||e.optBoolean("isStreaming"))continue;role=e.optString("role");if(!role.equals("user")&&!role.equals("assistant"))continue;if(role.equals("user")&&!ownUser(e,binding))continue;text=string(e,"content");call=voiceChannel(e.optString("channel"));}
            else if(kind.equals("send-message")){JSONObject m=e.optJSONObject("message");if(m==null||!m.optString("type").equals("text")||!m.optString("channel").isEmpty()||e.optBoolean("streaming"))continue;role="assistant";text=string(m,"content");call=voiceChannel(m.optString("channel"));}
            else if(kind.equals("notice"))text=string(e,"text");
            else if(kind.equals("voice-call")){
                JSONObject summary=e.optJSONObject("call");if(summary==null)continue;call=summary.optString("callId");if(call.isEmpty()||call.length()>512)continue;source="voice";
                if(expandIds.contains(call)){boolean consumed=false;try{long remaining=binding.optLong("deadlineMs")-clock.now();if(remaining<=0)throw fail("Shared history took too long to load.");
                    Object full=voiceRecords.containsKey(call)?copy(voiceRecords.get(call)):details.get(call).get(remaining,TimeUnit.MILLISECONDS);guard.check(binding,true);
                    if(full instanceof JSONObject){JSONObject record=(JSONObject)full;if(!call.equals(record.optString("callId"))||!binding.getString("agentId").equals(record.optString("agentId")))throw fail("Voice history belongs to another Bot.");JSONArray turns=record.optJSONArray("turns");if(turns!=null){voiceRecords.put(call,copy(record));consumed=true;for(int j=0;j<Math.min(turns.length(),256);j++){JSONObject turn=turns.optJSONObject(j);if(turn==null)continue;String speaker=turn.optString("speaker"),value=string(turn,"text");if(!speaker.equals("user")&&!speaker.equals("assistant")||value.isEmpty())continue;String id=call+"/"+turn.optString("id");if(seen.add(id))result.put(row(id,e,speaker,value,turn.optLong("atMs"),"voice",call));}continue;}}
                }catch(Exception ignored){if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();guard.check(binding,true);incomplete[0]=true;}if(!consumed)incomplete[0]=true;}
                long seconds=Math.max(0,summary.optLong("durationMs")/1000);text=String.format(Locale.ROOT,"Voice chat · %d:%02d",seconds/60,seconds%60);
            }else continue;
            if(text.isEmpty()||!call.isEmpty()&&source.equals("text")&&cards.contains(call))continue;
            if(!call.isEmpty())source="voice";String id=e.getString("id");if(seen.add(id+"@"+e.optString("seq")))result.put(row(id,e,role,text,e.optLong("timestampMs"),source,call));
        }
        return result;
        }finally{for(Future<Object> detail:details.values())detail.cancel(true);if(reads!=null)reads.shutdownNow();}
    }
    private static boolean ownUser(JSONObject entry,JSONObject binding)throws Exception {
        JSONObject author=entry.optJSONObject("author");
        if(author!=null)return author.optString("kind").equals("cursor_user")&&!binding.optString("authorKey").isEmpty()&&digest(author.optString("id")).equals(binding.optString("authorKey"));
        return binding.optBoolean("viewerIsOwner",true)&&entry.opt("fromUser")==null&&entry.opt("channelSender")==null;
    }
    private static JSONObject row(String id,JSONObject original,String role,String text,long at,String source,String call)throws Exception {
        JSONObject row=new JSONObject().put("id",id).put("role",role).put("text",text.length()>MAX_TEXT?text.substring(0,MAX_TEXT):text).put("atMs",Math.max(0,at)).put("source",source);
        Long seq=cursor(original.opt("seq"));if(seq!=null)row.put("seq",seq);if(!call.isEmpty())row.put("callId",call);return row;
    }
    JSONObject stage(Context context,JSONObject input)throws Exception {
        JSONObject binding=context.binding();validateBinding(binding);guard.check(binding,false);JSONObject record=canonicalRecord(binding,input);String call=record.getString("callId"),digest=digest(record.toString());
        synchronized(LOCK){guard.check(binding,false);JSONObject root=scope(binding);JSONObject out=root.getJSONObject("outbox"),old=out.optJSONObject(call);
            if(old!=null){if(!digest.equals(old.optString("digest")))throw fail("This call already has a different saved transcript.");return status(root,call,old.optString("state").equals("confirmed")?"synced":old.optString("state").equals("uncertain")?"uncertain":"pending",binding);}
            out.put(call,new JSONObject().put("binding",copy(binding)).put("record",record).put("digest",digest).put("state","staged"));write(root);return status(root,call,"pending",binding);
        }
    }
    JSONObject record(Context context,JSONObject input)throws Exception {
        stage(context,input);JSONObject binding=context.binding();validateBinding(binding);guard.check(binding,false);JSONObject record=canonicalRecord(binding,input);String call=record.getString("callId"),digest=digest(record.toString()),revision,inflightKey=binding.getString("accountKey")+"/"+binding.getString("authGeneration")+"/"+call;boolean reconcile=false;
        synchronized(LOCK){guard.check(binding,false);JSONObject root=scope(binding);revision=root.getString("revision");JSONObject out=root.getJSONObject("outbox"),existing=out.optJSONObject(call);
            if(existing!=null){if(!digest.equals(existing.optString("digest")))throw fail("This call already has a different saved transcript.");String state=existing.optString("state");if(state.equals("confirmed"))return status(root,call,"synced",binding);if(state.equals("attempting")&&INFLIGHT.contains(inflightKey))return status(root,call,"pending",binding);if(!state.equals("staged")){existing.put("state","uncertain");write(root);reconcile=true;}}
            else{out.put(call,new JSONObject().put("binding",copy(binding)).put("record",record).put("digest",digest).put("state","staged"));write(root);}
            if(!reconcile){out.getJSONObject(call).put("state","attempting");write(root);INFLIGHT.add(inflightKey);}
        }
        if(reconcile)return reconcile(binding,revision,call,record);
        String state="uncertain";
        try{guard.check(binding,false);transport.request("recordVoiceCall",new JSONObject().put("record",record),copy(binding));guard.check(binding,false);state="confirmed";}
        catch(Exception ignored){/* Once attempting is durable, every ambiguous failure remains uncertain. */}
        finally{synchronized(LOCK){INFLIGHT.remove(inflightKey);}}
        synchronized(LOCK){JSONObject root=active(binding,revision);root.getJSONObject("outbox").getJSONObject(call).put("state",state);write(root);return status(root,call,state.equals("confirmed")?"synced":"uncertain",binding);}
    }
    private JSONObject reconcile(JSONObject binding,String revision,String call,JSONObject expected)throws Exception {
        boolean confirmed=false;try{guard.check(binding,false);Object remote=transport.request("getVoiceCall",new JSONObject().put("id",binding.getString("agentId")).put("callId",call),copy(binding));guard.check(binding,false);confirmed=remote instanceof JSONObject&&containsFinalTurns(expected,(JSONObject)remote);}catch(Exception ignored){guard.check(binding,false);}
        synchronized(LOCK){JSONObject root=active(binding,revision);root.getJSONObject("outbox").getJSONObject(call).put("state",confirmed?"confirmed":"uncertain");write(root);return status(root,call,confirmed?"synced":"uncertain",binding);}
    }
    private static boolean containsFinalTurns(JSONObject expected,JSONObject remote)throws Exception {
        if(!expected.getString("callId").equals(remote.optString("callId"))||!expected.getString("agentId").equals(remote.optString("agentId")))return false;
        JSONArray turns=remote.optJSONArray("turns");if(turns==null)return false;Map<String,JSONObject> found=new HashMap<>();for(int i=0;i<turns.length();i++){JSONObject t=turns.optJSONObject(i);if(t!=null)found.put(t.optString("id"),t);}
        JSONArray wanted=expected.getJSONArray("turns");for(int i=0;i<wanted.length();i++){JSONObject t=wanted.getJSONObject(i),other=found.get(t.getString("id"));if(other==null||!t.getString("text").equals(other.optString("text"))||!t.getString("speaker").equals(other.optString("speaker"))||t.getLong("atMs")!=other.optLong("atMs",-1))return false;}return true;
    }
    private static JSONObject canonicalRecord(JSONObject binding,JSONObject input)throws Exception {
        String call=required(input,"callId",512);if(!binding.getString("agentId").equals(required(input,"agentId",512)))throw fail("Call belongs to another Bot.");
        String ending=required(input,"ending",32);if(!Arrays.asList("hung-up","disconnected","failed").contains(ending))throw fail("Unsupported call ending.");
        long started=number(input,"startedAtMs"),ended=number(input,"endedAtMs"),duration=number(input,"durationMs");if(ended<started||duration>ended-started+1000)throw fail("Unsupported call timing.");
        JSONArray raw=input.optJSONArray("turns");if(raw==null||raw.length()>256)throw fail("Call transcript is too large.");LinkedHashMap<String,JSONObject> unique=new LinkedHashMap<>();
        for(int i=0;i<raw.length();i++){JSONObject t=raw.getJSONObject(i);String id=required(t,"id",512),speaker=required(t,"speaker",16),text=required(t,"text",MAX_TEXT);if(!speaker.equals("user")&&!speaker.equals("assistant"))throw fail("Unsupported call speaker.");unique.put(id,new JSONObject().put("id",id).put("speaker",speaker).put("text",text).put("atMs",number(t,"atMs")));}
        JSONArray turns=new JSONArray();for(JSONObject t:unique.values())turns.put(t);
        JSONObject record=new JSONObject().put("callId",call).put("agentId",binding.getString("agentId")).put("model","").put("startedAtMs",started).put("endedAtMs",ended).put("durationMs",duration).put("ending",ending).put("turns",turns).put("nudges",new JSONArray()).put("harnessMayCollect",false);
        if(input.opt("realtimeConversationId") instanceof String)record.put("realtimeConversationId",required(input,"realtimeConversationId",1024));
        if(record.toString().getBytes(StandardCharsets.UTF_8).length>MAX_CALL)throw fail("Call transcript is too large to save safely.");return record;
    }
    void clear()throws Exception{synchronized(LOCK){store.write(new JSONObject().put("revision",UUID.randomUUID().toString()));}}
    private JSONObject scope(JSONObject binding)throws Exception {JSONObject root=store.read();if(!same(root.optJSONObject("binding"),binding)){root=new JSONObject().put("schema",1).put("revision",UUID.randomUUID().toString()).put("binding",copy(binding)).put("pages",new JSONObject()).put("outbox",new JSONObject());write(root);}return root;}
    private JSONObject active(JSONObject binding,String revision)throws Exception{JSONObject root=store.read();if(!same(root.optJSONObject("binding"),binding)||!revision.equals(root.optString("revision")))throw fail("Grok account changed. Nothing else was synced.");return root;}
    private static boolean same(JSONObject a,JSONObject b){return a!=null&&a.optString("accountKey").equals(b.optString("accountKey"))&&a.optString("authGeneration").equals(b.optString("authGeneration"));}
    private static String pageKey(JSONObject b,Long before,int limit){return b.optString("agentId")+"\n"+b.optString("sessionId")+"\n"+(before==null?"tail":before)+"/"+limit;}
    private static JSONObject cached(JSONObject root,JSONObject binding,Long before,int limit)throws Exception{return root.getJSONObject("pages").optJSONObject(pageKey(binding,before,limit));}
    /** Newest saved tail page of this conversation, whatever page size fetched it. */
    private static JSONObject newest(JSONObject root,JSONObject binding)throws Exception{
        JSONObject pages=root.getJSONObject("pages"),best=null;String prefix=binding.optString("agentId")+"\n"+binding.optString("sessionId")+"\ntail/";Iterator<String> keys=pages.keys();
        while(keys.hasNext()){String key=keys.next();JSONObject page=pages.optJSONObject(key);if(page!=null&&key.startsWith(prefix)&&(best==null||page.optLong("fetchedAtMs")>best.optLong("fetchedAtMs")))best=page;}
        return best;
    }
    /** Saved newest page for immediate display; never contacts Grok. Null when nothing is saved. */
    JSONObject savedTail(JSONObject binding)throws Exception{
        validateBinding(binding);guard.check(binding,true);
        synchronized(LOCK){JSONObject root=store.read();if(!same(root.optJSONObject("binding"),binding)||root.optJSONObject("pages")==null)return null;
            JSONObject page=newest(root,binding);if(page==null)return null;
            JSONObject view=copy(page).put("stale",true).put("syncStatus","saved").put("pendingCount",pending(root,binding));mergeLocal(view,binding,root);return view;}
    }
    private static void trimPages(JSONObject pages){while(pages.length()>6){Iterator<String> keys=pages.keys();String oldest=null;long time=Long.MAX_VALUE;while(keys.hasNext()){String key=keys.next();JSONObject page=pages.optJSONObject(key);if(page!=null&&page.optLong("fetchedAtMs")<time){oldest=key;time=page.optLong("fetchedAtMs");}}if(oldest==null)break;pages.remove(oldest);}}
    private static void pruneConfirmed(JSONObject root)throws Exception {
        JSONObject out=root.optJSONObject("outbox");if(out==null)return;
        while(true){int count=0;String oldest=null;long earliest=Long.MAX_VALUE;Iterator<String> keys=out.keys();while(keys.hasNext()){String key=keys.next();JSONObject item=out.getJSONObject(key);if(!item.optString("state").equals("confirmed"))continue;count++;long ended=item.getJSONObject("record").optLong("endedAtMs");if(ended<earliest){earliest=ended;oldest=key;}}if(count<=8||oldest==null)return;out.remove(oldest);}
    }
    private void write(JSONObject root)throws Exception {pruneConfirmed(root);while(root.toString().getBytes(StandardCharsets.UTF_8).length>MAX_STORE&&root.optJSONObject("pages")!=null&&root.getJSONObject("pages").length()>0){Iterator<String> keys=root.getJSONObject("pages").keys();root.getJSONObject("pages").remove(keys.next());}if(root.toString().getBytes(StandardCharsets.UTF_8).length>MAX_STORE)throw fail("Watch history storage is full. Existing unsynced calls were kept.");store.write(root);}
    private static int pending(JSONObject root,JSONObject binding){JSONObject out=root.optJSONObject("outbox");if(out==null)return 0;int count=0;Iterator<String> keys=out.keys();while(keys.hasNext()){JSONObject e=out.optJSONObject(keys.next());if(e!=null&&!e.optString("state").equals("confirmed")){JSONObject owner=e.optJSONObject("binding");if(owner!=null&&same(owner,binding)&&owner.optString("agentId").equals(binding.optString("agentId"))&&owner.optString("sessionId").equals(binding.optString("sessionId")))count++;}}return count;}
    private static JSONObject status(JSONObject root,String call,String state,JSONObject binding)throws Exception{return new JSONObject().put("callId",call).put("syncStatus",state).put("pendingCount",pending(root,binding));}
    private static void validateBinding(JSONObject b)throws Exception {required(b,"accountKey",128);required(b,"authGeneration",128);required(b,"agentId",512);Object session=b.opt("sessionId");if(session!=null&&(!(session instanceof String)||((String)session).length()>512||((String)session).contains("\n")))throw fail("Unsupported conversation binding.");}
    private static String required(JSONObject o,String key,int max)throws Exception {String value=string(o,key);if(value.trim().isEmpty()||value.length()>max||value.indexOf('\n')>=0&&(!key.equals("text")))throw fail("Unsupported history value.");return value;}
    private static String string(JSONObject o,String key){return o.opt(key) instanceof String?o.optString(key):"";}
    private static long number(JSONObject o,String key)throws Exception{Long value=cursor(o.opt(key));if(value==null)throw fail("Unsupported history timestamp.");return value;}
    private static Long cursor(Object value)throws Exception{if(value==null||value==JSONObject.NULL)return null;if(!(value instanceof Number))throw fail("Unsupported history cursor.");double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n<0||n>9_007_199_254_740_991L||Math.floor(n)!=n)throw fail("Unsupported history cursor.");return ((Number)value).longValue();}
    private static String voiceChannel(String channel){return channel.startsWith("voice:")?channel.substring(6):"";}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static String digest(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    private static GrokCore.Failure fail(String safe){return new GrokCore.Failure(safe,true);}
}
