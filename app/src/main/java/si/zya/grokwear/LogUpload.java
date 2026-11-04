package si.zya.grokwear;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Report a problem's optional "Send logs" (off by default, asked each time): one Sentry envelope with the
 * report's details and the full recent log as a text attachment, so a report isn't limited to what fits in
 * a QR code. No Sentry SDK: nothing runs or is sent unless the person turns it on and taps Send.
 *
 * Format: Sentry's envelope endpoint, {@code POST {scheme}://{host}{path}/api/{project}/envelope/} with
 * {@code X-Sentry-Auth: Sentry sentry_version=7, sentry_client=…, sentry_key=…}
 * (develop.sentry.dev/sdk/foundations/transport/authentication and …/envelopes). A DSN only allows sending
 * new events, so it is safe in the app. Never sent: account ids, Bot names, chats, tokens, the user's IP
 * as a field.
 */
final class LogUpload {
    private LogUpload(){}
    static final class Dsn {
        final String endpoint,key;
        Dsn(String endpoint,String key){this.endpoint=endpoint;this.key=key;}
    }
    /** "https://KEY@HOST[/path]/PROJECT" → the envelope endpoint and public key; null when absent or malformed. */
    static Dsn parse(String dsn){
        if(dsn==null)return null;dsn=dsn.trim();if(dsn.isEmpty()||dsn.length()>300)return null;
        try{
            java.net.URI uri=new java.net.URI(dsn);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()==null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)return null;
            String key=uri.getRawUserInfo();int colon=key.indexOf(':');if(colon>=0)key=key.substring(0,colon);
            if(!key.matches("[A-Za-z0-9]{8,64}"))return null;
            String path=uri.getRawPath()==null?"":uri.getRawPath();int slash=path.lastIndexOf('/');if(slash<0)return null;
            String project=path.substring(slash+1),prefix=path.substring(0,slash);
            if(!project.matches("[A-Za-z0-9_-]{1,40}")||!prefix.matches("(/[A-Za-z0-9._-]+)*"))return null;
            String host=uri.getHost()+(uri.getPort()==-1?"":":"+uri.getPort());
            return new Dsn("https://"+host+prefix+"/api/"+project+"/envelope/",key);
        }catch(Exception malformed){return null;}
    }
    /** The option shows only in builds made with a Sentry DSN. */
    static boolean available(){return parse(BuildConfig.SENTRY_DSN)!=null;}

    /** 32 lowercase hex characters, as Sentry's event_id. */
    static String newEventId(){return UUID.randomUUID().toString().replace("-","");}
    /** The short id a person can read out; also an event tag, so it can be searched in Sentry. */
    static String shortId(String eventId){return eventId.substring(0,8);}

    /** An envelope: header, one event, one text attachment (develop.sentry.dev envelope format, explicit lengths). */
    static byte[] envelope(String eventId,long nowMs,String message,Map<String,String> tags,String release,String attachment)throws Exception {
        JSONObject header=new JSONObject().put("event_id",eventId).put("sent_at",iso(nowMs));
        JSONObject tagJson=new JSONObject();for(Map.Entry<String,String> tag:new TreeMap<>(tags).entrySet())tagJson.put(tag.getKey(),clip(tag.getValue(),190));
        JSONObject event=new JSONObject().put("event_id",eventId).put("timestamp",nowMs/1000.0).put("platform","java").put("level","info")
                .put("logger","grok-wear.report").put("release",release).put("environment","production")
                .put("message",new JSONObject().put("formatted",clip(message.isEmpty()?"Report a problem":message,500)))
                .put("tags",tagJson).put("sdk",new JSONObject().put("name","grok-wear.report").put("version",BuildConfig.VERSION_NAME)
                        // Without this Sentry takes the IP from the connection and stores a location (city, country) with it.
                        .put("settings",new JSONObject().put("infer_ip","never")));
        byte[] eventBytes=event.toString().getBytes(StandardCharsets.UTF_8),text=attachment.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        line(out,header.toString());
        line(out,new JSONObject().put("type","event").put("length",eventBytes.length).put("content_type","application/json").toString());
        out.write(eventBytes);out.write('\n');
        line(out,new JSONObject().put("type","attachment").put("length",text.length).put("filename","grok-wear-log.txt").put("content_type","text/plain").toString());
        out.write(text);out.write('\n');
        return out.toByteArray();
    }
    static String authHeader(String key){return "Sentry sentry_version=7, sentry_client=grok-wear/"+BuildConfig.VERSION_NAME+", sentry_key="+key;}

    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    /**
     * Sends one report. Returns its short Log ID. Throws a Failure with a short reason; the QR code works either way.
     * where: the screen and message the report was made from; state: the app's side, as in the QR.
     */
    static String send(Context context,String where,String state)throws Exception {
        Dsn dsn=parse(BuildConfig.SENTRY_DSN);if(dsn==null)throw new GrokCore.Failure("Sending logs isn’t set up in this build.",true);
        String eventId=newEventId(),id=shortId(eventId);
        Map<String,String> tags=new TreeMap<>();
        tags.put("log_id",id);tags.put("app_version",BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")");
        tags.put("watch",ProblemReport.watch(Build.MANUFACTURER,Build.MODEL));tags.put("api",String.valueOf(Build.VERSION.SDK_INT));
        tags.put("network",ProblemReport.networkKind(context));
        String where2=where.isEmpty()?"":where.split(":",2)[0];if(!where2.isEmpty())tags.put("where",where2);
        byte[] body=envelope(eventId,System.currentTimeMillis(),where,tags,"grok-wear@"+BuildConfig.VERSION_NAME+"+"+BuildConfig.VERSION_CODE,
                ProblemReport.fullText(context,where,state,id));
        Request request=new Request.Builder().url(dsn.endpoint).header("X-Sentry-Auth",authHeader(dsn.key))
                .header("User-Agent","grok-wear/"+BuildConfig.VERSION_NAME)
                .post(RequestBody.create(body,MediaType.get("application/x-sentry-envelope"))).build();
        long started=android.os.SystemClock.elapsedRealtime();
        try(Response response=HTTP.newCall(request).execute()){
            long took=android.os.SystemClock.elapsedRealtime()-started;
            if(response.code()==200){ProblemReport.event(context,"Logs sent: "+id+" · "+ProblemReport.seconds(took));return id;}
            ProblemReport.event(context,"Logs not sent: HTTP "+response.code()+" · "+ProblemReport.seconds(took));
            throw new GrokCore.Failure(response.code()==429?"Sentry is busy. Try again later.":"Sentry didn’t take the logs (HTTP "+response.code()+").",true,"",response.code());
        }catch(GrokCore.Failure refused){throw refused;}
        catch(Exception failed){ProblemReport.note(context,"Logs not sent",failed);GrokCore.Failure lost=new GrokCore.Failure("Couldn’t reach Sentry. Check the connection.",false);lost.initCause(failed);throw lost;}
    }

    private static void line(ByteArrayOutputStream out,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.write(bytes,0,bytes.length);out.write('\n');}
    private static String clip(String text,int longest){return text.length()>longest?text.substring(0,longest-1)+"…":text;}
    private static String iso(long ms){SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date(ms));}
}
