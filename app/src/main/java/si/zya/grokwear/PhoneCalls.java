package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * The Bot's outbound phone calls, as Grok Bot Android 1.25.0 shows them (FEATURES-SPEC §3): a send-message chat entry
 * whose message is a phone-call, updated in place as the call goes from calling to talking to ended. Its status line
 * in the official words, when End call and Take over apply, and their request ({outboundCallId}) and reply. No
 * Android here, so it can be tested.
 */
final class PhoneCalls {
    private PhoneCalls(){}
    /** Most transcript rows a call's handed-off check reads. */
    static final int MAX_ROWS=500;

    /**
     * The call in a transcript entry, reduced to what the card shows: {entryId, callId, label, status, connectedAtMs,
     * transferAvailable, transferState, transferUser, handedOff, transferTarget?, outcome, endReason, durationMs}.
     * label is the callee's name, else the number as Grok formats it (toDisplay), as the official card shows it.
     * Null for any other entry, or one without the call id every request needs.
     */
    static JSONObject of(JSONObject entry){
        try{
            if(entry==null||!"send-message".equals(GrokCore.text(entry,"kind")))return null;
            JSONObject message=entry.optJSONObject("message");if(message==null||!"phone-call".equals(GrokCore.text(message,"type")))return null;
            JSONObject call=message.optJSONObject("call");if(call==null)return null;
            String entryId=GrokCore.text(entry,"id"),callId=GrokCore.text(call,"callId");
            if(!BotEvents.key(entryId)||!BotEvents.key(callId))return null;
            String label=BotEvents.prose(GrokCore.text(call,"name"));if(label.isEmpty())label=BotEvents.prose(GrokCore.text(call,"toDisplay"));
            JSONObject transfer=call.optJSONObject("transfer");
            boolean handedOff=false;JSONArray rows=call.optJSONArray("transcript");
            if(rows!=null)for(int i=0;i<Math.min(rows.length(),MAX_ROWS)&&!handedOff;i++){JSONObject row=rows.optJSONObject(i);
                handedOff=row!=null&&"transfer".equals(GrokCore.text(row,"action"))&&"handed_off".equals(GrokCore.text(row,"result"));}
            JSONObject out=new JSONObject().put("entryId",entryId).put("callId",callId).put("label",label)
                .put("status",word(GrokCore.text(call,"status"))).put("connectedAtMs",ServerTranscripts.msOf(call.opt("connectedAtMs")))
                .put("transferAvailable",Boolean.TRUE.equals(call.opt("transferAvailable")))
                .put("transferState",word(GrokCore.text(transfer,"state"))).put("transferUser","user".equals(GrokCore.text(transfer,"initiator")))
                .put("handedOff",handedOff).put("outcome",word(GrokCore.text(call,"outcome"))).put("endReason",word(GrokCore.text(call,"endReason")))
                .put("durationMs",ServerTranscripts.msOf(call.opt("durationMs")));
            String target=BotEvents.prose(GrokCore.text(call,"transferTargetName"));if(!target.isEmpty())out.put("transferTarget",target);
            return out;
        }catch(Exception unreadable){return null;}
    }
    private static String word(String value){return value.matches("[a-z_]{1,40}")?value:"";}
    static boolean ended(JSONObject call){return "ended".equals(call.optString("status"));}
    /** The card's line about who it calls: "{bot} is calling {label}" while it lasts, "Phone call to {label}" after. */
    static String headline(JSONObject call,String botName){
        String to=call.optString("label");if(to.isEmpty())to="someone";
        if(ended(call))return "Phone call to "+to;
        return (botName==null||botName.isEmpty()?"Your Bot":botName)+" is calling "+to;
    }
    /**
     * The status line in the official words (PhoneCallCards.summary). pending is the watch's own request still
     * waiting for the call to change: "hangup", "transfer" or "". Null while talking: the line is then the timer.
     */
    static String status(JSONObject call,String pending){
        if(ended(call)){
            switch(call.optString("outcome")){case "no_answer":return "No answer";case "failed":return "Didn't go through";default:break;}
            if("handed_off".equals(call.optString("endReason")))return transferred(call);
            long ms=call.optLong("durationMs");return ms>0?clock(ms)+" · Ended":"Ended";
        }
        if("hangup".equals(pending))return "Ending call…";
        if("in_progress".equals(call.optString("status"))&&call.optBoolean("handedOff"))return transferred(call);
        String transfer=call.optString("transferState");
        if((transfer.equals("ringing")&&call.optBoolean("transferUser"))||"transfer".equals(pending)||transfer.equals("answered"))return "Connecting you…";
        if("dialing".equals(call.optString("status"))||call.optLong("connectedAtMs")<=0)return "Calling…";
        return null;
    }
    private static String transferred(JSONObject call){String to=call.optString("transferTarget");return "Transferred to "+(to.isEmpty()?"you":to);}
    /** Time since the call connected, as the card's live timer shows it. */
    static String timer(JSONObject call,long nowMs){return clock(Math.max(0,nowMs-call.optLong("connectedAtMs")));}
    /** m:ss, or h:mm:ss from an hour. */
    static String clock(long ms){
        long s=Math.max(0,ms)/1000;
        return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);
    }
    /** End call: any call not ended yet, on a Bot of your own. */
    static boolean canEnd(JSONObject call,boolean own){return own&&!ended(call);}
    /** Take over: only while talking, when Grok offers it, and no transfer is under way or done. */
    static boolean canTakeOver(JSONObject call,boolean own){
        String transfer=call.optString("transferState");
        return own&&"in_progress".equals(call.optString("status"))&&call.optBoolean("transferAvailable")
            &&!transfer.equals("ringing")&&!transfer.equals("answered")&&!call.optBoolean("handedOff");
    }
    /** What the card shows and does, to notice when Grok's copy of the call has moved on from a request. */
    static String state(JSONObject call){return call.optString("status")+"|"+call.optString("transferState")+"|"+call.optBoolean("handedOff");}
    /** HangUpGrokBotOutboundCall and TransferGrokBotOutboundCall both take exactly this. */
    static JSONObject body(JSONObject call)throws Exception {
        String id=call.optString("callId");if(!BotEvents.key(id))throw new GrokCore.Failure("This call can't be changed from the watch.",true);
        return new JSONObject().put("outboundCallId",id);
    }
    /**
     * Null when Grok took the request ({accepted:true}, no refusal); else what to show: the refusal's own words, or
     * the official words for the action.
     */
    static String refused(JSONObject reply,boolean takeOver){
        JSONObject refusal=reply==null?null:reply.optJSONObject("refusal");
        if(reply!=null&&Boolean.TRUE.equals(reply.opt("accepted"))&&refusal==null)return null;
        String said=BotEvents.prose(GrokCore.text(refusal,"message"));
        return said.isEmpty()?failed(takeOver):said;
    }
    /** The official words when a request fails. */
    static String failed(boolean takeOver){return takeOver?"Couldn't take over the call":"Couldn't end the call";}
}
