package si.zya.grokwear;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * One live event connection per process (see BotEvents), open while anything listens: the open app, or a
 * reply alert after you leave it. It reconnects with backoff, and at once when the watch gets a new network.
 * Events are hints on the main thread; the roster and chat are still read the usual way.
 */
final class EventStream {
    interface Listener {
        /** identity is the account ("account_key/auth_generation") the stream was opened for. */
        void bot(String identity,JSONObject row);
        void entry(String identity,String botId,JSONObject entry);
        /** The stream opened; after a gap (again) events may have been missed, so read once the usual way. */
        default void connected(String identity,boolean again){}
    }
    static final String CHANNELS="transcript,agent-upserted";
    // Leaving the app for the message picker or a permission dialog shouldn't drop and redial the stream.
    private static final long LINGER_MS=20000;
    private static final long[] BACKOFF={1000,2000,5000,10000,30000,60000};
    private static final Set<Listener> LISTENERS=new CopyOnWriteArraySet<>();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Object WAKE=new Object();
    private static Context app;private static long generation;private static boolean running,kicked;
    private static volatile okhttp3.Call live;private static volatile boolean open;private static volatile String identityNow="";
    private static ConnectivityManager.NetworkCallback network;private static volatile android.net.Network defaultNetwork;
    private static final Runnable idleStop=EventStream::stopIfIdle;
    private EventStream(){}

    static synchronized void add(Context context,Listener listener){
        LISTENERS.add(listener);MAIN.removeCallbacks(idleStop);
        if(running){kick();return;} // A stream waiting out the linger dials again now.
        app=context.getApplicationContext();running=true;final long mine=++generation;
        watchNetwork();
        Thread thread=new Thread(()->run(mine),"grok-events");thread.setDaemon(true);thread.start();
    }
    static synchronized void remove(Listener listener){
        LISTENERS.remove(listener);
        if(LISTENERS.isEmpty()){MAIN.removeCallbacks(idleStop);MAIN.postDelayed(idleStop,LINGER_MS);}
    }
    /** The account changed (say, signed out): drop this connection; the next attempt reads the new state. */
    static void restart(){okhttp3.Call call=live;if(call!=null)call.cancel();kick();}
    /** The app's account is this one: a stream still on another (signed out, then in again) starts over. */
    static void expect(String identity){String now=identityNow;if(!now.isEmpty()&&!now.equals(identity))restart();}
    /** True while the stream is connected, so polling can back off. */
    static boolean isOpen(){return open;}
    private static synchronized void stopIfIdle(){
        if(!LISTENERS.isEmpty()||!running)return;
        running=false;generation++;open=false;identityNow="";unwatchNetwork();
        okhttp3.Call call=live;if(call!=null)call.cancel();kick();
    }
    private static synchronized boolean current(long mine){return running&&generation==mine;}
    private static synchronized void stopSignedOut(long mine){if(!current(mine))return;running=false;generation++;open=false;identityNow="";unwatchNetwork();}
    // A thread from a stopped stream can still be finishing while a new one runs: only the current one
    // publishes its call and state, and a thread only ever clears its own call.
    private static synchronized void claim(long mine,okhttp3.Call call){if(current(mine))live=call;else call.cancel();}
    private static synchronized void connectedAs(long mine,String identity){if(current(mine)){open=true;identityNow=identity;}}
    private static synchronized void release(long mine,okhttp3.Call call){if(call!=null&&live==call)live=null;if(current(mine))open=false;}

    private static void run(long mine){
        BotAccount account=new BotAccount(app);int[] failures={0};boolean[] before={false};String[] identity={""};
        while(current(mine)){
            // Nobody listens (the linger after the app left the screen): don't dial; a new listener kicks.
            if(LISTENERS.isEmpty()){pause(LINGER_MS);continue;}
            long began=SystemClock.elapsedRealtime();String ended="closed";okhttp3.Call[] call={null};
            try{account.events(CHANNELS,new BotAccount.EventSink(){
                public void opened(okhttp3.Call made){call[0]=made;claim(mine,made);}
                public void connected(String account){
                    connectedAs(mine,account);identity[0]=account;final boolean again=before[0];before[0]=true;
                    android.util.Log.i("GrokWearEvents","events_open open_ms="+(SystemClock.elapsedRealtime()-began)+" again="+again+" failures="+failures[0]);
                    MAIN.post(()->{if(current(mine))for(Listener l:LISTENERS)l.connected(account,again);});
                }
                public boolean frame(String data){
                    if(!current(mine))return false;BotEvents.Event event=BotEvents.parse(data);if(event==null)return true;
                    final String account=identity[0];
                    MAIN.post(()->{if(!current(mine))return;
                        for(Listener l:LISTENERS){if(event.kind.equals("bot"))l.bot(account,event.value);else l.entry(account,event.botId,event.value);}});
                    return true;
                }
            });}
            catch(GrokCore.Failure refused){
                // Signed out: nothing to listen to until the app adds a listener again after sign-in.
                if(BotAccount.SIGNED_OUT.equals(refused.code)){release(mine,call[0]);stopSignedOut(mine);return;}
                ended="refused_"+refused.status;}
            catch(Exception dropped){ended=dropped.getClass().getSimpleName();}
            finally{release(mine,call[0]);}
            boolean stopping=!current(mine);
            android.util.Log.i("GrokWearEvents","events_end after_ms="+(SystemClock.elapsedRealtime()-began)+" cause="+(stopping?"stopped":ended));
            if(stopping)return;
            if(SystemClock.elapsedRealtime()-began>60000)failures[0]=0;
            pause(BACKOFF[Math.min(failures[0]++,BACKOFF.length-1)]);
        }
    }
    private static void pause(long ms){
        synchronized(WAKE){long end=SystemClock.elapsedRealtime()+ms;
            while(!kicked){long left=end-SystemClock.elapsedRealtime();if(left<=0)break;try{WAKE.wait(left);}catch(InterruptedException stop){break;}}
            kicked=false;}
    }
    private static void kick(){synchronized(WAKE){kicked=true;WAKE.notifyAll();}}
    /** A lost network ends the stream now instead of after the read timeout; a new one reconnects at once. */
    private static void watchNetwork(){
        ConnectivityManager manager=app.getSystemService(ConnectivityManager.class);if(manager==null)return;
        network=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(android.net.Network available){
                // The first call reports the current network; a different one later means a switch (say Bluetooth to Wi-Fi).
                boolean switched=defaultNetwork!=null&&!available.equals(defaultNetwork);defaultNetwork=available;
                if(switched){okhttp3.Call call=live;if(call!=null)call.cancel();}kick();}
            @Override public void onLost(android.net.Network lost){
                if(lost.equals(defaultNetwork))defaultNetwork=null;BotAccount.networkChanged();
                okhttp3.Call call=live;if(call!=null)call.cancel();}
        };
        try{manager.registerDefaultNetworkCallback(network);}catch(RuntimeException unavailable){network=null;}
    }
    private static void unwatchNetwork(){
        ConnectivityManager.NetworkCallback callback=network;network=null;defaultNetwork=null;if(callback==null)return;
        ConnectivityManager manager=app.getSystemService(ConnectivityManager.class);
        if(manager!=null)try{manager.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}
    }
}
