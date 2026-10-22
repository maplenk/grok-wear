package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * End-of-call handoff to the Bot, matching the official clients: after the call record is
 * saved, the Bot's send_task tool receives the call-ended notice and then whatever the caller
 * asked for that the call never relayed. Sent at most once; a failure is never retried, so a
 * request cannot reach the Bot twice.
 */
final class CallHandoff {
    private CallHandoff(){}
    static final String TOOL="send_task";
    /** Exact notice text of the official clients. */
    static final String CALL_ENDED="The call ended. This channel is closed from now on, so anything still owed goes in the chat.";
    static final int MAX_REQUEST=2000,MAX_LINES=6;
    interface Tool {JSONObject call(JSONObject body)throws Exception;}

    /** Only Bots run by the temporal harness take the handoff, as in the official clients. */
    static boolean applies(JSONObject bot){return bot!=null&&"temporal".equals(bot.optString("harness"));}

    /**
     * The notice, then the leftover request. The watch relays nothing during a call, so every
     * finalized caller line counts as unrelayed; the last six are kept.
     */
    static List<String> requests(JSONArray turns){
        List<String> lines=new ArrayList<>();
        if(turns!=null)for(int i=0;i<turns.length();i++){JSONObject turn=turns.optJSONObject(i);if(turn==null||!"user".equals(turn.optString("speaker")))continue;String text=turn.optString("text","").trim();if(!text.isEmpty())lines.add(text);}
        if(lines.size()>MAX_LINES)lines=new ArrayList<>(lines.subList(lines.size()-MAX_LINES,lines.size()));
        List<String> requests=new ArrayList<>();requests.add(CALL_ENDED);
        String leftover=leftoverRequest(lines);if(leftover!=null)requests.add(leftover);
        return requests;
    }
    /** The last line is the request; earlier lines are quoted as what the caller said. */
    static String leftover(List<String> lines){
        if(lines.isEmpty())return null;String last=lines.get(lines.size()-1);if(lines.size()==1)return last;
        StringBuilder said=new StringBuilder();for(int i=0;i<lines.size()-1;i++){if(i>0)said.append(", ");said.append('"').append(lines.get(i)).append('"');}
        return last+" What the user said on the call: "+said;
    }
    /** Drops the oldest quoted lines until the request fits; a single line is cut to fit. */
    static String leftoverRequest(List<String> lines){
        List<String> remaining=new ArrayList<>(lines);
        while(!remaining.isEmpty()){
            String request=leftover(remaining);if(request==null)return null;
            if(request.length()<=MAX_REQUEST)return request;
            if(remaining.size()==1)return request.substring(0,MAX_REQUEST).replaceAll("\\s+$","");
            remaining.remove(0);
        }
        return null;
    }
    /** Wire name of the served outcome; the desktop client maps it to "served" before checking. */
    static final String SERVED="VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_SERVED";
    /**
     * Sends each request in order and stops at the first one that is not accepted. Returns a fixed
     * outcome token, plus the reply's own kind or relay token when it was not accepted.
     */
    static String send(Tool tool,String botId,String callId,List<String> requests,JSONObject line){
        for(String request:requests){
            try{
                JSONObject reply=tool.call(new JSONObject().put("agentId",botId).put("callId",callId).put("name",TOOL)
                    .put("input",new JSONObject().put("request",request)).put("line",line));
                String kind=reply.optString("kind");
                if(!SERVED.equals(kind)&&!"served".equals(kind))return "refused kind="+token(kind);
                JSONObject output=reply.optJSONObject("output");String relay=output==null?"":output.optString("relay");
                if(!"accepted".equals(relay))return "refused relay="+token(relay);
            }catch(Exception failed){return "failed";}
        }
        return "accepted";
    }
    /** Server-chosen enum token for logs; anything else is withheld. */
    private static String token(String value){return value.matches("[A-Za-z_]{1,64}")?value:"other";}
}
