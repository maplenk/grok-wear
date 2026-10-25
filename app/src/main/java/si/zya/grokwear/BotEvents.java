package si.zya.grokwear;

import org.json.JSONObject;
import java.util.LinkedHashMap;

/**
 * The gateway's live events, as Grok Bot Android 1.17.0 reads them from {gatewayUrl}/events: a roster row
 * whenever a Bot changes (agent-upserted) and each chat entry as it is appended (transcript). Framing,
 * parsing and the reply-alert rules live here without Android, so they can be tested; EventStream connects.
 */
final class BotEvents {
    private BotEvents(){}
    /** Frames larger than this are dropped; roster rows and messages are far smaller. */
    static final int MAX_FRAME=2*1024*1024;

    /** Server-sent events framing: data lines up to a blank line. Keep-alive comments and other fields are ignored. */
    static final class Frames {
        private final StringBuilder data=new StringBuilder();private boolean any,oversized;
        /** Feeds one line; returns a finished frame's data, or null. */
        String line(String line){
            if(line.isEmpty()){String out=any&&!oversized?data.toString():null;data.setLength(0);any=false;oversized=false;return out;}
            if(!line.startsWith("data:")||oversized)return null;
            String value=line.substring(5);if(value.startsWith(" "))value=value.substring(1);
            if(data.length()+value.length()+1>MAX_FRAME){oversized=true;data.setLength(0);return null;}
            if(any)data.append('\n');data.append(value);any=true;return null;
        }
    }

    /** One event the watch acts on: a sanitized roster row ("bot") or a reduced chat entry ("entry"). */
    static final class Event {
        final String kind,botId;final JSONObject value;
        Event(String kind,String botId,JSONObject value){this.kind=kind;this.botId=botId;this.value=value;}
    }
    /** Reads one frame; anything else (other channels, malformed data) is null. */
    static Event parse(String data){
        try{JSONObject frame=new JSONObject(data),payload=frame.optJSONObject("payload");if(payload==null)return null;
            String channel=GrokCore.text(frame,"channel");
            if(channel.equals("agent-upserted")){
                // The same reduction as listAgents rows. Live rows come without photos; callers keep the saved one.
                JSONObject row=GrokCore.rosterRow(payload.optJSONObject("agent"),0);
                return row==null?null:new Event("bot",row.getString("id"),row);}
            if(channel.equals("transcript")&&"appended".equals(GrokCore.text(payload,"type"))){
                String botId=GrokCore.text(payload,"agentId");JSONObject entry=entry(payload.optJSONObject("entry"));
                return botId.isEmpty()||botId.length()>512||entry==null?null:new Event("entry",botId,entry);}
        }catch(Exception malformed){}
        return null;
    }
    /**
     * A chat entry reduced to who wrote it and, for the Bot's own message, its text. Only what the chat shows
     * counts (the same rules as HistoryCoordinator): never suppressed, Bot-to-Bot, voice-channel or still
     * streaming entries. A Bot writes to you with a text send-message entry, or an assistant message.
     */
    static JSONObject entry(JSONObject e)throws Exception {
        if(e==null)return null;
        String kind=GrokCore.text(e,"kind"),role=GrokCore.text(e,"role"),text="";
        boolean hidden=present(e,"suppressed")||present(e,"fromAgent")||present(e,"toAgent")||!GrokCore.text(e,"channel").isEmpty()
            ||e.optBoolean("isStreaming")||e.optBoolean("streaming");
        boolean reply=false,user=false;
        if(!hidden&&kind.equals("message")){user=role.equals("user");if(role.equals("assistant")){reply=true;text=GrokCore.entryText(e);}}
        else if(!hidden&&kind.equals("send-message")){JSONObject message=e.optJSONObject("message");
            if(message!=null&&"text".equals(message.optString("type"))&&message.optString("channel").isEmpty()){reply=true;text=GrokCore.text(message,"content");}}
        // Shown as the chat shows it: **bold** and other markdown marks never reach a notification.
        text=MessageText.plain(text).replaceAll("[\\s\\p{Cc}]+"," ").trim();if(text.length()>4096)text=text.substring(0,4096);
        return new JSONObject().put("reply",reply).put("user",user).put("text",reply?text:"");
    }
    private static boolean present(JSONObject e,String key){return e.opt(key)!=null&&e.opt(key)!=JSONObject.NULL;}

    /**
     * The Bots a reply alert is waiting on after you sent them a message. A wait ends at the Bot's next
     * message, or, if the stream missed it, when its unread count goes up. It lapses ten minutes after the
     * send, or once the Bot has been idle for two minutes without writing back.
     */
    static final class Waits {
        static final long LIMIT_MS=10*60_000L,QUIET_MS=2*60_000L;
        private static final class Wait {
            final String name,message;final long since;long unread=-1,idleSince;
            Wait(String name,String message,long since){this.name=name;this.message=message;this.since=since;}
        }
        private final LinkedHashMap<String,Wait> waits=new LinkedHashMap<>();
        /** message is the watch message's id, for the chat check after a gap. */
        void start(String botId,String name,String message,long now){Wait previous=waits.get(botId),next=new Wait(name,message,now);if(previous!=null)next.unread=previous.unread;waits.put(botId,next);}
        /** Bot id to watch message id, for the chat check. */
        java.util.Map<String,String> messages(){java.util.Map<String,String> out=new LinkedHashMap<>();for(java.util.Map.Entry<String,Wait> w:waits.entrySet())out.put(w.getKey(),w.getValue().message);return out;}
        /** The chat shows a reply after this watch message: ends that wait if it's still the latest one. */
        JSONObject found(String botId,String message,JSONObject entry)throws Exception {
            Wait wait=waits.get(botId);if(wait==null||!wait.message.equals(message)||!entry.optBoolean("reply"))return null;
            waits.remove(botId);return alert(botId,wait,entry.optString("text"));
        }
        boolean isEmpty(){return waits.isEmpty();}
        boolean waiting(String botId){return waits.containsKey(botId);}
        String firstName(){return waits.isEmpty()?"":waits.values().iterator().next().name;}
        int size(){return waits.size();}
        /** Returns the alert ({bot, name, text}) when this entry is the Bot's reply, else null. */
        JSONObject entry(String botId,JSONObject entry)throws Exception {
            Wait wait=waits.get(botId);if(wait==null||!entry.optBoolean("reply"))return null;
            waits.remove(botId);return alert(botId,wait,entry.optString("text"));
        }
        /** Tracks the Bot's working state and unread count; returns the alert when the count went up. */
        JSONObject bot(JSONObject row,long now)throws Exception {
            String id=row.optString("id");Wait wait=waits.get(id);if(wait==null)return null;
            if(row.optBoolean("isRunning")||row.optBoolean("isRunningTurn")||row.optBoolean("isComposingMessage"))wait.idleSince=0;
            else if(wait.idleSince==0)wait.idleSince=now;
            if(!row.has("unreadCount"))return null;long unread=row.optLong("unreadCount");
            if(wait.unread>=0&&unread>wait.unread){waits.remove(id);return alert(id,wait,row.optString("lastMessagePreview"));}
            wait.unread=unread;return null; // The first count seen is the baseline; reading elsewhere lowers it.
        }
        void expire(long now){waits.values().removeIf(w->now-w.since>LIMIT_MS||(w.idleSince>0&&now-w.idleSince>QUIET_MS));}
        void clear(){waits.clear();}
        private static JSONObject alert(String botId,Wait wait,String text)throws Exception{return new JSONObject().put("bot",botId).put("name",wait.name).put("text",text);}
    }
}
