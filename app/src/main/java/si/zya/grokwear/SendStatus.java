package si.zya.grokwear;

import org.json.JSONObject;
import java.util.LinkedHashMap;

/**
 * Settling a message whose send through Grok's server (SendGrokBotUserMessage) went unanswered, as Grok Bot Android
 * 1.25.0 does: ask GetGrokBotSendStatus every 2 s, each ask with a 5 s deadline, until Grok says accepted, rejected
 * or not found; give up 60 s after the send failed, or after 3 asks in a row that didn't reach Grok. Unlike the
 * official app, a message Grok never got is not sent again on its own: the user can send it again. No Android here,
 * so it can be tested.
 */
final class SendStatus {
    /** The checks under way in this process, by the message's nonce (its messageId). */
    static final SendStatus ALL=new SendStatus();
    static final long POLL_MS=2_000L,DEADLINE_MS=5_000L,GIVE_UP_MS=60_000L;
    static final int STRIKES=3;
    /** Far more than one watch has unanswered at once; past this the oldest check goes first. */
    static final int MAX=16;
    /** What an ask, or the check, came to. ASK: ask now. UNREACHED: an ask that didn't reach Grok. */
    static final String SENT="sent",REJECTED="rejected",NOT_FOUND="not_found",WAITING="waiting",UNREACHED="unreached",GIVE_UP="give_up",ASK="ask";

    /** Grok's answer to one ask: SENT, REJECTED, NOT_FOUND, or WAITING for any other status. */
    static String answer(JSONObject reply){
        switch(GrokCore.text(reply,"status")){
            case "GROK_BOT_SEND_STATUS_ACCEPTED":return SENT;
            case "GROK_BOT_SEND_STATUS_REJECTED":return REJECTED;
            case "GROK_BOT_SEND_STATUS_NOT_FOUND":return NOT_FOUND;
            default:return WAITING;
        }
    }

    /** One unanswered send: the ask's body ({agentId, messageId, sessionId?, taskId?}) and when the send failed. */
    static final class Check {
        final JSONObject request;final long failedAt;
        private long askedAt;private int strikes;private boolean asking,done;
        Check(JSONObject request,long failedAt){this.request=request;this.failedAt=failedAt;this.askedAt=failedAt;}
        /** ASK when it's time for the next ask (the caller asks, then reports with answered), WAITING before, GIVE_UP once over. */
        synchronized String next(long now){
            if(done)return GIVE_UP;
            if(now-failedAt>=GIVE_UP_MS){done=true;return GIVE_UP;}
            if(asking||now-askedAt<POLL_MS)return WAITING;
            asking=true;return ASK;
        }
        /** An ask's result (SENT, REJECTED, NOT_FOUND, WAITING, UNREACHED or GIVE_UP): the check's own, which is that answer, WAITING or GIVE_UP. */
        synchronized String answered(String result,long now){
            asking=false;askedAt=now;strikes=UNREACHED.equals(result)?strikes+1:0;
            if(SENT.equals(result)||REJECTED.equals(result)||NOT_FOUND.equals(result)){done=true;return result;}
            if(GIVE_UP.equals(result)||strikes>=STRIKES||now-failedAt>=GIVE_UP_MS){done=true;return GIVE_UP;}
            return WAITING;
        }
        /** Still asking: the caller comes back in POLL_MS. */
        synchronized boolean open(long now){return !done&&now-failedAt<GIVE_UP_MS;}
    }

    private final LinkedHashMap<String,Check> checks=new LinkedHashMap<>();
    /** The send of this nonce went unanswered at now: check it from here (again, after a retry). */
    synchronized void start(String nonce,JSONObject request,long now){
        if(nonce==null||nonce.isEmpty())return;checks.remove(nonce);checks.put(nonce,new Check(request,now));
        while(checks.size()>MAX)checks.remove(checks.keySet().iterator().next());
    }
    synchronized Check get(String nonce){return nonce==null?null:checks.get(nonce);}
    synchronized void end(String nonce){if(nonce!=null)checks.remove(nonce);}
}
