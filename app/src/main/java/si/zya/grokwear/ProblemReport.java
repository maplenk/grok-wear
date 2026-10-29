package si.zya.grokwear;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Report a problem": a link to a new GitHub issue, shown as a QR code for the phone. It carries the
 * watch model, the Android and app versions and the last few errors. An error is the app's own wording,
 * a service status or an error type: never a message from the network stack (those can name the
 * account's server), account data, or anything said or shown.
 */
final class ProblemReport {
    private ProblemReport(){}
    static final String ISSUES="https://github.com/maplenk/grok-wear/issues/new";
    private static final String PREFS="grokwear_ui",RECENT="recent_problems";
    private static final int KEEP=3,LONGEST=90;

    static void note(Context context,String where,Throwable failure){note(context,where,describe(failure));}
    /** Keeps the newest few, with the time in UTC, across restarts: the report is often made later. */
    static synchronized void note(Context context,String where,String why){
        try{SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            SimpleDateFormat time=new SimpleDateFormat("MM-dd HH:mm",Locale.ROOT);time.setTimeZone(TimeZone.getTimeZone("UTC"));
            prefs.edit().putString(RECENT,add(prefs.getString(RECENT,""),time.format(new Date())+"Z "+where+": "+why)).apply();}
        catch(RuntimeException unavailable){}
    }
    /** Signing out clears them with the rest of the account's data. */
    static synchronized void forget(Context context){try{context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(RECENT).apply();}catch(RuntimeException unavailable){}}
    /** Newest first, one per line, at most KEEP. */
    static String add(String recent,String line){
        StringBuilder out=new StringBuilder(clean(line));int kept=1;
        for(String old:recent.split("\n"))if(!old.isEmpty()&&kept<KEEP){out.append('\n').append(old);kept++;}
        return out.toString();
    }
    private static String clean(String text){text=text.replaceAll("[\\s\\p{Cc}]+"," ").trim();return text.length()>LONGEST?text.substring(0,LONGEST-1)+"…":text;}
    /** The service's status or code, the cause's type, or the app's own wording. */
    static String describe(Throwable failure){
        String code=code(failure);
        return !code.isEmpty()?code:failure==null?"unknown":String.valueOf(failure.getMessage());
    }
    /** Short enough for the screen: the service's status or code, or the error's type; "" for the app's own refusals. */
    static String code(Throwable failure){
        if(failure instanceof GrokCore.Failure){GrokCore.Failure refused=(GrokCore.Failure)failure;
            if(refused.status>0)return "HTTP "+refused.status+(refused.code.isEmpty()?"":" "+refused.code);
            if(!refused.code.isEmpty())return refused.code;
            return refused.getCause()!=null?refused.getCause().getClass().getSimpleName():"";}
        return failure==null?"":failure.getClass().getSimpleName();
    }
    static String url(Context context){
        String recent="";try{recent=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(RECENT,"");}catch(RuntimeException unavailable){}
        return url(Build.MANUFACTURER+" "+Build.MODEL,Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")",
            BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")",AssistantRole.held(context),recent);
    }
    /** Short, so the QR code stays easy to scan on a watch screen. */
    static String url(String watch,String android,String version,boolean button,String recent){
        String body="Watch: "+clean(watch)+"\nAndroid: "+clean(android)+"\nGrok Wear: "+clean(version)+"\nAssistant button: "+(button?"on":"off")
            +"\nRecent errors (UTC):\n"+(recent.isEmpty()?"none":recent)+"\n\nWhat happened?\n";
        return ISSUES+"?title="+encode("Problem on "+clean(watch))+"&body="+encode(body);
    }
    private static String encode(String text){try{return URLEncoder.encode(text,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){return "";}}
}
