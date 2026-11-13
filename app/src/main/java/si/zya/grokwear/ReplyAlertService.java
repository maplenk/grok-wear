package si.zya.grokwear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * After you send a Bot a message from the watch, keeps the live event stream open in the background and
 * buzzes when the Bot replies (or Grok says it's out of usage, or the Bot or someone on its phone call asks you something), unless that chat is on screen. Grok Bot's own pushes go to its phone app
 * only, so this is the watch's way to hear back. Waits end as BotEvents.Waits says, at most ten minutes.
 */
public final class ReplyAlertService extends Service {
    private static final String WATCH="si.zya.grokwear.WATCH_REPLY";
    // 0.5.0's "replies" channel had vibration off, and a channel can't be changed once made: this one replaces it.
    private static final String WAITING="reply_wait",REPLIES="bot_replies",OLD_REPLIES="replies";
    private static final int NOTIFICATION=7,REPLY=8;
    static final String OPEN_BOT="open_bot";
    /** The Settings switch. On unless turned off; Android's notification permission is asked for separately. */
    static boolean enabled(Context context){try{return context.getSharedPreferences("grokwear_ui",MODE_PRIVATE).getBoolean("reply_alerts",true);}catch(RuntimeException unavailable){return true;}}
    static void setEnabled(Context context,boolean on){try{context.getSharedPreferences("grokwear_ui",MODE_PRIVATE).edit().putBoolean("reply_alerts",on).apply();}catch(RuntimeException ignored){}}
    /** Starts or extends the wait for this Bot. Call while the app is on screen, as foreground services require. */
    static void watch(Context context,String identity,String botId,String name,String messageId){
        if(!enabled(context))return;
        try{context.startForegroundService(new Intent(context,ReplyAlertService.class).setAction(WATCH)
            .putExtra("identity",identity).putExtra("bot",botId).putExtra("name",name).putExtra("message",messageId));}
        catch(RuntimeException refused){}
    }
    static void stopAll(Context context){try{context.stopService(new Intent(context,ReplyAlertService.class));}catch(RuntimeException ignored){}}
    /** Signing out: stop waiting and remove reply notices, which carry the old account's Bots and text. */
    static void forget(Context context){
        stopAll(context);NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
        try{for(android.service.notification.StatusBarNotification shown:manager.getActiveNotifications())if(shown.getTag()!=null&&shown.getTag().startsWith("reply:"))manager.cancel(shown.getTag(),shown.getId());}
        catch(RuntimeException ignored){}
    }
    /** The Bot's chat is open, so its reply notification has done its job. */
    static void dismiss(Context context,String botId){
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(tag(botId),REPLY);
    }
    private static String tag(String botId){return "reply:"+botId;}
    // Each Bot's last view seen live, so only a new one looks through the shown notifications.
    private static final java.util.Map<String,Long> VIEWED=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A live Bot row says its chat was read (BotEvents.viewedAt), say on the phone: its notice goes, as the official
     * app dismisses a viewed Bot's notifications. One posted after that read stays.
     */
    static void viewed(Context context,JSONObject row){
        String botId=row==null?"":row.optString("id");long at=BotEvents.viewedAt(row);
        if(botId.isEmpty()||at<=0)return;Long before=VIEWED.put(botId,at);if(before!=null&&before==at)return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
        try{for(android.service.notification.StatusBarNotification shown:manager.getActiveNotifications())
            if(tag(botId).equals(shown.getTag())&&shown.getId()==REPLY&&BotEvents.readAfter(row,shown.getPostTime()))manager.cancel(tag(botId),REPLY);}
        catch(RuntimeException ignored){}
    }
    /** Posts a Bot's reply; tapping it starts open (MainActivity) for that Bot. Also used by the debug preview. */
    static void notifyReply(Context context,String botId,String name,String text,Intent open){post(context,botId,name,text,"New reply",open);}
    /** The Bot's notice, with name as its title; hidden is all the lock screen shows. */
    private static void post(Context context,String botId,String name,String text,String hidden,Intent open){
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
        // A watch buzzes rather than rings, so the channel asks for vibration outright.
        NotificationChannel channel=new NotificationChannel(REPLIES,"Bot replies",NotificationManager.IMPORTANCE_HIGH);channel.enableVibration(true);
        manager.createNotificationChannel(channel);manager.deleteNotificationChannel(OLD_REPLIES);
        text=MessageText.plain(text);if(text.isEmpty())text="New message";
        PendingIntent tap=PendingIntent.getActivity(context,botId.hashCode(),open.setAction("open:"+botId).putExtra(OPEN_BOT,botId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        // The lock screen shows that something came, not what it says.
        Notification neutral=new NotificationCompat.Builder(context,REPLIES).setSmallIcon(R.drawable.ic_reply_status).setContentTitle("Grok Wear").setContentText(hidden).build();
        manager.notify(tag(botId),REPLY,new NotificationCompat.Builder(context,REPLIES).setSmallIcon(R.drawable.ic_reply_status)
            .setContentTitle(name).setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(neutral).setContentIntent(tap).setAutoCancel(true).build());
    }

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BotEvents.Waits waits=new BotEvents.Waits();
    private String identity="";private boolean listening,destroyed;private int lastStart;
    private final Runnable tick=new Runnable(){public void run(){int before=waits.size();waits.expire(SystemClock.elapsedRealtime());if(finishIfDone())return;if(waits.size()!=before)waiting();main.postDelayed(this,15000);}};
    private final EventStream.Listener events=new EventStream.Listener(){
        public void bot(String account,JSONObject row){if(account.equals(identity))handle(()->waits.bot(row,SystemClock.elapsedRealtime()));}
        public void entry(String account,String botId,JSONObject entry){if(!account.equals(identity))return;handle(()->waits.entry(botId,entry,System.currentTimeMillis()));revealLater(entry.optJSONObject("question"));}
        // The stream had a gap: replies in it never arrive as events.
        public void connected(String account,boolean again){if(again&&account.equals(identity))catchUp();}
        // Grok took the message but the Bot is out of usage: no reply is coming, so say that instead.
        public void usage(String account,JSONObject pushed){if(pushed!=null&&account.equals(identity))handle(()->waits.usage(pushed,SystemClock.elapsedRealtime(),System.currentTimeMillis()));}
    };
    /** A question from the Bot's phone call that Grok reveals later: look again then (BotEvents.Waits.reveal). */
    private void revealLater(JSONObject question){
        long delay=question==null?0:question.optLong("revealAtMs")-System.currentTimeMillis();
        if(delay>0&&delay<=BotEvents.Waits.LIMIT_MS)main.postDelayed(()->handle(()->waits.reveal(System.currentTimeMillis())),delay+100);
    }
    private interface Check{JSONObject run()throws Exception;}
    private void handle(Check check){
        if(destroyed)return; // A read that finished after the service ended.
        JSONObject alert;try{alert=check.run();}catch(Exception ignored){return;}
        if(alert==null)return;alert(alert);if(!finishIfDone())waiting();
    }

    /**
     * Reads what events can't tell after the fact: each waited-on chat for a Bot message after yours (as the
     * official app reads the transcript tail after a gap), then the roster for unread counts and idle Bots.
     */
    private void catchUp(){
        final String expected=identity;final java.util.Map<String,String> pending=waits.messages();
        worker.execute(()->{BotAccount account=new BotAccount(ReplyAlertService.this);
            for(java.util.Map.Entry<String,String> wait:pending.entrySet()){
                try{JSONObject reply=account.replyAfter(wait.getValue());
                    if(reply!=null)main.post(()->{if(expected.equals(identity))handle(()->waits.found(wait.getKey(),wait.getValue(),reply));});}
                catch(Exception unavailable){}}
            try{JSONArray rows=account.bots();
                main.post(()->{if(!expected.equals(identity))return;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&pending.containsKey(row.optString("id")))handle(()->waits.bot(row,SystemClock.elapsedRealtime()));}});
            }catch(Exception unavailable){}});
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){ProblemReport.watchCrashes(this);
        lastStart=startId;
        if(intent==null||!WATCH.equals(intent.getAction())){finishIfDone();return START_NOT_STICKY;}
        String account=intent.getStringExtra("identity"),botId=intent.getStringExtra("bot"),name=intent.getStringExtra("name"),message=intent.getStringExtra("message");
        try{startForeground(NOTIFICATION,waitingNotification(name),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);}
        catch(RuntimeException refused){stopSelf();return START_NOT_STICKY;}
        if(account==null||account.isEmpty()||botId==null||botId.isEmpty()||message==null||message.isEmpty()){finishIfDone();return START_NOT_STICKY;}
        // Another account signed in since: its waits don't carry over.
        if(!account.equals(identity)){waits.clear();identity=account;}
        waits.start(botId,name==null||name.isEmpty()?"Your Bot":name,message,SystemClock.elapsedRealtime());
        if(!listening){listening=true;EventStream.add(this,events);}
        main.removeCallbacks(tick);main.postDelayed(tick,15000);
        // In case it already answered (say, while a permission dialog was up).
        catchUp();waiting();return START_NOT_STICKY;
    }
    @Override public void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);if(listening)EventStream.remove(events);listening=false;worker.shutdownNow();super.onDestroy();}
    /** Android 15+ caps data-sync services at six hours a day; at the cap, stop rather than be stopped. */
    @Override public void onTimeout(int startId,int foregroundServiceType){waits.clear();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private boolean finishIfDone(){
        if(!waits.isEmpty())return false;
        // Only if no newer send has asked to wait meanwhile; that start keeps the service.
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(lastStart);return true;
    }
    private void waiting(){
        if(waits.isEmpty())return;NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.notify(NOTIFICATION,waitingNotification(waits.size()==1?waits.firstName():waits.size()+" Bots"));
    }
    private void alert(JSONObject alert){
        String botId=alert.optString("bot");
        // That chat is on screen: the app already shows the reply. Alerts turned off since: say nothing.
        if(botId.equals(MainActivity.openChat)||!enabled(this))return;
        // Out of usage: Grok's own words in place of a reply; the lock screen still names nothing.
        if(alert.optBoolean("usage")){post(this,botId,BotEvents.Waits.USAGE_TITLE,BotEvents.Waits.USAGE_TEXT,"New notice",new Intent(this,MainActivity.class));return;}
        // The Bot asks for your OK first: tapping opens its chat, where the request can be answered.
        if(alert.optBoolean("approval")){String line=alert.optString("text");post(this,botId,"Needs your OK",line.isEmpty()?"Approval needed":line,"New notice",new Intent(this,MainActivity.class));return;}
        // A question for you, from someone on the Bot's phone call or the Bot's own with options: who asks, then the question; tapping opens its chat to answer.
        if(alert.optBoolean("question")){String line=alert.optString("text");post(this,botId,alert.optString("title"),line.isEmpty()?"Answer in the chat":line,"New notice",new Intent(this,MainActivity.class));return;}
        notifyReply(this,botId,alert.optString("name"),alert.optString("text"),new Intent(this,MainActivity.class));
    }
    private Notification waitingNotification(String name){
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.createNotificationChannel(new NotificationChannel(WAITING,"Waiting for replies",NotificationManager.IMPORTANCE_MIN));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this,WAITING).setSmallIcon(R.drawable.ic_reply_status).setContentTitle("Waiting for a reply")
            .setContentText(name==null||name.isEmpty()?"Your Bot":name).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).build();
    }
}
