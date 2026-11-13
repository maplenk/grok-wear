package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grok's own "usage limit" notices for this process, per Bot: the gateway's usage-limit trays, pushed or read with
 * getTrays on connecting, and the server's turnFailed (BotEvents.tray, ServerTranscripts.turnFailed). A Bot out of usage still takes your message, then
 * says nothing, so the chat and reply alerts read this instead. The watch never dismisses or clears a tray: trays
 * are shared with the phone, whose Grok app dismisses them once usage is back. No Android here, so it can be tested.
 */
final class UsageTrays {
    /** The one store EventStream and ServerEvents fill. */
    static final UsageTrays ALL=new UsageTrays();
    /** Far more Bots than are out of usage at once; past this the oldest notice goes first. */
    static final int MAX=64;
    private final LinkedHashMap<String,JSONObject> trays=new LinkedHashMap<>();
    // Tray id to when usage resets (epoch ms), once asked; 0 while asking, or when Grok didn't say.
    private final Map<String,Long> resets=new HashMap<>();
    private String identity="";

    /** Applies one tray event for this account ("account_key/auth_generation"); another account's notices go first. Returns whether anything changed. */
    synchronized boolean apply(String account,BotEvents.Event event){
        if(account==null||account.isEmpty()||event==null||!"tray".equals(event.kind))return false;
        if(!account.equals(identity)){trays.clear();resets.clear();identity=account;}
        switch(event.value.optString("type")){
            case "pushed":{
                JSONObject tray=event.value.optJSONObject("tray");String id=tray==null?"":tray.optString("id");if(id.isEmpty())return false;
                JSONObject before=trays.remove(id);trays.put(id,tray);
                while(trays.size()>MAX){String oldest=trays.keySet().iterator().next();trays.remove(oldest);resets.remove(oldest);}
                return before==null||!before.toString().equals(tray.toString());
            }
            case "dismissed":{String id=event.value.optString("id");resets.remove(id);return trays.remove(id)!=null;}
            case "cleared":{boolean any=!trays.isEmpty();trays.clear();resets.clear();return any;}
            default:return false;
        }
    }
    /** The gateway's notices kept for this account (not the server's turnFailed ones), before asking getTrays. */
    synchronized Set<String> gatewayIds(String account){
        Set<String> ids=new HashSet<>();if(account==null||!account.equals(identity))return ids;
        for(String id:trays.keySet())if(!id.startsWith("server-turn:"))ids.add(id);
        return ids;
    }
    /**
     * Applies the gateway's current trays (a getTrays reply, read when the stream connects) for this account: each
     * usage-limit notice in it is kept as if pushed, and one of held (gatewayIds before asking) no longer listed has
     * gone. A notice pushed while the reply was on its way stays, and so do the server's turnFailed ones. Returns the
     * changes, as tray events, for listeners.
     */
    synchronized List<BotEvents.Event> reconcile(String account,JSONArray listed,Set<String> held){
        List<BotEvents.Event> changes=new ArrayList<>();
        if(account==null||account.isEmpty()||listed==null)return changes;
        if(!account.equals(identity)){trays.clear();resets.clear();identity=account;}
        Set<String> ids=new HashSet<>();
        for(int i=0;i<listed.length();i++){JSONObject tray=listed.optJSONObject(i);if(tray==null)continue;
            try{BotEvents.Event event=BotEvents.tray(new JSONObject().put("type","pushed").put("tray",tray));if(event==null)continue;
                ids.add(GrokCore.text(tray,"id"));if(apply(account,event))changes.add(event);}
            catch(Exception unreadable){}}
        for(String id:held)if(!ids.contains(id))
            try{BotEvents.Event gone=new BotEvents.Event("tray","",new JSONObject().put("type","dismissed").put("id",id));if(apply(account,gone))changes.add(gone);}
            catch(Exception unreadable){}
        return changes;
    }
    /** The Bot's newest usage-limit notice for this account (a copy), or null. */
    synchronized JSONObject latest(String account,String botId){
        if(account==null||!account.equals(identity)||botId==null||botId.isEmpty())return null;
        JSONObject newest=null;
        for(JSONObject tray:trays.values())if(botId.equals(tray.optString("agentId"))&&(newest==null||tray.optLong("createdAt")>=newest.optLong("createdAt")))newest=tray;
        try{return newest==null?null:new JSONObject(newest.toString());}catch(Exception unreadable){return null;}
    }
    /** True once per notice: that caller asks Grok when usage resets. */
    synchronized boolean claimReset(String trayId){if(!trays.containsKey(trayId)||resets.containsKey(trayId))return false;resets.put(trayId,0L);return true;}
    synchronized void reset(String trayId,long ms){if(trays.containsKey(trayId))resets.put(trayId,Math.max(0,ms));}
    /** When usage resets for this notice (epoch ms); 0 when not known (yet). */
    synchronized long resetAt(String trayId){Long ms=resets.get(trayId);return ms==null?0:ms;}
    /** Signing out: the old account's notices go. */
    synchronized void clear(){trays.clear();resets.clear();identity="";}

    /**
     * When usage resets, from a GetSandUsageStatus reply (epoch ms), as the official usage model reads it: only at the
     * limit (usagePercent 100 or more) on a personal allowance (not usesPooledEnterpriseAllowance), and only when
     * nextResetTimestampUtc parses. Else 0.
     */
    static long resetMs(JSONObject status){
        if(status==null||status.optBoolean("usesPooledEnterpriseAllowance"))return 0;
        Object value=status.opt("usagePercent");double percent;
        try{percent=value instanceof Number?((Number)value).doubleValue():value instanceof String?Double.parseDouble((String)value):Double.NaN;}
        catch(NumberFormatException unreadable){return 0;}
        if(!Double.isFinite(percent)||percent<100)return 0;
        String at=GrokCore.text(status,"nextResetTimestampUtc").trim();if(at.isEmpty()||at.length()>64)return 0;
        try{long ms=OffsetDateTime.parse(at).toInstant().toEpochMilli();return ms>0?ms:0;}catch(RuntimeException unreadable){return 0;}
    }
    /**
     * "Resets today at 9:00 AM", "Resets Tue at 9:00 AM" within the week, else "Resets Nov 20 at 9:00 AM", in the
     * watch's zone and with its 12- or 24-hour clock. Empty once that time has passed.
     */
    static String resetLine(long resetMs,long nowMs,ZoneId zone,Locale locale,boolean hours24){
        if(resetMs<=nowMs)return "";
        ZonedDateTime at=Instant.ofEpochMilli(resetMs).atZone(zone);LocalDate today=Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long days=ChronoUnit.DAYS.between(today,at.toLocalDate());
        String day=days==0?"today":at.format(DateTimeFormatter.ofPattern(days<7?"EEE":"MMM d",locale));
        return "Resets "+day+" at "+at.format(DateTimeFormatter.ofPattern(hours24?"H:mm":"h:mm a",locale));
    }
}
