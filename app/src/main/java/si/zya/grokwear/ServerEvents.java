package si.zya.grokwear;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Grok's server stream of Bot state (WatchGrokBotTranscripts), for Bots run on Grok's server. It runs beside
 * EventStream while that has listeners and the account has such Bots, and hands them the same Bot rows: unread
 * counts, previews and working state now come from the server, as in Grok Bot Android 1.25.0, and a turn that failed
 * for usage becomes the same usage-limit notice a gateway tray gives (UsageTrays). Reconnects like the
 * official client: soon after a clean end, with growing backoff after errors.
 */
final class ServerEvents {
    private ServerEvents(){}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Object WAKE=new Object();
    /** How often a stream with no server Bots checks again whether the account has some. */
    private static final long GATE_RECHECK_MS=60_000L;
    private static Context app;private static long generation;private static boolean running,kicked;
    private static volatile okhttp3.Call live;private static volatile boolean open,superseded;
    /** The chats the stream follows; a new one reconnects the stream with it after a short pause, as the official client does. */
    private static final ServerTranscripts.Scopes SCOPES=new ServerTranscripts.Scopes();
    private static final long REDIAL_DEBOUNCE_MS=250;
    private static final Runnable redial=()->{okhttp3.Call call=live;if(call!=null&&open){superseded=true;call.cancel();}};
    /** A chat page the watch just read: follow that chat's new entries from there. */
    /** Debug builds: start following this many entries back, so the server replays them (a live check that sends nothing). */
    static volatile int debugReplay;
    static void follow(String agentId,long[] marks){
        if(BuildConfig.DEBUG&&debugReplay>0){marks=new long[]{marks[0],Math.max(0,marks[1]-debugReplay),Math.max(0,marks[2]-debugReplay)};debugReplay=0;}
        if(SCOPES.adopt(agentId,marks)){MAIN.removeCallbacks(redial);MAIN.postDelayed(redial,REDIAL_DEBOUNCE_MS);}
    }
    /** True when this chat's entries come from the server stream, so the gateway's copies can be skipped. */
    static boolean covers(String agentId){return open&&SCOPES.has(agentId);}

    static synchronized void start(Context context){
        if(running){kick();return;}
        app=context.getApplicationContext();running=true;final long mine=++generation;
        Thread thread=new Thread(()->run(mine),"grok-server-events");thread.setDaemon(true);thread.start();
    }
    static synchronized void stop(){
        if(!running)return;running=false;generation++;open=false;BotAccount.SERVER_STATES.clear();SCOPES.clear();
        okhttp3.Call call=live;if(call!=null)call.cancel();kick();
    }
    /** The account or network changed: drop this connection and dial again. */
    static void restart(){okhttp3.Call call=live;if(call!=null)call.cancel();BotAccount.SERVER_STATES.clear();SCOPES.clear();kick();}
    /** True while connected: the server's Bot state then overrides the gateway's. */
    static boolean isOpen(){return open;}
    private static synchronized boolean current(long mine){return running&&generation==mine;}
    private static synchronized void claim(long mine,okhttp3.Call call){if(current(mine))live=call;else call.cancel();}
    private static synchronized void release(long mine,okhttp3.Call call){if(call!=null&&live==call)live=null;if(current(mine))open=false;}

