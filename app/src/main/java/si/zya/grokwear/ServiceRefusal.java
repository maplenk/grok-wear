package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Why Grok's backend refused a request, kept to what is safe to log and show: the HTTP status, the Connect
 * error code ("unauthenticated"), the backend's failure hint header ("SAND_CLIENT_UPDATE_REQUIRED") and the
 * usage-scope reason ("stale_team"). Never the error's message, which can carry account data.
 *
 * Grok Bot Android 1.18.0 reads the scope reason from the Connect error's aiserver.v1.ErrorDetails
 * ({@code debug.details.additionalInfo.sandUsageScopeError}); the desktop client reads the hint header.
 */
final class ServiceRefusal {
    static final String HINT_HEADER="x-automation-failure-hint";
    static final String STALE_TEAM="stale_team";
    final int status;
    /** Connect code such as "unauthenticated", or "". */
    final String code;
    /** x-automation-failure-hint value such as "SAND_CLIENT_UPDATE_REQUIRED", or "". */
    final String hint;
    /** ErrorDetails additionalInfo.sandUsageScopeError such as "stale_team", or "". */
    final String scope;

    ServiceRefusal(int status,String code,String hint,String scope){
        this.status=status;this.code=clean(code,"[a-z_]{1,40}");this.hint=clean(hint,"[A-Z0-9_]{1,40}");this.scope=clean(scope,"[A-Za-z0-9_]{1,40}");
    }

    /** body: the reply's first bytes, or null. Unreadable or unexpected bodies give a refusal with the status only. */
    static ServiceRefusal parse(int status,String hintHeader,byte[] body){
        String code="",scope="";
        try{
            if(body!=null&&body.length>0){
                JSONObject error=new JSONObject(new String(body,StandardCharsets.UTF_8));
                code=error.optString("code","");
                scope=scopeFrom(error.optJSONArray("details"));
            }
        }catch(Exception unreadable){/* Status and hint still count. */}
        return new ServiceRefusal(status,code,hintHeader==null?"":hintHeader.trim(),scope);
    }

    /** A team that was chosen at sign-in and no longer applies: Android drops it and asks again without it. */
    boolean staleTeam(){return status==401&&STALE_TEAM.equals(scope);}
    /** Grok no longer supports this client version (x-cursor-client-version). */
    boolean updateRequired(){return "SAND_CLIENT_UPDATE_REQUIRED".equals(hint);}
    /**
     * Like the desktop client: an unauthenticated or permission-denied refusal that carries no failure hint
     * is about the account's access to Grok Bot, which GetSandAccessStatus explains.
     */
    boolean accessRefusal(){return (status==401||status==403)&&hint.isEmpty();}

    /** For the log and reports: "HTTP 401 unauthenticated stale_team". */
    String summary(){
        StringBuilder out=new StringBuilder("HTTP ").append(status);
        if(!code.isEmpty())out.append(' ').append(code);
        if(!hint.isEmpty())out.append(' ').append(hint.toLowerCase(java.util.Locale.ROOT));
        if(!scope.isEmpty())out.append(' ').append(scope);
        return out.toString();
    }
    /** Short code kept on a Failure: the hint, the scope reason or the Connect code. */
    String failureCode(){return !hint.isEmpty()?hint.toLowerCase(java.util.Locale.ROOT):!scope.isEmpty()?scope:code;}

    /** Watch wording for the failure hints the official clients act on; "" for anything else. */
    String hintMessage(){
        switch(hint){
            case "SAND_CLIENT_UPDATE_REQUIRED":return "Grok needs a newer Grok Wear. Update the app, then try again.";
            case "USER_BLOCKED":return "Grok has blocked this account from Grok Bot. Check your account on your phone.";
            case "USAGE_LIMIT_EXCEEDED":return "This account has reached its Grok Bot usage limit. Try again later.";
            case "SERVICE_RATE_LIMIT_EXCEEDED":return "Grok is busy right now. Try again in a few minutes.";
            case "TEAM_INACTIVE":return "Your Grok team isn’t active. Ask a team admin, or sign in again without the team.";
            case "CLOUD_AGENT_STORAGE_DISABLED":return "Privacy mode on this account blocks Grok Bot. Change it on your phone, then try again.";
            case "TEAM_PRIVACY_MODE_UNCONFIGURED":return "Your team hasn’t set its privacy mode for Grok Bot yet. Ask a team admin.";
            case "TEAM_CLOUD_AGENT_ACCESS_DENIED":return "Your team doesn’t allow Grok Bot for you. Ask a team admin.";
            case "CLOUD_AGENTS_DISABLED":return "Grok Bot is turned off for this account or team.";
            case "CLOUD_AGENT_PLAN_RESTRICTED":
            case "FREE_OWNER_CLOUD_AGENT_DENIED":return "Grok Bot needs a plan on this account. Subscribe on your phone, then try again.";
            case "INVALID_TEAM_ID":
            case "SERVICE_ACCOUNT_TEAM_CONTEXT_MISSING":return "Grok didn’t accept the team chosen at sign-in. Sign in again.";
            case "SAND_BOX_BLOCKED":return "Grok has paused Bots for this account for now. Try again later.";
            default:return "";
        }
    }

