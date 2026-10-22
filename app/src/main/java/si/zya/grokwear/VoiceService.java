package si.zya.grokwear;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Explicitly admitted, non-sticky native call. Preview screens never instantiate this service. */
public final class VoiceService extends Service {
    private static final String START="si.zya.grokwear.TALK",END="si.zya.grokwear.END",MUTE="si.zya.grokwear.MUTE",INTERRUPT="si.zya.grokwear.INTERRUPT",CHANNEL="grok_voice";
    private static final int NOTIFICATION=41;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Runnable> LISTENERS=new CopyOnWriteArraySet<>();
    public static final class Snapshot {
        public final String state,caption,speaker,error,botName,syncStatus;
        /** The part of the caption being spoken now; equals caption when no playback position applies. */
        public final String captionLine;
        public final boolean active,muted;
        public final float inputLevel,outputLevel;
        /** Current call duration, measured on the monotonic clock. */
        public final long elapsedMs;
        /** Absolute monotonic deadline, including connection/setup time; zero when no call is active. */
        public final long deadlineElapsedMs;
        Snapshot(String state,String caption,String speaker,String error,String botName,boolean active,boolean muted,float inputLevel,float outputLevel){
            this(state,caption,speaker,error,botName,active,muted,inputLevel,outputLevel,0);}
        Snapshot(String state,String caption,String speaker,String error,String botName,boolean active,boolean muted,float inputLevel,float outputLevel,long elapsedMs){
            this(state,caption,speaker,error,botName,active,muted,inputLevel,outputLevel,elapsedMs,"");}
        Snapshot(String state,String caption,String speaker,String error,String botName,boolean active,boolean muted,float inputLevel,float outputLevel,long elapsedMs,String syncStatus){
            this(state,caption,speaker,error,botName,active,muted,inputLevel,outputLevel,elapsedMs,syncStatus,0);}
        Snapshot(String state,String caption,String speaker,String error,String botName,boolean active,boolean muted,float inputLevel,float outputLevel,long elapsedMs,String syncStatus,long deadlineElapsedMs){
            this(state,caption,speaker,error,botName,active,muted,inputLevel,outputLevel,elapsedMs,syncStatus,deadlineElapsedMs,caption);}
        Snapshot(String state,String caption,String speaker,String error,String botName,boolean active,boolean muted,float inputLevel,float outputLevel,long elapsedMs,String syncStatus,long deadlineElapsedMs,String captionLine){
            this.captionLine=captionLine==null?caption:captionLine;this.deadlineElapsedMs=deadlineElapsedMs;this.syncStatus=syncStatus;this.state=state;this.caption=caption;this.speaker=speaker;this.error=error;this.botName=botName;this.active=active;this.muted=muted;this.inputLevel=inputLevel;this.outputLevel=outputLevel;this.elapsedMs=elapsedMs;}
    }
    private static volatile Snapshot current=new Snapshot("Ready","","","","",false,false,0,0);
    private static String ticket;private static long ticketTime;
    private static volatile boolean savingRecord;
    private static volatile long historyEpoch;private long callHistoryEpoch;
    public static Snapshot snapshot(){return current;}
    public static boolean historyStaging(){return savingRecord;}
    public static void addListener(Runnable listener){LISTENERS.add(listener);}
    public static void removeListener(Runnable listener){LISTENERS.remove(listener);}
    private static void publish(Snapshot next){current=next;MAIN.post(()->{for(Runnable listener:LISTENERS)listener.run();});}
    public static boolean start(Activity activity){
        PowerManager power=(PowerManager)activity.getSystemService(POWER_SERVICE);
        KeyguardManager keyguard=(KeyguardManager)activity.getSystemService(KEYGUARD_SERVICE);
        if(activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus()||!activity.getWindow().getDecorView().isShown()
                ||!power.isInteractive()||keyguard.isKeyguardLocked()||activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED||current.active||savingRecord)return false;
        String admission=UUID.randomUUID().toString();synchronized(VoiceService.class){ticket=admission;ticketTime=SystemClock.elapsedRealtime();historyEpoch++;}
        publish(new Snapshot("Connecting","","","","",true,false,0,0));
        try{activity.startForegroundService(new Intent(activity,VoiceService.class).setAction(START).putExtra("admission",admission));return true;}
        catch(RuntimeException refused){synchronized(VoiceService.class){ticket=null;}publish(new Snapshot("Ready","","","Watch could not start a foreground voice call.","",false,false,0,0));return false;}
    }
    public static void stop(Context context){action(context,END);}
    public static void toggleMute(Context context){action(context,MUTE);}
    public static void interrupt(Context context){action(context,INTERRUPT);}
    private static void action(Context context,String action){if(!current.active)return;try{context.startService(new Intent(context,VoiceService.class).setAction(action));}catch(RuntimeException ignored){if(END.equals(action))context.stopService(new Intent(context,VoiceService.class));}}
    private static synchronized boolean consume(Intent intent){String supplied=intent.getStringExtra("admission");boolean ok=ticket!=null&&ticket.equals(supplied)&&SystemClock.elapsedRealtime()-ticketTime<5000;ticket=null;return ok;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService setup=Executors.newSingleThreadExecutor();
    private final ExecutorService historyWriter=Executors.newSingleThreadExecutor();
    private BotAccount callAccount;private HistoryCoordinator.Context callContext;private JSONObject callBot;
    private String syncStatus="";
    private volatile VoiceStartup startup;
    private volatile NativeVoiceSession session;private volatile NativeAudio audio;private PowerManager.WakeLock wake;
    private volatile long generation;private volatile boolean running,muted,responseIdle=true,drained=true,ending,endReady;
    private String state="Connecting",caption="",speaker="",error="",botName="";
    private float inputLevel,outputLevel;private long lastMeter,lastAudioPost;
    private volatile String replyText="";private volatile long replyStartFrame;private String lastSpoken="";
    /** Rough frames of speech per character, used until the whole answer's audio has arrived. */
    private static final double FRAMES_PER_CHAR=1500;
    private ConnectivityManager.NetworkCallback networkWatch;private volatile android.net.Network callNetwork;
    private long liveStartedAt,finishedElapsed,callDeadlineElapsed;
    // Startup phase times in ms from Talk, for the call_stop diagnostics line.
    private volatile long talkElapsed,planMs=-1,mintMs=-1,dialMs=-1,listeningMs=-1;
    private long sinceTalk(){return SystemClock.elapsedRealtime()-talkElapsed;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(END.equals(action)){finish("Call ended.","");return START_NOT_STICKY;}
        if(MUTE.equals(action)){if(running&&!ending){muted=!muted;if(session!=null)session.mute(muted);if(audio!=null&&liveStartedAt>0)audio.mute(muted);inputLevel=0;emit();notifyCall();}return START_NOT_STICKY;}
        if(INTERRUPT.equals(action)){if(running&&!ending&&session!=null)session.cancelReply();return START_NOT_STICKY;}
        if(!START.equals(action)||running||!consume(intent)){if(!running){publish(new Snapshot("Ready","","","Tap Voice chat again to start voice.","",false,false,0,0));stopSelf();}return START_NOT_STICKY;}
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(!power.isInteractive()||keyguard.isKeyguardLocked()||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){finish("Ready","Tap Voice chat again while the watch is unlocked.");return START_NOT_STICKY;}
        running=true;callHistoryEpoch=historyEpoch;long mine=++generation;talkElapsed=SystemClock.elapsedRealtime();planMs=-1;mintMs=-1;dialMs=-1;listeningMs=-1;state="Connecting";caption="";speaker="";error="";muted=false;responseIdle=true;drained=true;ending=false;endReady=false;
        liveStartedAt=0;finishedElapsed=0;callDeadlineElapsed=SystemClock.elapsedRealtime()+300000;syncStatus="Connecting voice";callAccount=null;callContext=null;
        try{Notification notification=notification();startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"grokwear:voice");wake.setReferenceCounted(false);wake.acquire(310000);
        }catch(RuntimeException refused){finish("Ready","Watch could not keep this voice call in the foreground.");return START_NOT_STICKY;}
        emit();main.postDelayed(()->{if(valid(mine))finish("Call ended","Voice call reached its five-minute limit.");},Math.max(0,callDeadlineElapsed-SystemClock.elapsedRealtime()));
        watchNetwork(mine);replyText="";replyStartFrame=0;
        NativeVoiceSession.prewarm();
        VoiceStartup pipeline=new VoiceStartup();startup=pipeline;
        setup.execute(()->{try{BotAccount account=new BotAccount(this);JSONObject bot=account.selectedBot();
            JSONObject binding=account.historyBinding();
            if(!binding.getString("agentId").equals(bot.optString("id")))throw new IllegalStateException();
            NativeVoiceSession.Backend lease=account.startupBackend(binding);pipeline.check();
            // Like the official apps, a call starts without preloading chat history: the Bot's
            // instructions and its lookup tools carry context, and the microphone isn't held
            // behind history pages or per-message acknowledgements. The binding still saves the call.
            VoiceStartup.Result prepared=pipeline.prepare(()->new HistoryCoordinator.Context(binding,new org.json.JSONArray()),
                ()->{JSONObject plan=lease.request("VoiceCallHarnessSession",new JSONObject().put("agentId",bot.getString("id")).put("agentName",bot.getString("name")));planMs=sinceTalk();return plan;},
                ()->{JSONObject credential=lease.request("MintSandVoiceCallSecret",new JSONObject());mintMs=sinceTalk();return credential;},
                (plan,credential)->post(mine,()->openPreparedSession(mine,account,bot,binding,plan,credential,pipeline)));
            account.verifyHistoryBinding(binding,true);
            post(mine,()->{
                try{
                    if(session==null)throw new IllegalStateException("Voice transport missing");
                    callContext=prepared.history;emit();
                }catch(Exception invalid){finish("Call stopped","Voice could not start safely. Try again.");}
            });
        }catch(Exception failed){String reason=setupReason(failed);post(mine,()->finish("Ready",reason));}});
        return START_NOT_STICKY;
    }
    private void openPreparedSession(long mine,BotAccount account,JSONObject bot,JSONObject binding,JSONObject plan,JSONObject credential,VoiceStartup pipeline){
        if(!valid(mine))return;dialMs=sinceTalk();
        botName=bot.optString("name","");callAccount=account;callBot=bot;
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);if(manager!=null&&manager.getActiveNetwork()!=null)callNetwork=manager.getActiveNetwork();
        try{session=new NativeVoiceSession(account,bot,new NativeVoiceSession.Listener(){
                    public void onDiagnostic(String line){android.util.Log.i("GrokWearVoice",line);}
                    public void onState(String value){post(mine,()->{
                        if("ending".equals(value)||"finishing".equals(value)){ending=true;endReady="ending".equals(value);muted=true;if(audio!=null)audio.mute(true);state="Ending";
                            if(endReady&&drained&&session!=null)session.completeAfterPlayback();emit();notifyCall();return;}
                        if(ending)return;
                        if("Reconnecting".equals(value)){responseIdle=true;state="Reconnecting";emit();notifyCall();return;}
                        if("speechidle".equals(value)){responseIdle=true;if(drained)state="Listening";}
                        else if(value.startsWith("Speaking")){responseIdle=false;state="Speaking";}else if(value.startsWith("Thinking")){responseIdle=false;state="Thinking";}
                        else if(value.startsWith("Listening")){if(audio!=null&&drained)state="Listening";}emit();});}
                    public void onReady(){post(mine,()->startAudio(mine));}
                    public void onAudio(byte[] pcm){postAudio(mine,pcm);}
                    public void onCaption(String who,String text){post(mine,()->{speaker=who;caption=text;
                        // A new question retires the previous answer; the Bot's text is mapped to playback below.
                        replyText="You".equals(who)?"":text;lastSpoken="";emit();});}
                    public boolean hasUnheardReplyAudio(){return valid(mine)&&!drained;}
                    public void onPlaybackInterrupted(){onSpeechStarted();}
                    public void onSpeechStarted(){
                        // Flush on the session callback, before it can enqueue the next
                        // response's audio. A delayed main-thread flush could erase that reply.
                        synchronized(VoiceService.this){if(!valid(mine)||ending)return;if(audio!=null)audio.interrupt();drained=true;responseIdle=false;}
                        post(mine,()->{if(!ending){state="Listening";emit();}});
                    }
                    public void onComplete(String reason){post(mine,()->finish(reason,""));}
                    public void onFailure(String safe){post(mine,()->finish("Call stopped",safe));}
                    public void onDisconnected(String safe){post(mine,()->finish("Call dropped",safe));}
                },pipeline.remainingMillis());
            session.redialBackend=account::redialBackend;
            session.preparedStartup(plan,credential,()->account.verifyHistoryBinding(binding,true));
            emit();notifyCall();session.start();prepareAudio(mine);
        }catch(Exception invalid){finish("Call stopped","Voice could not start safely. Try again.");}
    }
    /** Fixed local messages only: a Failure carries a safe sentence; anything else may hold private detail. */
    private String setupReason(Exception failed){
        if(!online(this))return OFFLINE;
        if(failed instanceof GrokCore.Failure&&failed.getMessage()!=null&&!failed.getMessage().isBlank())return failed.getMessage();
        if(failed instanceof java.util.concurrent.TimeoutException)return "Voice took too long to connect on this connection. Try again with a stronger signal.";
        return "Voice could not start. Check your connection and try again.";
    }
    static final String OFFLINE="You're offline. Check Wi-Fi or your watch's connection, then try again.";
    static boolean online(Context context){
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager==null)return true;
        android.net.Network network=manager.getActiveNetwork();if(network==null)return false;
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
        return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    /** A lost network kills the call's socket immediately; say so instead of waiting for pings. */
    private void watchNetwork(long mine){
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);if(manager==null)return;
        callNetwork=manager.getActiveNetwork();
        networkWatch=new ConnectivityManager.NetworkCallback(){
            // A dropped call redials as soon as any network is back, instead of waiting out a stuck attempt.
            @Override public void onAvailable(android.net.Network available){post(mine,()->{NativeVoiceSession live=session;if(live!=null&&!available.equals(callNetwork)){callNetwork=available;live.networkAvailable();}});}
            @Override public void onLost(android.net.Network lost){post(mine,()->{
            NativeVoiceSession live=session;
            // Before the socket exists, another network can still finish setup; only no network at all ends it.
            if(live!=null&&lost.equals(callNetwork))live.networkLost();else if(manager.getActiveNetwork()==null){if(live!=null)live.networkLost();else finish("Ready",OFFLINE);}
        });}};
        try{manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkWatch);}
        catch(RuntimeException unavailable){networkWatch=null;}
    }
    private void unwatchNetwork(){
        ConnectivityManager.NetworkCallback watch=networkWatch;networkWatch=null;callNetwork=null;if(watch==null)return;
        try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(watch);}catch(RuntimeException ignored){}
    }
    private boolean valid(long mine){return running&&generation==mine;}
    private void post(long mine,Runnable work){main.post(()->{if(valid(mine))work.run();});}
    // Copy immediately: the transport may clear its source after callback returns.
    private void postAudio(long mine,byte[] pcm){boolean announce;
        synchronized(this){if(!valid(mine))return;if(audio==null){post(mine,()->finish("Call stopped","Voice audio arrived before watch playback was ready."));return;}
            if(drained)replyStartFrame=audio.queuedFrames();drained=false;responseIdle=false;audio.output(pcm);
            // Synthesis arrives in bursts of many chunks; the UI needs the state, not every chunk.
            long now=SystemClock.uptimeMillis();announce=now-lastAudioPost>=150;if(announce)lastAudioPost=now;}
        if(announce)post(mine,()->{if(!ending)state="Speaking";emit();});}
    /** Audio focus, call mode and both audio tracks are set up while the socket dials; the microphone stays off until ready. */
    private void prepareAudio(long mine){if(!valid(mine)||audio!=null)return;
        audio=new NativeAudio(this,new NativeAudio.Listener(){
            public void input(byte[] pcm,float level){NativeVoiceSession live=session;if(valid(mine)&&!muted&&live!=null){live.sendPcm(pcm);meter(mine,level,true);}}
            public void output(float level){meter(mine,level,false);}
            public void drained(){post(mine,()->{synchronized(VoiceService.this){if(audio==null||!audio.isDrained())return;drained=true;}outputLevel=0;if(ending){if(endReady&&session!=null)session.completeAfterPlayback();}else if(responseIdle)state="Listening";emit();});}
            public void failure(String safe){post(mine,()->finish("Call stopped",safe));}
        });audio.start(true);
    }
    private void startAudio(long mine){if(!valid(mine)||liveStartedAt>0)return;
        syncStatus="Connected";
        liveStartedAt=SystemClock.elapsedRealtime();
        if(audio==null)prepareAudio(mine);NativeAudio prepared=audio;if(prepared==null||!valid(mine))return;
        prepared.mute(muted||ending);listeningMs=sinceTalk();state=ending?"Ending":"Listening";emit();notifyCall();
    }
    private void meter(long mine,float level,boolean input){long now=SystemClock.elapsedRealtime();synchronized(this){if(now-lastMeter<70&&level!=0)return;lastMeter=now;}post(mine,()->{if(input)inputLevel=muted?0:level;else outputLevel=level;emit();});}
    private void emit(){long elapsed=liveStartedAt==0?finishedElapsed:Math.max(0,SystemClock.elapsedRealtime()-liveStartedAt);
        publish(new Snapshot(state,caption,speaker,error,botName,running,muted,inputLevel,outputLevel,elapsed,syncStatus,running?callDeadlineElapsed:0,spokenLine()));}
    /** Sentence of the Bot's answer at the current playback position, so captions follow the voice. */
    private String spokenLine(){
        String text=replyText;NativeAudio playing=audio;
        if(text.isEmpty()||"You".equals(speaker))return caption;
        // Between and after answers keep the last sentence heard, never jump back to the start.
        if(playing==null||!"Speaking".equals(state))return lastSpoken.isEmpty()?MessageText.sentenceAt(text,0):lastSpoken;
        long queued=playing.queuedFrames()-replyStartFrame,played=playing.playedFrames()-replyStartFrame;
        // Audio streams in faster than it plays; until the reply is complete, an estimate from
        // the text length keeps the caption from running ahead of the voice.
        double total=responseIdle?queued:Math.max(queued,text.length()*FRAMES_PER_CHAR);
        lastSpoken=MessageText.sentenceAt(text,total<=0?0:Math.max(0,Math.min(1,played/total)));return lastSpoken;
    }
    private Notification notification(){NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Voice chat",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent end=PendingIntent.getService(this,1,new Intent(this,VoiceService.class).setAction(END),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder=new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_voice_status).setContentTitle("Voice chat · Tap to return")
            .setContentText(muted?"Microphone muted":botName.isEmpty()?"Connecting to your Bot":botName).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).addAction(R.drawable.ic_voice_status,"Hang up",end);
        new OngoingActivity.Builder(this,NOTIFICATION,builder).setStaticIcon(R.drawable.ic_voice_status).setTouchIntent(open)
            .setStatus(new Status.Builder().addTemplate(muted?"Voice chat · muted":"Voice chat · Tap to return").build()).build().apply(this);
        return builder.build();
    }
    private void notifyCall(){if(running)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());}
    private void finish(String finalState,String safe){if(!running&&session==null&&audio==null){if(savingRecord&&callHistoryEpoch==historyEpoch)return;state=finalState;error=safe;emit();stopSelf();return;}
        if(liveStartedAt!=0){finishedElapsed=Math.max(0,SystemClock.elapsedRealtime()-liveStartedAt);liveStartedAt=0;}
        // Fixed categories only: no captions, Bot/account identifiers, credentials or remote messages.
        android.util.Log.i("GrokWearVoice","call_stop cause="+stopCategory(finalState,safe)+" duration_ms="+finishedElapsed
                +" plan_ms="+planMs+",mint_ms="+mintMs+",dial_ms="+dialMs+",listening_ms="+listeningMs
                +(session==null?"":" "+session.protocolDiagnostics()));
        unwatchNetwork();replyText="";lastSpoken="";
        running=false;generation++;VoiceStartup preparing=startup;startup=null;if(preparing!=null)preparing.close();main.removeCallbacksAndMessages(null);NativeVoiceSession old=session;session=null;if(old!=null){if(!drained)old.discardUnheardReply();old.end();}
        BotAccount account=callAccount;HistoryCoordinator.Context context=callContext;JSONObject bot=callBot;callAccount=null;callContext=null;callBot=null;
        if(old!=null&&old.didConnect()&&account!=null&&context!=null){
            try{
                String ending="Call dropped".equals(finalState)?"disconnected":safe.isEmpty()?"hung-up":"failed";
                JSONObject record=old.recordSnapshot(ending,System.currentTimeMillis(),finishedElapsed),line=old.handoffLine();syncStatus="Saving shared history";savingRecord=true;
                long finishedGeneration=generation;
                // The upload and the handoff run after hang-up, often with the wrist down. Stay in the
                // foreground with the CPU awake until both finish, or a paused app could drop the
                // handoff, which is never retried. Bounded by the HTTP timeouts and this cap.
                PowerManager.WakeLock sending=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GrokWear:after-call");
                sending.setReferenceCounted(false);sending.acquire(120000);
                historyWriter.execute(()->{
                    try{
                        try{HistoryCoordinator.stage(account,context,record);}
                        catch(Exception ignored){MAIN.post(()->{if(generation==finishedGeneration&&!running&&callHistoryEpoch==historyEpoch){
                            savingRecord=false;syncStatus="rejected";emit();}});return;}
                        // Encrypted pending record is durable and prepare() merges it into the next call,
                        // so Talk works again now, while the upload and the handoff continue.
                        MAIN.post(()->{if(generation==finishedGeneration&&!running&&callHistoryEpoch==historyEpoch){
                            savingRecord=false;syncStatus="pending";emit();}});
                        String status;try{status=HistoryCoordinator.record(account,context,record).optString("syncStatus","uncertain");}
                        catch(Exception ignored){status="uncertain";}String result=status;
                        MAIN.post(()->{if(generation==finishedGeneration&&!running&&callHistoryEpoch==historyEpoch){syncStatus=result;emit();}});
                        // Tell the Bot the call ended, as the official clients do, so anything owed follows up in its chat.
                        // At most once: no retry after any failure, so no request can reach the Bot twice.
                        if(CallHandoff.applies(bot)){
                            String outcome=CallHandoff.send(body->account.handoffTool(context.binding(),body),bot.optString("id"),record.optString("callId"),CallHandoff.requests(record.optJSONArray("turns")),line);
                            android.util.Log.i("GrokWearVoice","call_handoff result="+outcome);
                        }else android.util.Log.i("GrokWearVoice","call_handoff skipped harness="+(bot==null||bot.optString("harness").isEmpty()?"unknown":bot.optString("harness")));
                    }finally{
                        // A new call that started meanwhile owns the service; leave it running.
                        MAIN.post(()->{if(generation==finishedGeneration&&!running&&callHistoryEpoch==historyEpoch){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
                        if(sending.isHeld())sending.release();
                    }
                });
            }catch(Exception ignored){savingRecord=false;syncStatus="uncertain";}
        }
        NativeAudio closing; synchronized(this){closing=audio;audio=null;}if(closing!=null)closing.close();
        if(wake!=null){if(wake.isHeld())wake.release();wake=null;}muted=false;inputLevel=0;outputLevel=0;state=finalState;error=safe;emit();if(savingRecord){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_voice_status).setContentTitle("Saving shared history").setContentText("Microphone is off").setOngoing(true).setOnlyAlertOnce(true).build());}else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    private static String stopCategory(String state,String safe){
        if(state.contains("five-minute")||safe.contains("five-minute"))return "call_time_limit";
        if("Call dropped".equals(state))return "transport_closed";
        if(safe.equals(OFFLINE)||safe.equals(NativeVoiceSession.LOST))return "network_lost";
        if(safe.startsWith("Shared history")||safe.startsWith("Couldn't load this chat")||safe.startsWith("Your chat took")||safe.contains("shared history"))return "history_unavailable";
        if(safe.equals("Grok refused the voice session or one of its requests."))return "server_protocol";
        if(safe.startsWith("Audio focus was lost."))return "audio_focus_lost";
        if(safe.startsWith("Voice reply exceeded"))return "playback_capacity";
        if(safe.startsWith("Watch speaker playback"))return "playback_failed";
        return safe.isEmpty()?"normal_end":"other_failure";
    }
    @Override public void onTaskRemoved(Intent rootIntent){finish("Call ended.","");super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){if(running||session!=null||audio!=null)finish("Call ended.",error);setup.shutdownNow();historyWriter.shutdown();super.onDestroy();}
}