    private static void run(long mine){
        BotAccount account=new BotAccount(app);int attempts=0;
        while(current(mine)){
            boolean needed;try{needed=account.hasServerBots();}catch(Exception unreadable){needed=false;}
            if(!needed){pause(GATE_RECHECK_MS);continue;}
            long began=SystemClock.elapsedRealtime();String ended="clean";okhttp3.Call[] call={null};boolean[] activated={false};String[] identity={""};
            superseded=false;
            try{account.watchServer(SCOPES.cursors(),new BotAccount.ServerSink(){
                public void opened(okhttp3.Call made){call[0]=made;claim(mine,made);}
                public void connected(String who){identity[0]=who;}
                public boolean frame(JSONObject frame)throws Exception{
                    if(!current(mine))return false;
                    // Like the official client, nothing counts until the server says the stream is connected.
                    if(frame.has("connected")){activated[0]=true;synchronized(ServerEvents.class){if(current(mine))open=true;}
                        android.util.Log.i("GrokWearServer","server_events_open open_ms="+(SystemClock.elapsedRealtime()-began));return true;}
                    if(!activated[0])return true;
                    if(BuildConfig.DEBUG){java.util.Iterator<String> keys=frame.keys();String kind=keys.hasNext()?keys.next():"empty";String shape=BotAccount.shapeOf(frame,"");android.util.Log.i("GrokWearServer","frame "+kind+" "+shape.substring(0,Math.min(1500,shape.length())));}
                    JSONObject state=frame.optJSONObject("agentState");
                    if(state!=null){for(String id:BotAccount.SERVER_STATES.apply(state))publish(mine,identity[0],account.serverRow(id,null));return true;}
                    JSONObject roster=frame.optJSONObject("rosterChanged");
                    if(roster!=null){String id=GrokCore.text(roster,"agentId");if(!id.isEmpty())publish(mine,identity[0],account.serverRow(id,roster.optJSONObject("agent")));return true;}
                    // A followed chat's new or changed entries, handed on like the gateway's transcript events.
                    JSONObject rows=frame.optJSONObject("rows");
                    if(rows!=null){entries(mine,identity[0],rows);return true;}
                    // The chat was cleared, or the server can't replay from where the stream left off: read it afresh.
                    JSONObject reset=frame.optJSONObject("cleared");if(reset==null)reset=frame.optJSONObject("cursorTooOld");
                    if(reset!=null){String id=GrokCore.text(reset,"agentId");if(!id.isEmpty()&&GrokCore.text(reset,"sessionId").isEmpty()){SCOPES.forget(id);gap(mine,identity[0]);}return true;}
                    // A server Bot out of usage: the same notice a gateway tray gives a box Bot.
                    JSONObject failed=frame.optJSONObject("turnFailed");
                    if(failed!=null){usage(mine,identity[0],ServerTranscripts.turnFailed(failed));return true;}
                    return true;
                }
            });}
            catch(GrokCore.Failure refused){if(BotAccount.SIGNED_OUT.equals(refused.code)){release(mine,call[0]);pause(GATE_RECHECK_MS);continue;}ended="refused_"+refused.status;}
            catch(Exception dropped){ended=dropped.getClass().getSimpleName();}
            finally{release(mine,call[0]);}
            if(!current(mine))return;
            // Reconnecting to follow another chat isn't a failure: dial again at once.
            if(superseded){superseded=false;continue;}
            android.util.Log.i("GrokWearServer","server_events_end after_ms="+(SystemClock.elapsedRealtime()-began)+" cause="+ended);
            // A clean end reconnects in 0.5 to 3 s; errors back off from 1 s to a minute, with jitter.
            long wait;
            if(ended.equals("clean")){attempts=0;wait=500+Math.round(Math.random()*2500);}
            else{attempts++;wait=Math.round(Math.min(1000L<<Math.min(attempts-1,10),60_000L)*(0.5+0.5*Math.random()));}
            pause(wait);
        }
    }
    /** A rows frame for a followed main chat: each new or changed entry, oldest change first, as a gateway transcript event. */
    private static void entries(long mine,String identity,JSONObject rows)throws Exception {
        String id=GrokCore.text(rows,"agentId");if(id.isEmpty()||!GrokCore.text(rows,"sessionId").isEmpty())return;
        long generation=Math.max(0,ServerTranscripts.seqOf(rows.opt("generation")));
        JSONArray list=rows.optJSONArray("entries");if(list==null)return;
        java.util.List<JSONObject> sorted=new java.util.ArrayList<>();for(int i=0;i<list.length();i++)if(list.optJSONObject(i)!=null)sorted.add(list.getJSONObject(i));
        sorted.sort((a,b)->Long.compare(ServerTranscripts.seqOf(a.opt("updatedSeq")),ServerTranscripts.seqOf(b.opt("updatedSeq"))));
        for(JSONObject row:sorted){
            JSONObject entry=ServerTranscripts.entryOf(row);if(entry==null)continue;
            String type=SCOPES.row(id,generation,row);if(type==null)continue;
            BotEvents.Event event=BotEvents.parse(new JSONObject().put("channel","transcript")
                .put("payload",new JSONObject().put("type",type).put("agentId",id).put("entry",entry)).toString());
            if(BuildConfig.DEBUG)android.util.Log.i("GrokWearServer","row "+type+" kind="+GrokCore.text(entry,"kind")+" event="+(event==null?"none":event.kind+(event.value.optBoolean("reply")?" reply":event.value.optBoolean("user")?" user":"")));
            if(event==null||identity.isEmpty())continue;
            MAIN.post(()->{if(!current(mine))return;for(EventStream.Listener l:EventStream.listeners())l.entry(identity,event.botId,event.value);});
        }
    }
    /** Entries may have been missed: listeners read again the usual way, as after a gap in the gateway's stream. */
    private static void gap(long mine,String identity){
        if(identity.isEmpty())return;
        MAIN.post(()->{if(!current(mine))return;for(EventStream.Listener l:EventStream.listeners())l.connected(identity,true);});
    }
    /** Hands a Bot row to EventStream's listeners on the main thread, as a gateway row would be (and clears a notice read elsewhere). */
    private static void publish(long mine,String identity,JSONObject row){
        if(row==null||identity.isEmpty())return;
        MAIN.post(()->{if(!current(mine))return;ReplyAlertService.viewed(app,row);for(EventStream.Listener l:EventStream.listeners())l.bot(identity,row);});
    }
    /** Keeps a usage-limit notice (UsageTrays) and, if it's news, tells EventStream's listeners on the main thread. */
    private static void usage(long mine,String identity,BotEvents.Event event){
        if(event==null||identity.isEmpty()||!UsageTrays.ALL.apply(identity,event))return;JSONObject pushed=event.value.optJSONObject("tray");
        MAIN.post(()->{if(!current(mine))return;for(EventStream.Listener l:EventStream.listeners())l.usage(identity,pushed);});
    }
    private static void pause(long ms){
        synchronized(WAKE){long end=SystemClock.elapsedRealtime()+ms;
            while(!kicked){long left=end-SystemClock.elapsedRealtime();if(left<=0)break;try{WAKE.wait(left);}catch(InterruptedException stop){break;}}
            kicked=false;}
    }
    private static void kick(){synchronized(WAKE){kicked=true;WAKE.notifyAll();}}
}