    /** ErrorDetails' sandUsageScopeError: its JSON debug form first (what Android reads), then the binary value. */
    static String scopeFrom(JSONArray details){
        if(details==null)return "";
        for(int i=0;i<Math.min(details.length(),8);i++){
            JSONObject detail=details.optJSONObject(i);if(detail==null)continue;
            String type=detail.optString("type","");
            if(!type.isEmpty()&&!type.endsWith("aiserver.v1.ErrorDetails"))continue;
            JSONObject debug=detail.optJSONObject("debug");
            JSONObject inner=debug==null?null:debug.optJSONObject("details");
            JSONObject info=inner==null?null:inner.optJSONObject("additionalInfo");
            if(info!=null&&!info.optString("sandUsageScopeError","").isEmpty())return info.optString("sandUsageScopeError");
            String value=detail.optString("value","");
            if(!value.isEmpty()){byte[] raw=base64(value);if(raw!=null){String found=mapValue(raw,0,raw.length,"sandUsageScopeError",0);if(found!=null)return found;}}
        }
        return "";
    }
    private static byte[] base64(String value){
        String text=value.trim();if(text.length()>16384)return null;
        try{return Base64.getDecoder().decode(pad(text.replace('-','+').replace('_','/')));}catch(IllegalArgumentException bad){return null;}
    }
    private static String pad(String text){int extra=text.length()%4;return extra==0?text:text+"====".substring(extra);}

    /**
     * Finds a protobuf map<string,string> entry {1: key, 2: value} anywhere in a message, a few levels deep.
     * The field numbers of ErrorDetails aren't needed: a map entry has the same shape wherever it sits.
     */
    static String mapValue(byte[] buf,int start,int end,String key,int depth){
        if(depth>6)return null;
        String entryKey=null,entryValue=null;boolean entryShape=true;int fields=0;
        int at=start;
        while(at<end){
            long[] tag=varint(buf,at,end);if(tag==null)return null;at=(int)tag[1];
            int field=(int)(tag[0]>>>3),wire=(int)(tag[0]&7);fields++;
            if(field<=0)return null;
            switch(wire){
                case 0:{long[] v=varint(buf,at,end);if(v==null)return null;at=(int)v[1];entryShape=false;break;}
                case 1:at+=8;entryShape=false;break;
                case 5:at+=4;entryShape=false;break;
                case 2:{
                    long[] len=varint(buf,at,end);if(len==null)return null;at=(int)len[1];
                    if(len[0]<0||len[0]>end-at)return null;int from=at,to=at+(int)len[0];at=to;
                    if(field==1||field==2){String text=utf8(buf,from,to);if(field==1)entryKey=text;else entryValue=text;}
                    else entryShape=false;
                    String nested=mapValue(buf,from,to,key,depth+1);if(nested!=null)return nested;
                    break;
                }
                default:return null;
            }
            if(at>end)return null;
        }
        if(entryShape&&fields<=2&&key.equals(entryKey)&&entryValue!=null)return entryValue;
        return null;
    }
    private static long[] varint(byte[] buf,int at,int end){
        long value=0;int shift=0;
        while(at<end&&shift<64){int b=buf[at++]&0xff;value|=(long)(b&0x7f)<<shift;if((b&0x80)==0)return new long[]{value,at};shift+=7;}
        return null;
    }
    private static String utf8(byte[] buf,int from,int to){
        java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder();
        try{return decoder.decode(java.nio.ByteBuffer.wrap(buf,from,to-from)).toString();}catch(java.nio.charset.CharacterCodingException notText){return null;}
    }
    private static String clean(String value,String allowed){return value!=null&&value.matches(allowed)?value:"";}
}
