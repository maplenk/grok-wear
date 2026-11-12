package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Grok's server-side chat state for Bots run on its server (harness "temporal"), as Grok Bot Android 1.25.0 reads
 * it: transcript rows from ListGrokBotTranscriptEntries, the Connect framing of WatchGrokBotTranscripts, and the
 * stream's per-Bot state (unread, preview, working). No Android here, so it can be tested.
 */
final class ServerTranscripts {
    private ServerTranscripts(){}
    /** Larger stream frames end the stream. The official client allows 16 MiB; a watch needs far less. */
    static final int MAX_FRAME=4*1024*1024;
    /** A row's entry JSON larger than this is skipped (the official client fetches those separately). */
    static final int MAX_BODY=1024*1024;
    /** Like the official client: a running Bot with no update for this long counts as idle. */
    static final long DEFAULT_STALE_MS=90_000L;

    /** A seq or updatedSeq: an int64 sent as a number or a decimal string; -1 when unreadable. */
    static long seqOf(Object value){
        if(value instanceof Number){double n=((Number)value).doubleValue();return Double.isFinite(n)&&n>=0&&n==Math.floor(n)&&n<9.0e15?((Number)value).longValue():-1;}
        if(value instanceof String&&((String)value).matches("\\d{1,18}"))return Long.parseLong((String)value);
        return -1;
    }
    /** Milliseconds sent as a number or a numeric string; 0 when absent or unreadable. */
    static long msOf(Object value){long ms=seqOf(value);return ms>0?ms:0;}

    /**
     * One transcript row's entry: its base64 body is the same entry JSON the gateway sends, with its seq. Null for
     * a row without an inline body (a large or omitted one) or one that doesn't decode to an entry.
     */
    static JSONObject entryOf(JSONObject row){
        if(row==null)return null;
        long seq=seqOf(row.opt("seq"));String body=GrokCore.text(row,"body");
        if(seq<0||body.isEmpty()||body.length()>MAX_BODY*4/3+4)return null;
        try{
            JSONObject entry=new JSONObject(new String(Base64.getMimeDecoder().decode(body),StandardCharsets.UTF_8));
            if(GrokCore.text(entry,"id").isEmpty()||GrokCore.text(entry,"kind").isEmpty())return null;
            if(seqOf(entry.opt("seq"))<0)entry.put("seq",seq);
            return entry;
        }catch(Exception unreadable){return null;}
    }

    /**
     * A ListGrokBotTranscriptEntries reply (newest first, asked for limit+1 rows) as the gateway's transcript page:
     * {entries: oldest first, nextBeforeSeq when there are older rows}.
     */
    static JSONObject page(JSONObject reply,int limit)throws Exception {
        JSONArray rows=reply.optJSONArray("entries");if(rows==null)rows=new JSONArray();
        boolean more=rows.length()>limit;
        TreeMap<Long,JSONObject> kept=new TreeMap<>();long oldest=-1;
        for(int i=0;i<Math.min(limit,rows.length());i++){
            JSONObject row=rows.optJSONObject(i);long seq=row==null?-1:seqOf(row.opt("seq"));if(seq<0)continue;
            oldest=oldest<0?seq:Math.min(oldest,seq);
            JSONObject entry=entryOf(row);if(entry!=null)kept.put(seq,entry);
        }
        JSONObject page=new JSONObject().put("entries",new JSONArray(new ArrayList<>(kept.values())));
        if(more&&oldest>=0)page.put("nextBeforeSeq",oldest);
        return page;
    }

