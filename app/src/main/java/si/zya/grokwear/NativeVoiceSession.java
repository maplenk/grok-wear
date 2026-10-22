package si.zya.grokwear;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One bounded, non-reconnecting, read-only preview call. Never logs or persists audio/secrets. */
public final class NativeVoiceSession {
    public interface Listener {
        void onState(String state);
        void onAudio(byte[] pcm);
        void onCaption(String who,String text);
        void onSpeechStarted();
        default void onPlaybackInterrupted(){}
        void onComplete(String reason);
        void onFailure(String safeError);
        default void onDisconnected(String safeError){onFailure(safeError);}
        void onReady();
        default boolean hasUnheardReplyAudio(){return false;}
    }
    private static final long MAX_QUEUE=256L*1024;
    private static final int MAX_EVENT=512*1024;
    private static final Set<String> READ_TOOLS=Set.of("recall_text_messages","search_conversations","stay_silent","end_the_call");
    private final Object callbackGuard=new Object();
    interface Backend {JSONObject request(String method,JSONObject body)throws Exception;}
    interface Wire {boolean send(String text);long queueSize();void cancel();}
    interface WireEvents {void opened();void message(String text);void failed(Integer httpStatus);void closed();}
    interface WireFactory {Wire open(HttpUrl url,String secret,WireEvents events);}
    interface PcmCodec {String encode(byte[] pcm);byte[] decode(String text);}
    private final Backend backend;
    private final WireFactory wireFactory;
    private final PcmCodec codec;
    private final String botId,botName;
    private final String callId=java.util.UUID.randomUUID().toString();
    private final Listener listener;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    // Independent of blocking account HTTP work: a stalled harness/mint cannot postpone startup expiry.
    private final ScheduledExecutorService startupWatchdog=Executors.newSingleThreadScheduledExecutor();
    private final long startupTimeoutMillis;
    private volatile java.util.concurrent.ScheduledFuture<?> startupDeadline;
    private final java.util.concurrent.ThreadPoolExecutor lookups=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8));
    private final Semaphore pcmSlots=new Semaphore(32), eventSlots=new Semaphore(32);
    private final AtomicBoolean terminal=new AtomicBoolean(), started=new AtomicBoolean();
    private final Set<String> offered=new HashSet<>(), calls=new HashSet<>();
    // Pings detect a silently dead socket (for example a Wi-Fi handover) without a read timeout
    // that would also end quiet listening periods. VoiceService reports outright network loss sooner.
    private final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
            .readTimeout(0,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private volatile Wire socket;
    private volatile boolean muted,ending;
    private boolean endingAnnounced;
    private JSONObject plan;
    private JSONObject preparedPlan,preparedCredential;
    interface StartupGuard {void check()throws Exception;}
    private StartupGuard startupGuard=()->{};
    void preparedStartup(JSONObject plan,JSONObject credential,StartupGuard guard)throws Exception {
        if(started.get())throw new IllegalStateException("Startup must precede start");
        preparedPlan=new JSONObject(plan.toString());preparedCredential=new JSONObject(credential.toString());startupGuard=guard;
    }
    private JSONArray historicalTurns=new JSONArray();
    private boolean historyAvailable=true,historySeeded;
    private final AtomicBoolean deferredHistorySubmitted=new AtomicBoolean();
    private final Object historyGuard=new Object();
    private final java.util.LinkedHashMap<String,JSONObject> spokenTurns=new java.util.LinkedHashMap<>();
    private String realtimeConversationId,assistantItem="",userItem="";
    private long connectedAtMs;
    private int transcriptChars;
    private final Set<String> currentReplyItems=new HashSet<>(),finalizedTurns=new HashSet<>();
    private boolean suppressReplyTranscript;
    /** A conservative omission: generated words not fully heard must not become shared memory. */
    public void discardUnheardReply(){synchronized(historyGuard){suppressReplyTranscript=true;for(String id:currentReplyItems){finalizedTurns.remove(id);JSONObject old=spokenTurns.remove(id);if(old!=null)transcriptChars-=old.optString("text").length();}currentReplyItems.clear();}}
    private volatile boolean didConnect;
    /** Recent shared turns are context only; never execute/re-record them as new turns. */
    public NativeVoiceSession(BotAccount account,JSONObject selectedBot,JSONArray history,Listener listener){
        this(account,selectedBot,listener);setHistory(history);
    }
    void setHistory(JSONArray history){
        if(started.get())throw new IllegalStateException("History must precede start");
        try{historicalTurns=history==null?new JSONArray():new JSONArray(history.toString());}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid history");}
    }
    /** Open the transport while fresh history loads, but never admit audio with missing history. */
    void deferHistory(){
        if(started.get())throw new IllegalStateException("History mode must precede start");
        historyAvailable=false;
    }
    void provideHistory(JSONArray history)throws Exception {
        if(terminal.get())return;
        if(!started.get()||!deferredHistorySubmitted.compareAndSet(false,true))throw new IllegalStateException("History may only be delivered once after start");
        if(history==null)throw new IllegalArgumentException("Missing history");
        JSONArray copy=new JSONArray(history.toString());historyFrames(copy);
        execute(()->{
            if(historyAvailable||historySeeded)throw new IllegalStateException("History was already supplied");
            historicalTurns=copy;historyAvailable=true;seedHistoryIfReady();
        });
    }
    public boolean didConnect(){return didConnect;}
    /** Conversation state the harness expects alongside the end-of-call handoff; read after the call ended. */
    public JSONObject handoffLine()throws Exception{
        return new JSONObject().put("theyJustTalked",callerSpoke).put("newsOwed",newsOwed).put("receiptOwed",receiptOwed).put("alreadySpokeThisTurn",alreadySpoke);
    }
    public JSONObject recordSnapshot(String ending,long endedAtMs,long durationMs)throws Exception {
        synchronized(historyGuard){
            JSONArray turns=new JSONArray();for(java.util.Map.Entry<String,JSONObject> entry:spokenTurns.entrySet())if(finalizedTurns.contains(entry.getKey()))turns.put(new JSONObject(entry.getValue().toString()));
            return new JSONObject().put("callId",callId).put("agentId",botId).put("model","")
                .put("realtimeConversationId",realtimeConversationId==null?JSONObject.NULL:realtimeConversationId)
                .put("startedAtMs",connectedAtMs).put("endedAtMs",endedAtMs).put("durationMs",Math.max(0,durationMs))
                .put("ending",ending).put("turns",turns).put("nudges",new JSONArray()).put("harnessMayCollect",false);
        }
    }
    static JSONArray historyFrames(JSONArray turns)throws Exception {
        JSONArray frames=new JSONArray();int chars=0;
        if(turns.length()>32)throw new IllegalArgumentException("History limit");
        for(int i=0;i<turns.length();i++){
            JSONObject t=turns.getJSONObject(i);String role=t.optString("speaker",t.optString("role","")),text=t.optString("text","");
            if(!role.equals("user")&&!role.equals("assistant"))throw new IllegalArgumentException("Invalid history role");
            if(text.isBlank())continue;chars+=text.length();if(text.length()>8192||chars>32000)throw new IllegalArgumentException("History limit");
            frames.put(new JSONObject().put("type","conversation.item.create").put("item",new JSONObject()
                .put("type","message").put("role",role).put("content",new JSONArray().put(new JSONObject()
                .put("type",role.equals("user")?"input_text":"output_text").put("text",text)))));
        }return frames;
    }
    private void captureTurn(JSONObject event,String who,boolean delta)throws Exception {
        String id=event.optString("item_id","");
        if(id.isEmpty()){
            if(who.equals("user")){if(userItem.isEmpty())userItem="user-"+java.util.UUID.randomUUID();id=userItem;}
            else {if(assistantItem.isEmpty())assistantItem="assistant-"+java.util.UUID.randomUUID();id=assistantItem;}
        }
        String text=event.optString(delta?"delta":"transcript","");
        synchronized(historyGuard){
            if(terminal.get())return;
            if(who.equals("assistant")){if(suppressReplyTranscript)return;currentReplyItems.add(id);}
            JSONObject old=spokenTurns.get(id);if(delta&&old!=null)text=old.optString("text")+text;
            if(text.isBlank())return;int before=old==null?0:old.optString("text").length();
            if((old==null&&spokenTurns.size()>=256)||text.length()>8000||transcriptChars-before+text.length()>64000)
                throw new IllegalArgumentException("Transcript limit");
            transcriptChars+=text.length()-before;
            if(event.optString("type").endsWith(".done")||event.optString("type").endsWith(".completed"))finalizedTurns.add(id);
            spokenTurns.put(id,new JSONObject().put("id",id).put("speaker",who).put("text",text)
                .put("atMs",old==null?System.currentTimeMillis():old.getLong("atMs")));
        }
    }
    private volatile long received,protocolErrors;
    private volatile String lastProtocolClass="none",lastProtocolCode="none";
    private String activeResponseId="";
    private boolean interruptedReply,cancelRequested;
    private long callerTurn;
    private final Set<String> interruptedItems=new HashSet<>();
    /** Fixed diagnostic tokens and counters only; never server messages, IDs or transcripts. */
    // Content-free call counters for the call_stop log line: sizes, counts and protocol event names only.
    private volatile long pcmSent,speechStarts,audioOut,heard,toolCalls;private volatile int seeded,toolsOffered,instructionChars;private volatile String toolNames="",seedShape="";
    private final java.util.Map<String,Integer> eventTypes=new java.util.LinkedHashMap<>();private volatile int eventsAfterReady;
    // Microphone level only: the loudest sample, frames with speech-level sound, and frames of pure zeros.
    private volatile int micPeak;private volatile long micFrames,loudFrames,zeroFrames;
    // Phase times in ms from start(): socket open, settings accepted, ready; and speech end to first reply audio.
    private volatile long startedNanos,openMs=-1,updatedMs=-1,readyMs=-1,speechStoppedNanos,lastTtfaMs=-1,maxTtfaMs=-1;
    private long sinceStart(){return startedNanos==0?-1:(System.nanoTime()-startedNanos)/1_000_000;}
    public String protocolDiagnostics(){String types;synchronized(eventTypes){types=eventTypes.toString();}
        return "received="+received+",errors="+protocolErrors+",class="+lastProtocolClass+",code="+lastProtocolCode
        +",ready="+ready+",muted="+muted+",pcm="+pcmSent+",speech="+speechStarts+",heard="+heard+",responses="+responsesSeen+",audio_out="+audioOut
        +",tool_calls="+toolCalls+",seeded="+seeded+"["+seedShape+"],tools="+toolsOffered+"["+toolNames+"],instructions="+instructionChars
        +",open_ms="+openMs+",updated_ms="+updatedMs+",ready_ms="+readyMs+",ttfa_ms="+lastTtfaMs+",ttfa_max_ms="+maxTtfaMs
        +",after_ready="+eventsAfterReady+",mic_peak="+micPeak+",mic_frames="+micFrames+",loud="+loudFrames+",zero="+zeroFrames+",events="+types;}
    /** Roles, sizes and character classes of the seeded history, never its text. */
    static String seedShape(JSONArray frames){
        StringBuilder roles=new StringBuilder();int total=0,max=0,nonBmp=0,control=0,lone=0;
        for(int i=0;i<frames.length();i++){JSONObject item=frames.optJSONObject(i)==null?null:frames.optJSONObject(i).optJSONObject("item");if(item==null)continue;
            roles.append("user".equals(item.optString("role"))?'U':'A');JSONArray content=item.optJSONArray("content");String text=content==null||content.optJSONObject(0)==null?"":content.optJSONObject(0).optString("text");
            total+=text.length();max=Math.max(max,text.length());
            for(int c=0;c<text.length();c++){char ch=text.charAt(c);
                if(Character.isHighSurrogate(ch)){if(c+1<text.length()&&Character.isLowSurrogate(text.charAt(c+1))){nonBmp++;c++;}else lone++;}
                else if(Character.isLowSurrogate(ch))lone++;else if(ch<32&&ch!='\n'&&ch!='\t')control++;}}
        return roles+" chars="+total+" max="+max+" nonbmp="+nonBmp+" lone="+lone+" ctrl="+control;
    }
    private static String responseId(JSONObject event){
        JSONObject response=event.optJSONObject("response");
        String id=event.optString("response_id",response==null?"":response.optString("id",""));
        if(id.length()>256)throw new IllegalArgumentException("Response identifier limit");
        return id;
    }
    private static void validateIdentifier(JSONObject object,String key){
        if(object==null)return;Object value=object.opt(key);
        if(value!=null&&value!=JSONObject.NULL&&(!(value instanceof String)||((String)value).length()>256))
            throw new IllegalArgumentException("Voice identifier limit");
    }
    private static void validateIdentifiers(JSONObject event){
        for(String key:new String[]{"response_id","item_id","call_id"})validateIdentifier(event,key);
        validateIdentifier(event.optJSONObject("response"),"id");
        validateIdentifier(event.optJSONObject("item"),"id");
        validateIdentifier(event.optJSONObject("conversation"),"id");
    }
    private boolean belongsToCurrentReply(JSONObject event){
        String id=responseId(event);
        return id.isEmpty()||activeResponseId.isEmpty()||id.equals(activeResponseId);
    }
    private void interruptReply(){
        if(replyInFlight||listener.hasUnheardReplyAudio()){
            synchronized(historyGuard){
                // Bounded by the same call-wide transcript item limit.
                if(interruptedItems.size()+currentReplyItems.size()<=256)interruptedItems.addAll(currentReplyItems);
                discardUnheardReply();
            }
        }
        interruptedReply=true;
    }
    private void protocolError(JSONObject event){
        JSONObject fault=event.optJSONObject("error");if(fault==null)fault=event;
        String kind=fault.optString("type","");
        lastProtocolClass=Set.of("invalid_request_error","server_error","authentication_error","rate_limit_error").contains(kind)?kind:"unrecognized";
        String code=fault.optString("code","");
        lastProtocolCode=Set.of("internal_error","async_not_enabled","invalid_request_error","missing_tool_call_results").contains(code)?code:"unrecognized";
        protocolErrors++;
        if(!ready||!settingsAccepted||"authentication_error".equals(kind))fail("Grok refused the voice session or one of its requests.");
        // Live request errors do not imply a closed session. Preserve turn ownership;
        // a subsequent response event or socket close provides the definitive state.
    }
    private volatile boolean ready;
    private volatile boolean settingsAccepted,settingsSent;
    /** A call is live once Grok confirmed its settings; a drop before that is a failed start, not a dropped call. */
    private boolean live(){return ready&&settingsAccepted;}
    boolean isLive(){return live();}
    static final long SETTINGS_DEADLINE_MS=10000;
    private final Set<String> pendingHistory=new HashSet<>();
    private boolean callerSpeaking,replyInFlight,callerSpoke,alreadySpoke,newsOwed,receiptOwed,toolReplyOwed,announcedSpeaking;
    /** Lookups started in the current caller turn; older turns' lookups cannot produce its answer. */
    private long responsesSeen;private int pendingLookups;
    static final long THINKING_FALLBACK_MS=8000;
    volatile long thinkingFallbackMs=THINKING_FALLBACK_MS;

    public NativeVoiceSession(BotAccount account,JSONObject selectedBot,Listener listener) {
        this(account,selectedBot,listener,30000);
    }
    NativeVoiceSession(BotAccount account,JSONObject selectedBot,Listener listener,long timeoutMillis) {
        this(account::backend,selectedBot,null,new PcmCodec(){
            public String encode(byte[] pcm){return Base64.encodeToString(pcm,Base64.NO_WRAP);}
            public byte[] decode(String text){return Base64.decode(text,Base64.DEFAULT);}
        },listener,timeoutMillis);
    }
    NativeVoiceSession(Backend backend,JSONObject selectedBot,WireFactory factory,PcmCodec codec,Listener listener){
        this(backend,selectedBot,factory,codec,listener,30000);
    }
    NativeVoiceSession(Backend backend,JSONObject selectedBot,WireFactory factory,PcmCodec codec,Listener listener,long startupTimeoutMillis){
        if(startupTimeoutMillis<=0)throw new IllegalArgumentException("Startup deadline must be positive");
        this.startupTimeoutMillis=startupTimeoutMillis;
        this.backend=backend;this.listener=listener;this.codec=codec;
        this.wireFactory=factory==null?this::openRealWire:factory;
        botId=selectedBot.optString("id","").trim();botName=selectedBot.optString("name","").trim();
    }
    private Wire openRealWire(HttpUrl url,String secret,WireEvents events){
        Request request=new Request.Builder().url(url).header("Sec-WebSocket-Protocol","xai-client-secret."+secret).build();
        WebSocket websocket=http.newWebSocket(request,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){events.opened();}
            @Override public void onMessage(WebSocket ws,String text){events.message(text);}
            @Override public void onMessage(WebSocket ws,ByteString bytes){fail("Grok voice returned an unexpected audio format.");}
            @Override public void onFailure(WebSocket ws,Throwable error,Response response){events.failed(response==null?null:response.code());}
            @Override public void onClosed(WebSocket ws,int code,String reason){events.closed();}
        });
        return new Wire(){public boolean send(String text){return websocket.send(text);}public long queueSize(){return websocket.queueSize();}public void cancel(){websocket.cancel();}};
    }
    public void start() {
        if(!started.compareAndSet(false,true)||terminal.get())return;startedNanos=System.nanoTime();
        startupDeadline=startupWatchdog.schedule(()->fail("Grok voice did not become ready in time."),startupTimeoutMillis,TimeUnit.MILLISECONDS);
        worker.schedule(()->finish("Call preview reached its five-minute limit.",false),5,TimeUnit.MINUTES);
        execute(()->{
            if(botId.isEmpty()||botName.isEmpty()){fail("Choose a Grok Bot before starting voice.");return;}
            emit(()->listener.onState("Connecting · read-only voice preview"));
            try {
                if(terminal.get())return;
                startupGuard.check();
                plan=preparedPlan!=null?preparedPlan:backend.request("VoiceCallHarnessSession",new JSONObject().put("agentId",botId).put("agentName",botName));
                preparedPlan=null;
                if(terminal.get())return;
                JSONArray tools=plan.optJSONArray("tools");
                JSONArray filtered=new JSONArray();
                if(tools!=null)for(int i=0;i<tools.length();i++) {
                    JSONObject tool=tools.optJSONObject(i);
                    if(tool==null)continue;
                    String name=tool.optString("name","");
                    if(READ_TOOLS.contains(name)){filtered.put(new JSONObject(tool.toString()));offered.add(name);}
                }
                plan.put("tools",filtered);
                if(plan.optString("instructions","").isEmpty())throw new IllegalArgumentException();
                if(terminal.get())return;
                JSONObject credential=preparedCredential!=null?preparedCredential:backend.request("MintSandVoiceCallSecret",new JSONObject());
                preparedCredential=null;
                if(terminal.get())return;
                String secret=credential.optString("clientSecret","");
                String model=credential.optString("model","");
                if(credential.optLong("expiresAtUnixSeconds",0)<=System.currentTimeMillis()/1000)throw new IllegalArgumentException();
                if(secret.isEmpty()||model.isEmpty()||!secret.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw new IllegalArgumentException();
                HttpUrl url=voiceUrl(credential.getString("websocketUrl"),model);
                if(terminal.get())return;
                startupGuard.check();
                if(terminal.get())return;
                socket=wireFactory.open(url,secret,new WireEvents(){
                    public void opened(){synchronized(historyGuard){if(terminal.get())return;didConnect=true;connectedAtMs=System.currentTimeMillis();openMs=sinceStart();}enqueueEvent(()->NativeVoiceSession.this.opened());}
                    public void message(String text){if(text.length()>MAX_EVENT){fail("Voice returned an oversized event.");return;}enqueueEvent(()->event(text));}
                    public void failed(Integer status){
                        if(ending&&status==null){execute(()->{replyInFlight=false;announceEndingIfReady();});}
                        else if(live()&&status==null)disconnect("The voice connection was interrupted. Start a new voice chat to reconnect.");
                        else fail(websocketFailure(status));
                    }
                    public void closed(){
                        if(ending){execute(()->{replyInFlight=false;announceEndingIfReady();});}
                        else if(live())disconnect("The voice connection closed unexpectedly. Start a new voice chat to reconnect.");
                        else fail("Grok voice closed before the session became ready. Start a new voice chat to try again.");
                    }
                });
                if(terminal.get())socket.cancel();

            } catch(Exception failure){fail(setupFailure(failure));}
        });
    }
    static String setupFailure(Exception failure){
        // GrokCore.Failure is generated locally with fixed safe messages; arbitrary exceptions may contain credentials.
        if(failure instanceof GrokCore.Failure&&failure.getMessage()!=null&&!failure.getMessage().isBlank())return failure.getMessage();
        return "Could not open this Grok Bot voice preview. Sign in again or try a new call.";
    }
    static String websocketFailure(Integer status){
        return status!=null&&status>=100&&status<=599
            ?"Grok voice connection failed (HTTP "+status+"). Start a new call to try again."
            :"Grok voice connection failed. Start a new call to try again.";
    }
    static HttpUrl voiceUrl(String raw,String model)throws Exception {
        URI uri=new URI(raw);
        if(!"wss".equalsIgnoreCase(uri.getScheme())||!"api.x.ai".equalsIgnoreCase(uri.getHost())
                ||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))
            throw new IllegalArgumentException("Invalid voice destination");
        // OkHttp represents a secure WebSocket request with its HTTPS URL internally.
        return HttpUrl.get("https"+raw.substring(raw.indexOf(':'))).newBuilder().setQueryParameter("model",model).build();
    }
    private void opened()throws Exception {
        if(terminal.get())return;
        JSONObject format=new JSONObject().put("type","audio/pcm").put("rate",24000);
        JSONObject audio=new JSONObject().put("input",new JSONObject().put("format",format)
                .put("transcription",new JSONObject().put("model","grok-transcribe")))
                .put("output",new JSONObject().put("format",format).put("speed",1.0));
        instructionChars=plan.getString("instructions").length();JSONArray offered=plan.getJSONArray("tools");toolsOffered=offered.length();
        StringBuilder names=new StringBuilder();for(int i=0;i<offered.length();i++){String name=offered.optJSONObject(i)==null?"":offered.optJSONObject(i).optString("name");if(name.matches("[a-z_]{1,40}")){if(names.length()>0)names.append(' ');names.append(name);}}toolNames=names.toString();
        JSONObject session=new JSONObject().put("instructions",plan.getString("instructions"))
                .put("tools",plan.getJSONArray("tools")).put("turn_detection",new JSONObject().put("type","server_vad"))
                .put("audio",audio).put("resumption",new JSONObject().put("enabled",true));
        send(new JSONObject().put("type","session.update").put("session",session));
        // Like the official clients, audio may follow the settings on the same ordered socket at
        // once; waiting for session.updated cost about 0.8 s. History, when given, still waits.
        if(historyAvailable&&historicalTurns.length()==0&&!terminal.get()){settingsSent=true;historySeeded=true;admitReady();}
    }
    public void sendPcm(byte[] pcm) {
        if(pcm==null||pcm.length==0||pcm.length>16384||pcm.length%2!=0||terminal.get()||muted||ending)return;
        if(!pcmSlots.tryAcquire()){fail("Microphone could not keep up with this voice connection.");return;}
        int peak=0;for(int i=0;i+1<pcm.length;i+=2){int sample=Math.abs((short)((pcm[i]&0xff)|(pcm[i+1]<<8)));if(sample>peak)peak=sample;}
        micFrames++;if(peak>micPeak)micPeak=peak;if(peak>=2000)loudFrames++;if(peak==0)zeroFrames++;
        byte[] copy=Arrays.copyOf(pcm,pcm.length);
        if(!execute(()->{
            try {if(ready&&!muted&&!ending){send(new JSONObject().put("type","input_audio_buffer.append").put("audio",codec.encode(copy)));pcmSent+=copy.length;}}
            finally{Arrays.fill(copy,(byte)0);pcmSlots.release();}
        })){Arrays.fill(copy,(byte)0);pcmSlots.release();}
    }
    public void cancelReply(){execute(()->cancelReplyNow());}
    private void cancelReplyNow()throws Exception {
        interruptReply();
        emit(()->listener.onPlaybackInterrupted());
        if(replyInFlight&&!cancelRequested){cancelRequested=true;send(new JSONObject().put("type","response.cancel"));}
        else if(!replyInFlight)emit(()->listener.onState("speechidle"));
    }
    public void mute(boolean value){muted=value;}
    static final String LOST="Your watch lost its connection.";
    /** The network carrying this call disappeared; its socket cannot recover. */
    public void networkLost(){if(live())disconnect(LOST);else fail(LOST);}
    public void end(){finish("Call ended.",false);}
    /** Service acknowledges that the final native speech has actually left AudioTrack. */
    public void completeAfterPlayback(){execute(()->{if(ending&&!replyInFlight)finish("Grok ended the call.",false);});}
    private void requestGracefulEnd(){
        if(ending)return;ending=true;muted=true;emit(()->listener.onState("finishing"));
        // Synthesis may deliver tens of seconds ahead of real-time playback. Do not cut
        // accepted PCM at an arbitrary wall-clock delay; the service acknowledges drain.
        // The absolute five-minute call cap and explicit user hang-up still apply.
        announceEndingIfReady();
    }
    private void announceEndingIfReady(){
        if(ending&&!replyInFlight&&!endingAnnounced){endingAnnounced=true;emit(()->listener.onState("ending"));}
    }

    private void event(String raw)throws Exception {
        int eventBytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(eventBytes>MAX_EVENT){fail("Voice returned an oversized event.");return;}
        received+=eventBytes;
        JSONObject e=new JSONObject(raw);validateIdentifiers(e);String type=e.optString("type","");
        String counted=type.matches("[a-z_.]{1,64}")?type:"other";synchronized(eventTypes){eventTypes.merge(counted,1,Integer::sum);}if(ready)eventsAfterReady++;
        if(!ready&&!Set.of("session.created","session.updated","conversation.created","conversation.item.created","conversation.item.added","error").contains(type))return;
        switch(type) {
            case "conversation.created":
                JSONObject conversation=e.optJSONObject("conversation");if(conversation!=null){String id=conversation.optString("id","");if(!id.isEmpty()&&id.length()<=256)synchronized(historyGuard){realtimeConversationId=id;}}break;
            case "session.created":break; // Created precedes acceptance of our actual settings.
            case "session.updated":if(updatedMs<0)updatedMs=sinceStart();
                if(!settingsAccepted){settingsAccepted=true;
                    // Audio already flows; the account and Bot must still be the ones this call started with.
                    if(ready)try{startupGuard.check();}catch(Exception changed){fail(setupFailure(changed));break;}
                    seedHistoryIfReady();}
                break;
            case "conversation.item.created":case "conversation.item.added":
                JSONObject item=e.optJSONObject("item");if(item!=null&&pendingHistory.remove(item.optString("id","")))admitReady();break;
            case "input_audio_buffer.speech_started":
                userItem="";callerSpoke=true;callerSpeaking=true;alreadySpoke=false;speechStarts++;
                callerTurn++;toolReplyOwed=false;pendingLookups=0;interruptReply();
                // server_vad has already cancelled generation. Only stop local playback.
                emit(()->listener.onSpeechStarted());break;
            case "input_audio_buffer.speech_stopped":callerSpeaking=false;speechStoppedNanos=System.nanoTime();emit(()->listener.onState("Thinking"));
                // Speech detection can end on noise that never becomes a reply. Without
                // a response or lookup, return to Listening instead of thinking forever.
                long turn=callerTurn,seenBefore=responsesSeen;
                try{worker.schedule(()->{if(terminal.get()||ending||callerSpeaking||replyInFlight||pendingLookups>0||callerTurn!=turn||responsesSeen!=seenBefore)return;
                    emit(()->listener.onState("speechidle"));},thinkingFallbackMs,TimeUnit.MILLISECONDS);}catch(java.util.concurrent.RejectedExecutionException ignored){}
                break;
            case "response.created":
                activeResponseId=responseId(e);interruptedReply=callerSpeaking;cancelRequested=false;responsesSeen++;
                synchronized(historyGuard){assistantItem="";currentReplyItems.clear();suppressReplyTranscript=callerSpeaking;}
                replyInFlight=true;announcedSpeaking=false;break;
            case "response.done":case "response.cancelled":
                if(!belongsToCurrentReply(e))break;
                replyInFlight=false;announcedSpeaking=false;
                // A lookup still running will produce the spoken answer; keep showing Thinking.
                if(ending)announceEndingIfReady();else{emit(()->listener.onState(pendingLookups>0?"Thinking":"speechidle"));payToolReply();}break;
            case "response.audio.delta":case "response.output_audio.delta":
                if(!callerSpeaking&&!interruptedReply&&belongsToCurrentReply(e)){String encoded=e.optString("delta","");
                    if(!encoded.isEmpty()){byte[] pcm=codec.decode(encoded);
                        if(pcm.length%2!=0)throw new IllegalArgumentException();alreadySpoke=true;audioOut+=pcm.length;
                        if(speechStoppedNanos!=0){lastTtfaMs=(System.nanoTime()-speechStoppedNanos)/1_000_000;maxTtfaMs=Math.max(maxTtfaMs,lastTtfaMs);speechStoppedNanos=0;}
                        if(!announcedSpeaking){announcedSpeaking=true;emit(()->listener.onState("Speaking"));}emit(()->listener.onAudio(pcm));}}
                break;
            case "conversation.item.input_audio_transcription.updated":case "conversation.item.input_audio_transcription.completed":
                if(type.endsWith(".completed"))heard++;captureTurn(e,"user",false);caption("You",e.optString("transcript",""));break;
            case "response.audio_transcript.delta":case "response.output_audio_transcript.delta":
                if(!interruptedReply&&belongsToCurrentReply(e)&&!interruptedItems.contains(e.optString("item_id","")))captureTurn(e,"assistant",true);break;
            case "response.audio_transcript.done":case "response.output_audio_transcript.done":
                if(!interruptedReply&&belongsToCurrentReply(e)&&!interruptedItems.contains(e.optString("item_id",""))){captureTurn(e,"assistant",false);caption(botName,e.optString("transcript",""));}break;
            case "response.function_call_arguments.done":if(!interruptedReply&&belongsToCurrentReply(e))tool(e);break;
            case "error":protocolError(e);break;
            default:break;
        }
    }
    private void seedHistoryIfReady()throws Exception {
        if(!settingsAccepted||!historyAvailable||historySeeded||terminal.get())return;
        startupGuard.check();
        JSONArray seeds=historyFrames(historicalTurns);historySeeded=true;seeded=seeds.length();seedShape=seedShape(seeds);
        for(int i=0;i<seeds.length();i++){
            JSONObject seed=seeds.getJSONObject(i);String id="history-"+java.util.UUID.randomUUID();
            seed.getJSONObject("item").put("id",id);pendingHistory.add(id);
        }
        for(int i=0;i<seeds.length();i++){send(seeds.getJSONObject(i));if(terminal.get())return;}
        historicalTurns=new JSONArray();admitReady();
    }
    private void admitReady(){
        if(!ready&&(settingsAccepted||settingsSent)&&historySeeded&&pendingHistory.isEmpty()&&!terminal.get()){
            try{startupGuard.check();}catch(Exception changed){fail(setupFailure(changed));return;}
            if(terminal.get())return;
            ready=true;readyMs=sinceStart();cancelStartupDeadline();
            // Settings that are never confirmed would leave a call that can't hear the caller.
            if(!settingsAccepted)try{worker.schedule(()->execute(()->{if(!settingsAccepted&&!terminal.get())fail("Grok voice did not accept the call settings. Start a new call.");}),SETTINGS_DEADLINE_MS,TimeUnit.MILLISECONDS);}catch(java.util.concurrent.RejectedExecutionException ignored){}emit(()->listener.onState("Listening · read-only voice preview"));emit(()->listener.onReady());
            // Shared history is context, not a new prompt. The desktop harness may
            // offer a proactive greeting, but a watch call waits for fresh speech.
        }
    }
    private void caption(String who,String text){if(!text.isBlank())emit(()->listener.onCaption(who,text.substring(0,Math.min(text.length(),8000))));}
    private void requestReply(String instructions)throws Exception {
        JSONObject response=new JSONObject();
        if(instructions!=null&&!instructions.isBlank())response.put("instructions",instructions);
        send(new JSONObject().put("type","response.create").put("response",response));
        replyInFlight=true;
    }
    private void tool(JSONObject e)throws Exception {
        String id=e.optString("call_id",""),name=e.optString("name","");toolCalls++;
        if(id.isEmpty()){fail("Grok returned a tool call without an identifier.");return;}
        if(calls.contains(id))return; // One execution per function-call ID, including refusals.
        if(calls.size()>=128){finish("Call preview reached its tool limit.",false);return;}
        calls.add(id);
        if(!READ_TOOLS.contains(name)||!offered.contains(name)) {
            toolResultWithReply(id,new JSONObject().put("error","This read-only preview does not permit that action."));return;
        }
        if("end_the_call".equals(name)){toolOutput(id,new JSONObject().put("hangUp",true));requestGracefulEnd();return;}
        JSONObject input=new JSONObject(e.optString("arguments","{}"));
        JSONObject line=new JSONObject().put("theyJustTalked",callerSpoke).put("newsOwed",newsOwed)
                .put("receiptOwed",receiptOwed).put("alreadySpokeThisTurn",alreadySpoke);
        JSONObject request=toolRequest(botId,callId,name,input,line);
        long requestedTurn=callerTurn;pendingLookups++;
        try {lookups.execute(()->{
            if(terminal.get()||ending)return;
            try {
                if(terminal.get()||ending)return;
                JSONObject result=backend.request("VoiceCallHarnessTool",request);
                if(terminal.get()||ending)return;
                execute(()->{
                    if(requestedTurn==callerTurn)pendingLookups=Math.max(0,pendingLookups-1);
                    if(!"served".equals(result.optString("kind",""))) {
                        toolResultForTurn(id,new JSONObject().put("error","The requested read-only tool was not available."),requestedTurn);return;
                    }
                    Object output=result.opt("output");
                    toolOutput(id,output==null?new JSONObject():output);
                    if(requestedTurn==callerTurn&&(!(output instanceof JSONObject)||!"silent".equals(((JSONObject)output).optString("plan","")))){toolReplyOwed=true;payToolReply();}
                    else settleIfQuiet();
                });
            } catch(Exception ignored){execute(()->{if(requestedTurn==callerTurn)pendingLookups=Math.max(0,pendingLookups-1);toolResultForTurn(id,new JSONObject().put("error","The read-only lookup failed."),requestedTurn);});}
        });} catch(java.util.concurrent.RejectedExecutionException ignored){fail("Call preview reached its pending lookup limit.");}
    }

    private void payToolReply()throws Exception {
        if(toolReplyOwed&&!callerSpeaking&&!replyInFlight&&!ending){toolReplyOwed=false;requestReply(null);}
    }
    static JSONObject toolRequest(String botId,String callId,String name,JSONObject input,JSONObject line)throws Exception {
        return new JSONObject().put("agentId",botId).put("callId",callId).put("name",name).put("input",input).put("line",line);
    }
    private void toolResultForTurn(String id,Object output,long turn)throws Exception {
        toolOutput(id,output);if(turn==callerTurn){toolReplyOwed=true;payToolReply();}else settleIfQuiet();
    }
    /** A silent or superseded lookup leaves nothing to say: show Listening again. */
    private void settleIfQuiet(){if(pendingLookups==0&&!replyInFlight&&!callerSpeaking&&!ending)emit(()->listener.onState("speechidle"));}
    private void toolResultWithReply(String id,Object output)throws Exception {
        toolOutput(id,output);toolReplyOwed=true;payToolReply();
    }
    static String encodeOutput(Object output)throws Exception {
        // Android JSONObject does not expose valueToString; an array uses the same JSON encoder.
        String wrapped=new JSONArray().put(output==null?JSONObject.NULL:output).toString();
        if(wrapped==null||wrapped.length()<2)throw new IllegalArgumentException();
        return wrapped.substring(1,wrapped.length()-1);
    }
    private void toolOutput(String id,Object output)throws Exception {
        String encoded=encodeOutput(output);
        if(encoded.length()>128*1024){fail("Grok returned an oversized tool result.");return;}
        send(new JSONObject().put("type","conversation.item.create").put("item",new JSONObject()
                .put("type","function_call_output").put("call_id",id).put("output",encoded)));
    }
    private void send(JSONObject frame)throws Exception {
        Wire ws=socket;
        if(terminal.get())return;
        String text=frame.toString();
        int bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(ws==null||bytes>MAX_QUEUE){fail("Grok voice could not keep up with the connection.");return;}
        // A full outgoing queue or a refused send means the socket is no longer draining:
        // on a watch that is a lost network far more often than a slow encoder.
        if(ws.queueSize()+bytes>MAX_QUEUE||!ws.send(text)){if(live())disconnect(LOST);else fail("Grok voice could not keep up with the connection.");}
    }
    private interface Work {void run()throws Exception;}
    private boolean execute(Work work) {
        if(terminal.get())return false;
        try {worker.execute(()->{if(terminal.get())return;try{work.run();}catch(Exception ignored){fail("Grok voice could not process this call safely.");}});return true;}
        catch(java.util.concurrent.RejectedExecutionException ignored){return false;}
    }
    private void enqueueEvent(Work work) {
        if(terminal.get())return;
        if(!eventSlots.tryAcquire()){fail("Grok voice returned events faster than this watch could process.");return;}
        if(!execute(()->{try{work.run();}finally{eventSlots.release();}}))eventSlots.release();
    }
    private void emit(Runnable callback){synchronized(callbackGuard){if(!terminal.get())callback.run();}}
    private void cancelStartupDeadline(){
        java.util.concurrent.ScheduledFuture<?> deadline=startupDeadline;if(deadline!=null)deadline.cancel(false);
        startupWatchdog.shutdownNow();
    }
    private void fail(String safe){finish(safe,true);}
    private void disconnect(String safe){finish(safe,true,true);}
    private void finish(String reason,boolean failure){finish(reason,failure,false);}
    private void finish(String reason,boolean failure,boolean disconnected) {
        synchronized(callbackGuard){if(!terminal.compareAndSet(false,true))return;}
        cancelStartupDeadline();
        Wire ws=socket;if(ws!=null)ws.cancel();
        // BotAccount's active HTTP request has its own 30s bound; it is not cancellable here.
        // Guards after every backend return prevent a timed-out startup from minting/opening later.
        worker.shutdownNow();lookups.shutdownNow();http.dispatcher().cancelAll();http.connectionPool().evictAll();
        if(disconnected)listener.onDisconnected(reason);else if(failure)listener.onFailure(reason);else listener.onComplete(reason);
    }
}
