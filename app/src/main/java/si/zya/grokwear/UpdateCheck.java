package si.zya.grokwear;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * Tells the watch when a newer Grok Wear is out. Once a day while the app is open, it reads GitHub's public "latest
 * release" for this project: an anonymous request that carries no sign-in, account, chat or device details. It can
 * be turned off in Settings.
 */
final class UpdateCheck {
    private UpdateCheck(){}
    private static final String API="https://api.github.com/repos/maplenk/grok-wear/releases/latest";
    private static final String PAGE="https://github.com/maplenk/grok-wear/releases/tag/v";
    private static final String PREFS="grokwear_updates";
    static final long DAY_MS=24L*60*60*1000;
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static OkHttpClient client;

    /** The version a release tag names ("v0.7.3" or "0.7.3"), or "" for anything else. */
    static String versionOf(String tag){
        String v=tag==null?"":tag.startsWith("v")?tag.substring(1):tag;
        return v.matches("\\d{1,4}(\\.\\d{1,4}){1,3}")?v:"";
    }
    /** Compares dotted versions numerically; a missing part counts as 0 and anything unreadable as oldest. */
    static int compare(String a,String b){
        String[] x=a==null?new String[0]:a.split("\\."),y=b==null?new String[0]:b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            int p=part(x,i),q=part(y,i);if(p!=q)return Integer.compare(p,q);
        }
        return 0;
    }
    private static int part(String[] parts,int i){
        if(i>=parts.length)return 0;
        try{return Integer.parseInt(parts[i].replaceAll("[^0-9].*$",""));}catch(NumberFormatException unreadable){return -1;}
    }
    /** The release version if it is newer than current, else "". */
    static String newer(String latest,String current){String v=versionOf(latest);return !v.isEmpty()&&compare(v,current)>0?v:"";}
    /** Whether a day has passed since the last answer (or the clock went back). */
    static boolean due(long last,long now){return last<=0||now<last||now-last>=DAY_MS;}
    /** The release page for a version, where its download and notes are. */
    static String page(String version){return PAGE+version;}

    private static SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static boolean enabled(Context context){try{return prefs(context).getBoolean("enabled",true);}catch(RuntimeException unavailable){return false;}}
    /** Turning it off also forgets any newer version it had found. */
    static void setEnabled(Context context,boolean on){
        try{SharedPreferences.Editor edit=prefs(context).edit().putBoolean("enabled",on);if(!on)edit.remove("latest").remove("last_ms");edit.apply();}
        catch(RuntimeException unavailable){}
    }
    /** The newer version found, still newer than this app, or "". */
    static String available(Context context){
        if(!enabled(context))return "";
        try{return newer(prefs(context).getString("latest",""),BuildConfig.VERSION_NAME);}catch(RuntimeException unavailable){return "";}
    }

    /**
     * Asks GitHub in the background when a day has passed. changed runs on the main thread when the answer names a
     * version this watch didn't know about. A failed request is asked again the next time the app opens.
     */
    static void maybeCheck(Context context,Runnable changed){
        final Context app=context.getApplicationContext();
        if(!enabled(app))return;
        long last;try{last=prefs(app).getLong("last_ms",0);}catch(RuntimeException unavailable){return;}
        if(!due(last,System.currentTimeMillis())||!RUNNING.compareAndSet(false,true))return;
        Thread thread=new Thread(()->{
            try{
                String before=available(app),latest=fetch();
                if(latest==null)return;
                prefs(app).edit().putString("latest",latest).putLong("last_ms",System.currentTimeMillis()).apply();
                if(BuildConfig.DEBUG)android.util.Log.i("GrokWearUpdate","latest="+latest+" newer="+(available(app).isEmpty()?"none":available(app)));
                if(!available(app).equals(before)&&changed!=null)new android.os.Handler(android.os.Looper.getMainLooper()).post(changed);
            }catch(Exception unavailable){
                if(BuildConfig.DEBUG)android.util.Log.i("GrokWearUpdate","check failed: "+unavailable.getClass().getSimpleName());
            }finally{RUNNING.set(false);}
        },"grok-update-check");
        thread.setDaemon(true);thread.start();
    }
    /** The latest release's version, "" when GitHub has none, or null when it couldn't be read. */
    private static String fetch()throws Exception {
        synchronized(UpdateCheck.class){
            if(client==null)client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
                .callTimeout(20,TimeUnit.SECONDS).build();
        }
        Request request=new Request.Builder().url(API).get().header("Accept","application/vnd.github+json")
            .header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","GrokWear").build();
        try(Response response=client.newCall(request).execute()){
            if(response.code()==404)return "";
            if(response.code()!=200||response.body()==null)return null;
            byte[] body=GrokVault.readBounded(response.body().byteStream(),512*1024);
            return versionOf(new JSONObject(new String(body,java.nio.charset.StandardCharsets.UTF_8)).optString("tag_name"));
        }
    }

    /** Debug builds: pretend GitHub named this version, to see the notice. */
    static void debugPretend(Context context,String version){
        if(!BuildConfig.DEBUG)return;
        prefs(context).edit().putString("latest",version==null?"":version).putLong("last_ms",System.currentTimeMillis()).apply();
    }
    /** Debug builds: forget the last answer, so the app asks GitHub when it next opens. */
    static void debugForget(Context context){if(BuildConfig.DEBUG)prefs(context).edit().remove("last_ms").remove("latest").apply();}
}
