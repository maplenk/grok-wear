package si.zya.grokwear;

import org.json.JSONObject;
import java.util.LinkedHashMap;

/**
 * The gateway's live events, as Grok Bot Android 1.17.0 reads them from {gatewayUrl}/events: a roster row
 * whenever a Bot changes (agent-upserted), each chat entry as it is appended (transcript) and, as 1.25.0 reads
 * them, Grok's usage-limit notices (tray; see UsageTrays) and finished work for a voice call (voice-call-nudge;
 * see CallNudges). Framing,
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

    /** One event the watch acts on: a sanitized roster row ("bot"), a reduced chat entry ("entry"), a usage-limit notice change ("tray") or a voice call's finished work ("nudge"). */
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
            String type=GrokCore.text(payload,"type");
            if(channel.equals("transcript")&&(type.equals("appended")||type.equals("updated"))){
                String botId=GrokCore.text(payload,"agentId");JSONObject entry=entry(payload.optJSONObject("entry"));
                // A changed entry matters only as an approval request answered or expired elsewhere, a phone call
                // moving on (FEATURES-SPEC §0.3), a question (from that call, or with option buttons) answered or
                // closed, or an ask for the phone settled.
                if(type.equals("updated")&&(entry==null||!entry.has("approval")&&!entry.has("call")&&!entry.has("question")&&!entry.has("widget")&&!entry.has("phoneTool")))return null;
                return botId.isEmpty()||botId.length()>512||entry==null?null:new Event("entry",botId,entry);}
            if(channel.equals("tray"))return tray(payload);
            // Finished work for a voice call (CallNudges): only the call it names takes it.
            if(channel.equals("voice-call-nudge")){JSONObject nudge=CallNudges.of(payload);return nudge==null?null:new Event("nudge",nudge.getString("agentId"),nudge);}
        }catch(Exception malformed){}
        return null;
    }
    /**
     * A tray event ("tray"), kept only as far as Grok's usage-limit notices go (kind "error", errorKind "usage_limit"):
     * pushed {tray}, dismissed {id} or cleared, as the official app applies them. Another kind of tray pushed under an
     * id reads as that id's dismissal, since an upsert replaces whatever it was. Snapshots and the rest are null.
     */
    static Event tray(JSONObject payload)throws Exception {
        String type=GrokCore.text(payload,"type");
        if(type.equals("cleared"))return new Event("tray","",new JSONObject().put("type","cleared"));
        if(type.equals("dismissed")){String id=GrokCore.text(payload,"id");return key(id)?new Event("tray","",new JSONObject().put("type","dismissed").put("id",id)):null;}
        JSONObject tray=type.equals("pushed")?payload.optJSONObject("tray"):null;if(tray==null)return null;
        String id=GrokCore.text(tray,"id");if(!key(id))return null;
        if(!"error".equals(GrokCore.text(tray,"kind"))||!"usage_limit".equals(GrokCore.text(tray,"errorKind")))
            return new Event("tray","",new JSONObject().put("type","dismissed").put("id",id));
        return usage(id,GrokCore.text(tray,"agentId"),tray.opt("createdAt"),GrokCore.text(tray,"title"),GrokCore.text(tray,"detail"));
    }
    /**
     * A usage-limit notice as a pushed tray event: {type:"pushed", tray:{id, agentId, createdAt (epoch ms, 0 when
     * unknown), title?, detail?}}. Also built from the server's turnFailed (ServerTranscripts). Null without both ids.
     */
    static Event usage(String id,String botId,Object createdAt,String title,String detail)throws Exception {
        if(!key(id)||!key(botId))return null;
        JSONObject tray=new JSONObject().put("id",id).put("agentId",botId).put("createdAt",ServerTranscripts.msOf(createdAt));
        title=prose(title);detail=prose(detail);if(!title.isEmpty())tray.put("title",title);if(!detail.isEmpty())tray.put("detail",detail);
        return new Event("tray",botId,new JSONObject().put("type","pushed").put("tray",tray));
    }
    /** An id fit to keep and send back: 1 to 512 characters, no control characters. */
    static boolean key(String id){return id!=null&&!id.isEmpty()&&id.length()<=512&&!id.matches("(?s).*\\p{Cc}.*");}
    /** Server text as one line of at most 300 characters. */
    static String prose(String text){text=text==null?"":text.replaceAll("[\\s\\p{Cc}]+"," ").trim();return text.length()>300?text.substring(0,300):text;}
    /**
     * A chat entry reduced to who wrote it and, for the Bot's own message, its text. Only what the chat shows
     * counts (the same rules as HistoryCoordinator): never suppressed, Bot-to-Bot, voice-channel or still
     * streaming entries. A Bot writes to you with a text send-message entry, or an assistant message. A Bot's
     * approval request also carries that approval (Approvals.of, without ids an answer would need), and a phone
     * call it places its status.
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
        JSONObject out=new JSONObject().put("reply",reply).put("user",user).put("text",reply?text:"");
        JSONObject approval=hidden||!kind.equals("send-message")?null:Approvals.of(e,"");
        if(approval!=null)out.put("approval",new JSONObject().put("status",approval.getString("status")).put("kind",approval.getString("kind")).put("line",approval.getString("line")));
        JSONObject call=hidden||approval!=null||!kind.equals("send-message")?null:PhoneCalls.of(e);
        if(call!=null)out.put("call",new JSONObject().put("status",call.getString("status")));
        // A question from the Bot's phone call: who asks, what, and when it shows and closes; never the ids an answer needs.
        JSONObject question=hidden||approval!=null||call!=null||!kind.equals("send-message")?null:CallQuestions.of(e,"");
        if(question!=null){JSONObject brief=new JSONObject();
            for(String key:new String[]{"askedBy","prompt","deadlineAtMs","revealAtMs","presentedAtMs","closed","resolution"})if(question.has(key))brief.put(key,question.get(key));
            out.put("question",brief);}
        // A question with option buttons: its prompt and whether it's still open; an ask only the phone answers.
        JSONObject widget=hidden||approval!=null||call!=null||question!=null||!kind.equals("send-message")?null:Widgets.of(e,"");
        if(widget!=null)out.put("widget",new JSONObject().put("prompt",widget.getString("prompt")).put("open","open".equals(Widgets.state(widget,false))));
        if(!hidden&&kind.equals("send-message")&&Widgets.phoneTool(e)!=null)out.put("phoneTool",true);
        return out;
    }
    private static boolean present(JSONObject e,String key){return e.opt(key)!=null&&e.opt(key)!=JSONObject.NULL;}
    /**
     * When the Bot's chat was last read, if its row says it's read (nothing unread, a lastViewedAt): on another device,
     * say. The official app then dismisses that Bot's notifications (dismissViewed); else 0.
     */
    static long viewedAt(JSONObject row){
        if(row==null||!Boolean.FALSE.equals(row.opt("hasUnread"))||row.optLong("unreadCount",0)>0)return 0;
        return Math.max(0,row.optLong("lastViewedAt",0));
    }
    /** A notice posted at postedAt is read, by this row's view: posted no later than the chat was read. */
    static boolean readAfter(JSONObject row,long postedAt){long viewed=viewedAt(row);return viewed>0&&postedAt>0&&postedAt<=viewed;}

    /**
     * The Bots a reply alert is waiting on after you sent them a message. A wait ends at the Bot's next
     * message, or, if the stream missed it, when its unread count goes up, or at Grok's notice that the Bot
     * is out of usage. It lapses ten minutes after the
     * send, or once the Bot has been idle for two minutes without writing back.
     */
    static final class Waits {
        static final long LIMIT_MS=10*60_000L,QUIET_MS=2*60_000L;
        private static final class Wait {
            final String name,message;final long since;long unread=-1,idleSince;
            /** A question from the Bot's phone call that Grok hasn't revealed yet. */
            JSONObject question;
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
        JSONObject entry(String botId,JSONObject entry)throws Exception{return entry(botId,entry,System.currentTimeMillis());}
        /**
         * Returns the alert ({bot, name, text}) when this entry is the Bot's reply, else null. A Bot that asks for your
         * OK first won't go on without it: that request ends the wait too ({…, approval:true}, its one line as text).
         * So does a question from the Bot's phone call, which has a deadline ({…, question:true, title: who asks, its
         * prompt as text}); one Grok hasn't revealed yet at wall (the clock its times use) waits for reveal(). And so
         * does the Bot's own question with option buttons, still open (title "{Bot} is asking").
         */
        JSONObject entry(String botId,JSONObject entry,long wall)throws Exception {
            Wait wait=waits.get(botId);if(wait==null)return null;
            JSONObject approval=entry.optJSONObject("approval");
            if(approval!=null&&"pending".equals(approval.optString("status"))){waits.remove(botId);return alert(botId,wait,approval.optString("line")).put("approval",true);}
            JSONObject question=entry.optJSONObject("question");
            if(question!=null&&"open".equals(CallQuestions.state(question,wall))){
                if(CallQuestions.heldBack(question,wall)){wait.question=question;return null;}
                waits.remove(botId);return asked(botId,wait,question);}
            JSONObject widget=entry.optJSONObject("widget");
            if(widget!=null&&widget.optBoolean("open")){waits.remove(botId);return options(botId,wait,widget);}
            if(!entry.optBoolean("reply"))return null;
            waits.remove(botId);return alert(botId,wait,entry.optString("text"));
        }
        /** A Bot's own question with option buttons, still open: who asks ("{Bot} is asking") and the question, as a question alert. */
        private static JSONObject options(String botId,Wait wait,JSONObject widget)throws Exception {
            String prompt=widget.optString("prompt");return alert(botId,wait,prompt.isEmpty()?"Question":prompt).put("question",true).put("title",wait.name+" is asking");
        }
        /** A held-back question Grok has revealed by wall, still open, for a Bot still waited on: its alert, else null. */
        JSONObject reveal(long wall)throws Exception {
            for(java.util.Map.Entry<String,Wait> w:waits.entrySet()){JSONObject question=w.getValue().question;
                if(question!=null&&!CallQuestions.heldBack(question,wall)&&"open".equals(CallQuestions.state(question,wall))){waits.remove(w.getKey());return asked(w.getKey(),w.getValue(),question);}}
            return null;
        }
        private static JSONObject asked(String botId,Wait wait,JSONObject question)throws Exception {
            return alert(botId,wait,question.optString("prompt")).put("question",true).put("title",CallQuestions.asking(question));
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
        /** The words a usage-limit alert shows, as the official app words its notice. */
        static final String USAGE_TITLE="Out of usage",USAGE_TEXT="This Bot is out of usage until it resets.";
        /** Watch and server clocks differ: a notice stamped up to this long before the send still counts as after it. */
        static final long CLOCK_GRACE_MS=2*60_000L;
        /**
         * Grok's usage-limit notice (a pushed tray) for a waited-on Bot, from after the watch's message: the reply won't
         * come, so the wait ends with that notice ({bot, name, text, usage:true}). now is the wait's clock, wall the
         * wall clock, to place the send against the notice's createdAt. An older notice repeated is not this message's.
         */
        JSONObject usage(JSONObject tray,long now,long wall)throws Exception {
            String id=tray==null?"":tray.optString("agentId");Wait wait=waits.get(id);if(wait==null)return null;
            long created=tray.optLong("createdAt"),sent=wall-(now-wait.since);
            if(created>0&&created<sent-CLOCK_GRACE_MS)return null;
            waits.remove(id);return alert(id,wait,USAGE_TEXT).put("usage",true);
        }
        void expire(long now){waits.values().removeIf(w->now-w.since>LIMIT_MS||(w.idleSince>0&&now-w.idleSince>QUIET_MS));}
        void clear(){waits.clear();}
        private static JSONObject alert(String botId,Wait wait,String text)throws Exception{return new JSONObject().put("bot",botId).put("name",wait.name).put("text",text);}
    }
}
