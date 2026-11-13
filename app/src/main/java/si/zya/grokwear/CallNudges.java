package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Finished work reaching your own voice call, as Grok Bot Android 1.25.0 handles it (MORE-FEATURES-SPEC §2): the
 * gateway's voice-call-nudge events, matched to the call on the line and turned into realtime messages. ack, progress
 * and outcome become one spoken async update; overheard notes become a silent function-call pair, only for a call that
 * said it takes them. No Android here, so it can be tested.
 */
final class CallNudges {
    private CallNudges(){}
    static final Set<String> KINDS=Set.of("ack","progress","outcome","overheard");
    /** Longer updates are cut: a call's update is a sentence or two, and the socket's queue is bounded. */
    static final int MAX_UPDATE=4000;
    /** Most distinct updates one call takes; the official dedupe set grows for the call, a watch call is short. */
    static final int MAX_SPOKEN=64;

    /** The call id the watch mints for each call, as the official app does ("call-" and a UUID). */
    static String newCallId(){return "call-"+UUID.randomUUID();}
    /** A seeded nudge's id, as the official app builds it (newId("nudge") after "seeded-nudge-"). */
    static String seedId(){return "seeded-nudge-nudge-"+UUID.randomUUID();}

    /**
     * A voice-call-nudge payload as the official inboundNudge checks it: {agentId, callId, update, kind}, with string ids
     * and update, a known kind and an update that isn't blank. Null for anything else.
     */
    static JSONObject of(JSONObject payload){
        try{
            if(payload==null)return null;
            Object agent=payload.opt("agentId"),call=payload.opt("callId"),update=payload.opt("update");String kind=GrokCore.text(payload,"kind");
            if(!(agent instanceof String)||!(call instanceof String)||!(update instanceof String)||!KINDS.contains(kind)||((String)update).trim().isEmpty())return null;
            if(!BotEvents.key((String)agent)||!BotEvents.key((String)call))return null;
            String text=(String)update;if(text.length()>MAX_UPDATE)text=text.substring(0,MAX_UPDATE);
            return new JSONObject().put("agentId",agent).put("callId",call).put("update",text).put("kind",kind);
        }catch(Exception unreadable){return null;}
    }
    /**
     * Why a nudge doesn't go into this call, or null when it does: other_call (another Bot or call), off_line (the
     * caller isn't on the line), overheard_off (the call didn't take overheard notes), duplicate (the same update
     * already went in), full.
     */
    static String refusal(JSONObject nudge,String botId,String callId,boolean onTheLine,boolean acceptsOverheard,Set<String> spoken){
        if(!onTheLine)return "off_line";
        if(!nudge.optString("agentId").equals(botId)||!nudge.optString("callId").equals(callId))return "other_call";
        if(!spoken(nudge)&&!acceptsOverheard)return "overheard_off";
        if(spoken.contains(nudge.optString("update")))return "duplicate";
        if(spoken.size()>=MAX_SPOKEN)return "full";
        return null;
    }
    /** ack, progress and outcome are said aloud (and owe the caller news); overheard notes are only filed. */
    static boolean spoken(JSONObject nudge){return !"overheard".equals(nudge.optString("kind"));}
    /**
     * The realtime messages for a nudge: one async_update.add (topic work_landed) for a spoken one, which the server
     * speaks; for an overheard note, a work_overheard function call and its output, with nothing asking for a reply.
     */
    static JSONArray frames(JSONObject nudge,String seedId,long atMs)throws Exception {
        String update=nudge.optString("update");JSONArray frames=new JSONArray();
        if(spoken(nudge))return frames.put(new JSONObject().put("type","async_update.add").put("event_id",seedId).put("async_update",new JSONObject()
            .put("topic","work_landed").put("payload",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("type","text").put("content",update)).put("timestampMs",atMs)))));
        String at=Instant.ofEpochMilli(atMs).truncatedTo(ChronoUnit.SECONDS).toString();
        // In the official key order: status, steps, at.
        String output="{\"status\":\"in progress\",\"steps\":"+new JSONArray().put(update)+",\"at\":"+JSONObject.quote(at)+"}";
        frames.put(new JSONObject().put("type","conversation.item.create").put("item",new JSONObject().put("type","function_call").put("name","work_overheard").put("call_id",seedId).put("arguments","{}")));
        return frames.put(new JSONObject().put("type","conversation.item.create").put("item",new JSONObject().put("type","function_call_output").put("call_id",seedId).put("output",output)));
    }
}
