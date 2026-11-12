package si.zya.grokwear;

import org.json.JSONObject;

/**
 * A Bot's approval requests ("auto review"), as Grok Bot Android 1.25.0 shows them: a send-message chat entry whose
 * message is an auto-review-approval, pending until answered or expired (FEATURES-SPEC §2). The official one-line
 * copy per kind, the two answers the watch offers (never "always", which writes a standing rule), the answer's
 * request for the gateway and for Grok's server, and what a refusal means. No Android here, so it can be tested.
 */
final class Approvals {
    private Approvals(){}
    /** Failure codes: the answer belongs on the phone (the buttons go), or the request is no longer pending. */
    static final String ELSEWHERE="answer_elsewhere",STALE="auto-review/stale";

    /**
     * The approval in a transcript entry, reduced to what the watch shows and answers with: {entryId, agentId,
     * requestId, status, kind, line, destination?, local?}. agentId is the entry's author, else chatBot (it may be
     * empty). status is pending, approved, always, denied or expired; anything else reads "closed". Null for any
     * other entry, or one without the ids an answer needs.
     */
    static JSONObject of(JSONObject entry,String chatBot){
        try{
            if(entry==null||!"send-message".equals(GrokCore.text(entry,"kind")))return null;
            JSONObject message=entry.optJSONObject("message");if(message==null||!"auto-review-approval".equals(GrokCore.text(message,"type")))return null;
            JSONObject approval=message.optJSONObject("approval");if(approval==null)return null;
            String entryId=GrokCore.text(entry,"id"),requestId=GrokCore.text(approval,"requestId");
            if(!BotEvents.key(entryId)||!BotEvents.key(requestId))return null;
            JSONObject author=entry.optJSONObject("author");String agentId=GrokCore.text(author,"id");
            if(!BotEvents.key(agentId))agentId=BotEvents.key(chatBot)?chatBot:"";
            String status=GrokCore.text(approval,"status");
            if(!java.util.Arrays.asList("pending","approved","always","denied","expired").contains(status))status="closed";
            JSONObject copy=approval.optJSONObject("summaryCopy"),params=copy==null?null:copy.optJSONObject("params");if(params==null)params=new JSONObject();
            String kind=copy==null?"":GrokCore.text(copy,"kind");if(!kind.matches("[a-z_]{1,40}"))kind="";
            String surface=GrokCore.text(params,"surface");if(surface.isEmpty())surface=GrokCore.text(approval,"surface");
            JSONObject out=new JSONObject().put("entryId",entryId).put("agentId",agentId).put("requestId",requestId).put("status",status).put("kind",kind);
            String line="";
            switch(kind){
                case "phone_call":{String to=BotEvents.prose(GrokCore.text(params,"destination"));if(!to.isEmpty())out.put("destination",to);line=GrokCore.text(params,"request");break;}
                case "phone_call_context":line=GrokCore.text(params,"context");break;
                case "email_inbox_claim":line=GrokCore.text(params,"address");break;
                case "shell":{
                    boolean local=surface.equals("host_shell");if(local)out.put("local",true);
                    String what=BotEvents.prose(GrokCore.text(params,"description")),from=BotEvents.prose(GrokCore.text(params,"workingDirectory"));
                    if(!what.isEmpty())line=what+(local?" on your local computer":" on Grok Bot's computer")+(from.isEmpty()?"":" from "+from);break;}
                case "mcp":{
                    String server=BotEvents.prose(GrokCore.text(params,"serverDisplayName")),tool=BotEvents.prose(GrokCore.text(params,"toolName")),hint=BotEvents.prose(GrokCore.text(params,"destinationHint"));
                    if(!server.isEmpty()&&!tool.isEmpty())line="Use "+server+" tool "+tool+(hint.isEmpty()?"":" for "+hint);break;}
                default:break;
            }
            line=BotEvents.prose(line);if(line.isEmpty())line=BotEvents.prose(GrokCore.text(approval,"summary"));
            return out.put("line",line);
        }catch(Exception unreadable){return null;}
    }
    /** The card's question, in the official words where the app has them. */
    static String headline(JSONObject approval,String botName){
        String bot=botName==null||botName.isEmpty()?"your Bot":botName;
        switch(approval.optString("kind")){
            case "phone_call":{String to=approval.optString("destination");return to.isEmpty()?"Place a phone call?":"Call "+to+"?";}
            case "phone_call_context":return "Send this to your call?";
            case "phone_call_terms":return bot+" wants to make phone calls for you";
            case "email_inbox_claim":return "Claim an email address for your Bots?";
            case "shell":return "Allow "+bot+" to run a command"+(approval.optBoolean("local")?" on your computer?":"?");
            case "mcp":return "Allow "+bot+" to use a connected app?";
            default:return "Approval needed";
        }
    }
    /**
     * The two answers ({approve, deny}) for a pending request the watch can answer, else null. Legal consent for
     * phone calls has its terms and policy links, and an app's questions need the computer: those stay off the watch.
     */
    static String[] choices(JSONObject approval){
        if(!"pending".equals(approval.optString("status")))return null;
        switch(approval.optString("kind")){
            case "phone_call_terms":case "mcp_elicitation":return null;
            case "phone_call":return new String[]{"Call","Cancel"};
            case "phone_call_context":return new String[]{"Send","Cancel"};
            default:return new String[]{"Allow","Deny"};
        }
    }
    /** Where a pending request the watch leaves alone is answered. */
    static String elsewhere(JSONObject approval){return "mcp_elicitation".equals(approval.optString("kind"))?"Answer on your computer":"Answer on your phone";}
    /** A request that's no longer pending, in the official app's words. */
    static String settled(JSONObject approval){
        boolean context="phone_call_context".equals(approval.optString("kind"));
        switch(approval.optString("status")){
            case "approved":return context?"Approved to send":"Allowed once";
            case "always":return "Always allowed";
            case "denied":return context?"Not sent":"Denied";
            case "expired":return context?"Request expired":"Expired";
            default:return "Closed";
        }
    }
    /** resolveAutoReviewApproval on the gateway (box Bots): {agentId, entryId, requestId, resolution, approvalPlatform}. */
    static JSONObject gatewayBody(JSONObject approval,boolean approved)throws Exception {
        return ids(approval).put("entryId",approval.getString("entryId")).put("requestId",approval.getString("requestId"))
            .put("resolution",approved?"approved":"denied").put("approvalPlatform","android");
    }
    /** ResolveGrokBotAutoReviewApproval on Grok's server (temporal Bots): no entryId, the resolution as its enum name. */
    static JSONObject serverBody(JSONObject approval,boolean approved)throws Exception {
        return ids(approval).put("requestId",approval.getString("requestId"))
            .put("resolution",approved?"GROK_BOT_AUTO_REVIEW_APPROVAL_RESOLUTION_APPROVED":"GROK_BOT_AUTO_REVIEW_APPROVAL_RESOLUTION_DENIED").put("approvalPlatform","android");
    }
    private static JSONObject ids(JSONObject approval)throws Exception {
        String agent=approval.optString("agentId"),entry=approval.optString("entryId"),request=approval.optString("requestId");
        if(!BotEvents.key(agent)||!BotEvents.key(entry)||!BotEvents.key(request))throw new GrokCore.Failure("This request can't be answered from the watch.",true,ELSEWHERE);
        return new JSONObject().put("agentId",agent);
    }
    /**
     * What a refused or lost answer means, in plain words (FEATURES-SPEC §2.5). A lost reply may have landed, so it
     * is never sent again on its own; the chat shows what happened.
     */
    static GrokCore.Failure problem(GrokCore.Failure failure,boolean server){
        String code=failure.code==null?"":failure.code;
        if(code.equals(ELSEWHERE))return failure;
        if(!failure.rejected){GrokCore.Failure lost=new GrokCore.Failure("Couldn't confirm your answer. Check the chat before answering again.",false,code,failure.status);lost.initCause(failure);return lost;}
        if(code.equals(STALE)||code.equals("not_found")||(server&&failure.status==404))return new GrokCore.Failure("Already answered or expired.",true,STALE,failure.status);
        // A gateway without the operation (an older Bot computer) can't take answers: the phone can.
        if(!server&&failure.status==404)return new GrokCore.Failure("This Bot can't take answers from the watch. Answer on your phone.",true,ELSEWHERE,failure.status);
        // Sign-in trouble already comes in words.
        if(failure.status==401||failure.status==403)return failure;
        return new GrokCore.Failure("Couldn't answer. Open on phone.",true,code,failure.status);
    }
}
