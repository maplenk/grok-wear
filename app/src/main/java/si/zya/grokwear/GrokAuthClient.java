package si.zya.grokwear;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Fixed vendor destinations only. Calls must run on GrokClient's background worker. */
final class GrokAuthClient {
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(20,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    // EnsureSandBox answers once the Bot's sandbox is running, which can take a while when it was asleep.
    private static final OkHttpClient SANDBOX=HTTP.newBuilder().readTimeout(45,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build();
    // The official clients give the access check 10 s.
    private static final OkHttpClient ACCESS=HTTP.newBuilder().callTimeout(10,TimeUnit.SECONDS).build();
    static void networkChanged(){try{HTTP.connectionPool().evictAll();}catch(RuntimeException ignored){}}
    final GrokAuthCore core;
    GrokAuthClient(Context context) {
        Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
        core=new GrokAuthCore(new GrokAuthVault(context),(operation,input)->timed(app,operation,input),System::currentTimeMillis,
                text->ProblemReport.event(app,text));
    }
    JSONObject begin()throws Exception{return core.begin();}
    JSONObject poll()throws Exception{return core.poll();}
    JSONObject pendingLogin()throws Exception{return core.pendingLogin();}
    JSONObject state()throws Exception{return core.state();}
    JSONObject currentConfig()throws Exception{return core.currentConfig();}
    void forgetConfig(String gatewayToken)throws Exception{core.forgetConfig(gatewayToken);}
    void cancel()throws Exception{core.cancel();}
    void unlink()throws Exception{core.unlink();}

    /**
     * For Report a problem: a slow or failed renewal, sandbox start or access check, with its time and the
     * network. Sign-in polling repeats every few seconds while it waits (404), so only its failures are kept.
     */
    private static GrokAuthCore.Reply timed(Context context,String operation,JSONObject input)throws Exception {
        long started=android.os.SystemClock.elapsedRealtime();String what=what(operation);boolean poll=GrokAuthCore.POLL.equals(operation);
        try{GrokAuthCore.Reply reply=request(operation,input);long took=android.os.SystemClock.elapsedRealtime()-started;
            boolean failed=reply.status!=200&&!(poll&&reply.status==404);
            if(failed||(!poll&&took>=3000))
                ProblemReport.event(context,what+": "+(reply.status==200?"ok":reply.refusal.summary())+" · "+ProblemReport.seconds(took)+" · "+ProblemReport.network(context));
            return reply;}
        catch(Exception failed){
            ProblemReport.note(context,what,failed,"after "+ProblemReport.seconds(android.os.SystemClock.elapsedRealtime()-started)+" · "+ProblemReport.network(context));
            throw failed;}
    }
    private static String what(String operation){
        switch(operation){
            case GrokAuthCore.BOOTSTRAP:return "Bot sandbox start";
            case GrokAuthCore.ACCESS:return "Access check";
            case GrokAuthCore.POLL:return "Sign-in check";
            default:return "Sign-in renewal";
        }
    }
    private static GrokAuthCore.Reply request(String operation,JSONObject input)throws Exception {
        Request.Builder request=new Request.Builder().header("Content-Type","application/json");
        if(GrokAuthCore.POLL.equals(operation)) {
            HttpUrl url=HttpUrl.get(GrokAuthCore.BACKEND+"/auth/poll").newBuilder()
                    .addQueryParameter("uuid",input.getString("uuid")).addQueryParameter("verifier",input.getString("verifier")).build();
            request.url(url).get();
        } else if(GrokAuthCore.REFRESH.equals(operation)) {
            JSONObject body=new JSONObject().put("client_id",GrokAuthCore.CLIENT_ID).put("grant_type","refresh_token")
                    .put("refresh_token",input.getString("refreshToken"));
            request.url(GrokAuthCore.BACKEND+"/oauth/token").post(json(body));
        } else if(GrokAuthCore.BOOTSTRAP.equals(operation)||GrokAuthCore.ACCESS.equals(operation)) {
            String path=GrokAuthCore.BOOTSTRAP.equals(operation)?"/aiserver.v1.GrokBotService/EnsureSandBox":"/aiserver.v1.DashboardService/GetSandAccessStatus";
            request.url(GrokAuthCore.BACKEND+path).post(json(new JSONObject()))
                    .header("Authorization","Bearer "+input.getString("accessToken"))
                    .header("connect-protocol-version","1").header("x-cursor-client-type","sand")
                    .header("x-cursor-client-version","0.71.2").header("x-sand-box-namespace","prod");
            if(input.has("selectedTeamId"))request.header("x-cursor-team-id",Long.toString(input.getLong("selectedTeamId")));
        } else throw new GrokCore.Failure("This Grok sign-in operation is not enabled.",true);
        OkHttpClient client=GrokAuthCore.BOOTSTRAP.equals(operation)?SANDBOX:GrokAuthCore.ACCESS.equals(operation)?ACCESS:HTTP;
        try(Response response=client.newCall(request.build()).execute()) {
            // Error bodies can contain account data: only the status, Connect code, failure hint and scope reason are kept.
            if(response.code()!=200)return new GrokAuthCore.Reply(response.code(),new JSONObject(),refusal(response));
            if(response.body()==null)throw new java.io.IOException();
            byte[] body=GrokVault.readBounded(response.body().byteStream(),1024*1024);
            try{return new GrokAuthCore.Reply(response.code(),new JSONObject(new String(body,StandardCharsets.UTF_8)));}
            finally{java.util.Arrays.fill(body,(byte)0);}
        }
    }
    /** The refusal's safe parts; the body is read at most 16 KB and wiped. */
    static ServiceRefusal refusal(Response response){
        String hint=response.header(ServiceRefusal.HINT_HEADER);
        if(hint==null)hint=response.header("trailer-"+ServiceRefusal.HINT_HEADER);
        byte[] body=null;
        try{if(response.body()!=null)body=GrokVault.readBounded(response.body().byteStream(),16384);}catch(Exception unreadable){body=null;}
        try{return ServiceRefusal.parse(response.code(),hint,body);}
        finally{if(body!=null)java.util.Arrays.fill(body,(byte)0);}
    }
    private static RequestBody json(JSONObject object) {
        // one-shot disables OkHttp's follow-up replay of token-bearing POST bodies.
        byte[] bytes=object.toString().getBytes(StandardCharsets.UTF_8);
        return new RequestBody() {
            public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
            public long contentLength(){return bytes.length;}
            public boolean isOneShot(){return true;}
            public void writeTo(okio.BufferedSink sink)throws java.io.IOException {sink.write(bytes);}
        };
    }
}