    /** The highest updatedSeq and seq among a reply's rows, and its generation (absent means 0): {generation, updatedSeq, seq}. */
    static long[] marks(JSONObject reply){
        long generation=Math.max(0,seqOf(reply.opt("generation"))),updated=0,seq=0;
        JSONArray rows=reply.optJSONArray("entries");
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            updated=Math.max(updated,seqOf(row.opt("updatedSeq")));seq=Math.max(seq,seqOf(row.opt("seq")));}
        return new long[]{generation,updated,seq};
    }

    /**
     * The chats the server stream follows (its cursors), like the official client's adopted scopes: a Bot's main chat
     * once the watch has read its newest page, at most MAX of them, most recently read first.
     */
    static final class Scopes {
        static final int MAX=8;
        private final java.util.LinkedHashMap<String,long[]> scopes=new java.util.LinkedHashMap<>(16,0.75f,true);
        /** Follows this chat from what its newest page showed. True when the stream must reconnect to include it. */
        synchronized boolean adopt(String agentId,long[] marks){
            if(agentId==null||agentId.isEmpty()||agentId.length()>512)return false;
            long[] known=scopes.get(agentId);
            if(known!=null&&known[0]==marks[0]){known[1]=Math.max(known[1],marks[1]);known[2]=Math.max(known[2],marks[2]);return false;}
            scopes.put(agentId,new long[]{marks[0],marks[1],marks[2]});
            while(scopes.size()>MAX)scopes.remove(scopes.keySet().iterator().next());
            return true;
        }
        synchronized boolean has(String agentId){return scopes.containsKey(agentId);}
        synchronized void forget(String agentId){scopes.remove(agentId);}
        synchronized void clear(){scopes.clear();}
        /** The stream request's cursors: {agentId, generation, afterUpdatedSeq} (a decimal string), main chat only. */
        synchronized JSONArray cursors()throws Exception {
            JSONArray out=new JSONArray();List<String> ids=new ArrayList<>(scopes.keySet());
            for(int i=ids.size()-1;i>=0;i--){long[] s=scopes.get(ids.get(i));
                out.put(new JSONObject().put("agentId",ids.get(i)).put("generation",s[0]).put("afterUpdatedSeq",Long.toString(s[1])));}
            return out;
        }
        /**
         * Applies one streamed row of a followed chat: "appended" for an entry past the newest one shown, "updated" for
         * a change to one already there, or null for a chat not followed, an older generation or a row already seen.
         */
        synchronized String row(String agentId,long generation,JSONObject row){
            long[] s=scopes.get(agentId);if(s==null||generation<s[0])return null;
            long seq=seqOf(row.opt("seq")),updated=seqOf(row.opt("updatedSeq"));if(seq<0||updated<0)return null;
            if(generation>s[0]){s[0]=generation;s[1]=0;s[2]=0;}
            if(updated<=s[1]&&seq<=s[2])return null;
            s[1]=Math.max(s[1],updated);
            if(seq>s[2]){s[2]=seq;return "appended";}
            return "updated";
        }
    }

    /** The one request message of a Connect server stream: a 5-byte envelope (flags 0, big-endian length), then JSON. */
    static byte[] envelope(JSONObject message){
        byte[] json=message.toString().getBytes(StandardCharsets.UTF_8);byte[] out=new byte[json.length+5];
        out[1]=(byte)(json.length>>>24);out[2]=(byte)(json.length>>>16);out[3]=(byte)(json.length>>>8);out[4]=(byte)json.length;
        System.arraycopy(json,0,out,5,json.length);return out;
    }

    /** Reads a Connect server stream's messages. */
    static final class Frames {
        private final DataInputStream in;
        Frames(InputStream in){this.in=new DataInputStream(in);}
        /**
         * The next message, or null at a clean end of the stream. An error trailer, a compressed or oversized frame,
         * or a body that ends without the end-of-stream frame throws.
         */
        JSONObject next()throws IOException,GrokCore.Failure {
            int flags;
            try{flags=in.readUnsignedByte();}catch(EOFException ended){throw new IOException("stream ended without end-of-stream");}
            int length=in.readInt();
            if(length<0||length>MAX_FRAME)throw new IOException("stream frame too large");
            if((flags&1)!=0)throw new IOException("compressed stream frame");
            byte[] payload=new byte[length];in.readFully(payload);
            String text=new String(payload,StandardCharsets.UTF_8).trim();
            if((flags&2)!=0){
                JSONObject trailer;try{trailer=text.isEmpty()?new JSONObject():new JSONObject(text);}catch(Exception unreadable){throw new IOException("unreadable end-of-stream");}
                JSONObject error=trailer.optJSONObject("error");
                if(error==null)return null;
                String code=GrokCore.text(error,"code");
                throw new GrokCore.Failure("Live updates from Grok stopped ("+(code.matches("[a-z_]{1,40}")?code:"error")+").",false,code.matches("[a-z_]{1,40}")?code:"");
            }
            try{return new JSONObject(text);}catch(Exception unreadable){throw new IOException("unreadable stream frame");}
        }
    }

    /**
     * The stream's agentState for each Bot, kept as the official ServerAgentStateOverlays does: client state (unread,
     * last activity, preview) per Bot and live state (running, composing) per Bot and session.
     */
    static final class States {
        private final Map<String,JSONObject> client=new HashMap<>();
        private final Map<String,Map<String,JSONObject>> live=new HashMap<>();
        /** Applies one agentState frame; returns the Bots whose state it touched (all known ones after a snapshot). */
        synchronized Set<String> apply(JSONObject state){
            Set<String> touched=new HashSet<>();if(state==null)return touched;
            if(state.optBoolean("snapshot")){touched.addAll(client.keySet());touched.addAll(live.keySet());client.clear();live.clear();}
            JSONArray clients=state.optJSONArray("client");
            if(clients!=null)for(int i=0;i<clients.length();i++){JSONObject item=clients.optJSONObject(i);String id=GrokCore.text(item,"agentId");
                if(id.isEmpty()||id.length()>512)continue;client.put(id,item);touched.add(id);}
            JSONArray lives=state.optJSONArray("live");
            if(lives!=null)for(int i=0;i<lives.length();i++){JSONObject item=lives.optJSONObject(i);String id=GrokCore.text(item,"agentId"),session=GrokCore.text(item,"sessionId");
                // Group chat seats aren't the Bot's own state.
                if(id.isEmpty()||id.length()>512||session.startsWith("group:"))continue;
                live.computeIfAbsent(id,k->new TreeMap<>()).put(session,item);touched.add(id);}
            return touched;
        }
        synchronized boolean knows(String agentId){return client.containsKey(agentId)||live.containsKey(agentId);}
        synchronized void clear(){client.clear();live.clear();}
        /**
         * The Bot's row with this state over it: the same fields as a gateway row (hasUnread, unreadCount,
         * lastActivityAt, lastViewedAt, lastMessagePreview, isRunning, isRunningTurn, isComposingMessage).
         * Unknown Bots come back unchanged.
         */
        synchronized JSONObject over(JSONObject base,long now)throws Exception {
            JSONObject row=new JSONObject(base.toString());String id=GrokCore.text(row,"id");
            JSONObject c=client.get(id);
            if(c!=null){
                long unread=Math.max(0,c.optLong("unreadCount",0)),threads=Math.max(0,c.optLong("unreadThreadCount",0));
                row.put("unreadCount",unread).put("hasUnread",unread>0||threads>0);
                long viewed=msOf(c.opt("lastViewedAtMs")),activity=msOf(c.opt("lastActivityAtMs"));
                if(viewed>0)row.put("lastViewedAt",viewed);else row.remove("lastViewedAt");
                if(activity>0)row.put("lastActivityAt",activity);else row.remove("lastActivityAt");
                String preview=previewOf(c);
                if(preview.isEmpty())row.remove("lastMessagePreview");else row.put("lastMessagePreview",preview);
            }
            Map<String,JSONObject> sessions=live.get(id);
            if(sessions!=null){
                // Like mergeSessions: the first session running a turn, else the first running, else idle (main first).
                JSONObject chosen=null;boolean anyRunning=false;
                for(JSONObject s:sessions.values()){if(!running(s,now))continue;anyRunning=true;if(s.optBoolean("isRunning")){chosen=s;break;}if(chosen==null)chosen=s;}
                boolean turn=chosen!=null&&chosen.optBoolean("isRunning");
                row.put("isRunning",anyRunning).put("isRunningTurn",turn).put("isComposingMessage",turn&&chosen.optBoolean("isComposingMessage"));
            }
            return row;
        }
        /** Running, unless the server hasn't updated it for its stale time (90 s by default). */
        private static boolean running(JSONObject s,long now){
            if(!s.optBoolean("isRunning")&&!s.optBoolean("hasRunningSubagents"))return false;
            long updated=msOf(s.opt("updatedAtMs")),stale=msOf(s.opt("staleAfterMs"));if(stale<=0)stale=DEFAULT_STALE_MS;
            return now<=Math.min(updated>0?updated:now,now)+stale;
        }
    }

    /**
     * A turnFailed frame ({agentId, turnId, code, summary?, failedAtMs?}) as the usage-limit tray the official app
     * makes of it (ServerTurnFailures): id "server-turn:"+turnId, createdAt from failedAtMs, the summary as detail.
     * Only GROK_BOT_TURN_FAILURE_CODE_USAGE_LIMIT (or its number, 3) counts; other failures are null.
     */
    static BotEvents.Event turnFailed(JSONObject failed){
        if(failed==null)return null;
        Object code=failed.opt("code");String name=code instanceof String?((String)code).replaceFirst("^GROK_BOT_TURN_FAILURE_CODE_",""):"";
        boolean usage=name.equals("USAGE_LIMIT")||name.equals("3")||(code instanceof Number&&((Number)code).doubleValue()==3);
        String turn=GrokCore.text(failed,"turnId");if(!usage||turn.isEmpty())return null;
        try{return BotEvents.usage("server-turn:"+turn,GrokCore.text(failed,"agentId"),failed.opt("failedAtMs"),"",GrokCore.text(failed,"summary"));}
        catch(Exception unreadable){return null;}
    }

    /** The preview line for a Bot's last entry: lastEntryPreview's text, else lastMessagePreview, else "". */
    static String previewOf(JSONObject c){
        JSONObject p=c.optJSONObject("lastEntryPreview");String text="";
        if(p!=null){
            if(!GrokCore.text(p,"text").isEmpty())text=GrokCore.text(p,"text");
            else if(p.optJSONObject("sentAgentMessage")!=null){JSONObject m=p.optJSONObject("sentAgentMessage");text="Messaged "+GrokCore.text(m,"peerName")+(GrokCore.text(m,"text").isEmpty()?"":": "+GrokCore.text(m,"text"));}
            else if(p.optJSONObject("receivedAgentMessage")!=null){JSONObject m=p.optJSONObject("receivedAgentMessage");text="Message from "+GrokCore.text(m,"peerName")+(GrokCore.text(m,"text").isEmpty()?"":": "+GrokCore.text(m,"text"));}
            else if(p.optJSONObject("attachments")!=null){long n=p.optJSONObject("attachments").optLong("count",1);text=n>1?n+" attachments":"Attachment";}
            else if(p.optJSONObject("link")!=null)text=GrokCore.text(p.optJSONObject("link"),"url");
            else if(p.optJSONObject("widgetAnswered")!=null){JSONObject w=p.optJSONObject("widgetAnswered");text=GrokCore.text(w,"prompt")+" — "+GrokCore.text(w,"answer");}
            else if(p.optJSONObject("widgetOptions")!=null){JSONObject w=p.optJSONObject("widgetOptions");JSONArray o=w.optJSONArray("options");List<String> opts=new ArrayList<>();
                if(o!=null)for(int i=0;i<Math.min(4,o.length());i++)if(o.opt(i) instanceof String)opts.add(o.optString(i));
                text=GrokCore.text(w,"prompt")+(opts.isEmpty()?"":" — "+String.join(" / ",opts));}
            else if(p.optJSONObject("widgetPrompt")!=null)text=GrokCore.text(p.optJSONObject("widgetPrompt"),"prompt");
        }
        if(text.trim().isEmpty())text=GrokCore.text(c,"lastMessagePreview");
        text=text.replaceAll("[\\s\\p{Cc}]+"," ").trim();
        return text.length()>4096?text.substring(0,4096):text;
    }
}
