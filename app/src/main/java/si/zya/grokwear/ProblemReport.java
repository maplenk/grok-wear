package si.zya.grokwear;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A short private log of what happened, for Report a problem and the Bot list's details: Bot list loads,
 * calls and sign-in, with how long they took, the network and why they failed. A reason is the app's own
 * wording, a service status or an error type: never a message from the network stack (those can name the
 * account's server), account data, Bot names or anything said or shown. The report is a link to a new
 * GitHub issue, shown as a QR code for the phone, with the watch model, the versions and as much of the
 * log as still scans on a watch screen: an email to the developer (the default, private), or a new
 * GitHub issue (public). Wear OS mail apps don't take a drafted email from other apps, so the phone sends it.
 */
final class ProblemReport {
    private ProblemReport(){}
    static final String ISSUES="https://github.com/maplenk/grok-wear/issues/new",EMAIL="namankhator@gmail.com";
    private static final String PREFS="grokwear_ui",LOG="recent_problems";
    private static final int KEEP=15,LONGEST=130;
    /** Encoded link length: past about this, a QR code at watch size gets hard for a phone to read. */
    static final int LINK_BUDGET=900;

    static void note(Context context,String where,Throwable failure){note(context,where,failure,"");}
    /** A failure: where, why, and anything else worth knowing, such as the time taken and the network. */
    static void note(Context context,String where,Throwable failure,String detail){event(context,where+": "+describe(failure)+(detail.isEmpty()?"":" · "+detail));}
    static void event(Context context,String text){event(context,text,"");}
    /**
     * Keeps the newest lines, with the time in UTC, across restarts: the report is often made later.
     * sameKind: a newest line starting with it is replaced, so a routine success repeated every minute
     * keeps one line, the latest, instead of pushing the failures out.
     */
    static synchronized void event(Context context,String text,String sameKind){
        try{SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            SimpleDateFormat time=new SimpleDateFormat("MM-dd HH:mm",Locale.ROOT);time.setTimeZone(TimeZone.getTimeZone("UTC"));
            prefs.edit().putString(LOG,add(prefs.getString(LOG,""),time.format(new Date())+"Z "+text,sameKind)).apply();}
        catch(RuntimeException unavailable){}
    }
    /** Newest first, one per line. */
    static String log(Context context){try{return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LOG,"");}catch(RuntimeException unavailable){return "";}}
    /** Signing out clears it with the rest of the account's data. */
    static synchronized void forget(Context context){try{context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(LOG).apply();}catch(RuntimeException unavailable){}}
    static String add(String recent,String line){return add(recent,line,"");}
    /** Newest first, one per line, at most KEEP; the newest is replaced when it's the same kind (after its "MM-dd HH:mmZ " time). */
    static String add(String recent,String line,String sameKind){
        StringBuilder out=new StringBuilder(clean(line));int kept=1;boolean first=true;
        for(String old:recent.split("\n")){if(old.isEmpty())continue;
            if(first&&!sameKind.isEmpty()&&old.length()>13&&old.startsWith(sameKind,13)){first=false;continue;}first=false;
            if(kept<KEEP){out.append('\n').append(old);kept++;}}
        return out.toString();
    }
    private static String clean(String text){return clean(text,LONGEST);}
    private static String clean(String text,int longest){text=text.replaceAll("[\\s\\p{Cc}]+"," ").trim();return text.length()>longest?text.substring(0,longest-1)+"…":text;}
    /** Charge and Battery Saver: a saver that throttles the network explains some slow loads. */
    static String battery(Context context){
        try{android.os.BatteryManager meter=context.getSystemService(android.os.BatteryManager.class);android.os.PowerManager power=context.getSystemService(android.os.PowerManager.class);
            int level=meter==null?-1:meter.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return "battery "+(level>=0?level+"%":"unknown")+(power!=null&&power.isPowerSaveMode()?", saver on":"");}
        catch(RuntimeException unknown){return "battery unknown";}
    }
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
    /** The watch's way online (its own Wi-Fi or LTE, or through the phone over Bluetooth) and Android's estimate of its download speed. */
    static String network(Context context){
        NetworkCapabilities link=link(context);if(link==null)return "offline";
        int down=link.getLinkDownstreamBandwidthKbps();
        return kind(link)+(down>0?" "+mbps(down):"");
    }
    /** For the report's header: both speed estimates and, on Wi-Fi, the signal. */
    static String networkDetails(Context context){
        NetworkCapabilities link=link(context);if(link==null)return "offline";
        int down=link.getLinkDownstreamBandwidthKbps(),up=link.getLinkUpstreamBandwidthKbps(),signal=link.getSignalStrength();
        return kind(link)+(down>0?", est. "+mbps(down)+" down":"")+(up>0?", "+mbps(up)+" up":"")
            +(signal!=NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED&&signal<0?", signal "+signal+" dBm":"");
    }
    private static NetworkCapabilities link(Context context){
        try{ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
            return manager==null||manager.getActiveNetwork()==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());}
        catch(RuntimeException unknown){return null;}
    }
    private static String kind(NetworkCapabilities link){
        if(link.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi";
        if(link.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))return "via phone";
        if(link.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "LTE";
        return "other network";
    }
    static String mbps(int kbps){return kbps<10_000?String.format(Locale.ROOT,"%.1f Mbps",kbps/1000.0):kbps/1000+" Mbps";}
    /** Size for the log: bytes as KB, or MB above 1,000 KB. */
    static String size(long bytes){return bytes<1_000_000?Math.max(1,(bytes+500)/1000)+" KB":String.format(Locale.ROOT,"%.1f MB",bytes/1_000_000.0);}
    /** "Google Google Pixel Watch" reads as "Google Pixel Watch": many models already start with the maker. */
    static String watch(String maker,String model){return model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))?model:maker+" "+model;}
    /** Seconds with one decimal, for the log. */
    static String seconds(long ms){return String.format(Locale.ROOT,"%.1f s",ms/1000.0);}
    /**
     * where: the screen and message the report was made from, such as "Bot list: Bots aren’t available yet (HTTP 503)…", or "".
     * state: the app's side, such as how many Bots and when they last loaded, so a report says something even when nothing failed.
     */
    static String url(Context context,String where,String state,boolean email){
        return url(watch(Build.MANUFACTURER,Build.MODEL),Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")",
            BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")",AssistantRole.held(context),networkDetails(context),
            state+(state.isEmpty()?"":" · ")+battery(context),where,log(context),email);
    }
    /** The details, then the newest log lines that keep the link within LINK_BUDGET. The title names where it happened. */
    static String url(String watch,String android,String version,boolean button,String network,String state,String where,String log,boolean email){
        String place=where.isEmpty()?"":clean(where.split(":",2)[0]);
        String head=(where.isEmpty()?"":"Where: "+clean(where)+"\n")+"Watch: "+clean(watch)+"\nAndroid: "+clean(android)+"\nGrok Wear: "+clean(version)+"\nAssistant button: "+(button?"on":"off")
            +"\nNetwork: "+clean(network)+(state.isEmpty()?"":"\nState: "+clean(state,160))+"\nLog (UTC, newest first):\n",tail="\nWhat happened?\n",title=(place.isEmpty()?"Problem":place+" problem")+" on "+clean(watch);
        StringBuilder lines=new StringBuilder();
        // " · " reads well on the watch but takes 8 characters in a link; " - " takes 3.
        for(String line:log.split("\n")){if(line.isEmpty())continue;
            String more=lines+line.replace(" · "," - ")+"\n";
            if(link(email,title,head+more+tail).length()>LINK_BUDGET)break;
            lines.setLength(0);lines.append(more);}
        return link(email,title,head+(lines.length()==0?"none\n":lines.toString())+tail);
    }
    /** Mail apps read a space only as %20 (RFC 6068); GitHub also takes "+", which is shorter. */
    private static String link(boolean email,String title,String body){
        return email?"mailto:"+EMAIL+"?subject="+encode(title).replace("+","%20")+"&body="+encode(body).replace("+","%20")
            :ISSUES+"?title="+encode(title)+"&body="+encode(body);
    }
    /**
     * Form encoding that leaves the characters a query may carry as they are (RFC 3986): every escape
     * adds two characters, and each one makes the QR code denser.
     */
    static String encode(String text){
        StringBuilder out=new StringBuilder();
        for(byte b:text.getBytes(java.nio.charset.StandardCharsets.UTF_8)){int c=b&0xff;
            if(c==' ')out.append('+');
            else if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~:/(),;@!*'".indexOf(c)>=0)out.append((char)c);
            else out.append('%').append(HEX.charAt(c>>4)).append(HEX.charAt(c&15));}
        return out.toString();
    }
    private static final String HEX="0123456789ABCDEF";
}
