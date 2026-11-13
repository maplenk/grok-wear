package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A Bot's question during one of its outbound phone calls, as Grok Bot Android 1.25.0 shows it (MORE-FEATURES-SPEC
 * §1): a send-message chat entry whose message is a user form with a phoneCallQuestion, answered (Send) or declined
 * (Skip) before its deadline through the user-form requests. When it shows, its state in the official words, and the
 * answer's requests and reply. No Android here, so it can be tested.
 */
final class CallQuestions {
    private CallQuestions(){}
    /** Ids of these entries start so; one without its question is stale, and the official chat hides it. */
    static final String PREFIX="phone-call-question:";
    /** The official words for any answer that didn't go through. */
    static final String FAILED="Couldn't send your answer";
    static final int MAX_ANSWER=4096;

    /**
     * The question in a transcript entry, reduced to what the card shows and the answer needs: {entryId, agentId,
     * askedBy, prompt, placeholder, prefill, deadlineAtMs, revealAtMs?, presentedAtMs?, closed?, resolution?,
     * answer?}. agentId is chatBot (the chat's Bot), else the entry's author (it may be empty). Null for any other
     * entry, a stale one, or one without its entry id.
     */
    static JSONObject of(JSONObject entry,String chatBot){
        try{
            if(entry==null||!"send-message".equals(GrokCore.text(entry,"kind")))return null;
            JSONObject message=entry.optJSONObject("message");if(message==null||!"user-form".equals(GrokCore.text(message,"type")))return null;
            JSONObject question=message.optJSONObject("phoneCallQuestion");if(question==null)return null;
            String entryId=GrokCore.text(entry,"id"),agentId=BotEvents.key(chatBot)?chatBot:GrokCore.text(entry.optJSONObject("author"),"id");
            if(!BotEvents.key(entryId))return null;if(!BotEvents.key(agentId))agentId="";
            JSONObject form=message.optJSONObject("formRequest");
            String prompt=BotEvents.prose(GrokCore.text(question,"prompt"));if(prompt.isEmpty())prompt=BotEvents.prose(GrokCore.text(form,"instruction"));
            String prefill=GrokCore.text(question,"prefill").trim();if(prefill.length()>MAX_ANSWER)prefill="";
            JSONObject out=new JSONObject().put("entryId",entryId).put("agentId",agentId).put("askedBy",BotEvents.prose(GrokCore.text(question,"askedBy")))
                .put("prompt",prompt).put("placeholder",BotEvents.prose(GrokCore.text(question,"placeholder"))).put("prefill",prefill)
                .put("deadlineAtMs",ServerTranscripts.msOf(question.opt("deadlineAtMs")));
            for(String key:new String[]{"revealAtMs","presentedAtMs"}){long ms=ServerTranscripts.msOf(question.opt(key));if(ms>0)out.put(key,ms);}
            // Grok's own final word wins over everything else; the card has words for sent, skipped, timed_out and ended.
            Object closed=question.opt("closed");if(closed!=null&&closed!=JSONObject.NULL)out.put("closed",closed instanceof String&&((String)closed).matches("[a-z_]{1,40}")?closed:"closed");
            String resolution=GrokCore.text(entry,"formResolution");if(resolution.equals("submitted")||resolution.equals("dismissed"))out.put("resolution",resolution);
            JSONArray outcomes=entry.optJSONArray("formFieldOutcomes");
            if(outcomes!=null)for(int i=0;i<Math.min(outcomes.length(),32);i++){JSONObject field=outcomes.optJSONObject(i);
                if(field!=null&&"answer".equals(GrokCore.text(field,"id"))){String said=GrokCore.text(field,"submittedValue").trim();if(!said.isEmpty())out.put("answer",said.length()>MAX_ANSWER?said.substring(0,MAX_ANSWER):said);break;}}
            return out;
        }catch(Exception unreadable){return null;}
    }
    /** open, or how it ended: Grok's closed (sent, skipped, timed_out, ended), the answer's resolution, or the deadline passing here. */
    static String state(JSONObject question,long nowMs){
        if(question.has("closed"))return question.optString("closed");
        switch(question.optString("resolution")){case "submitted":return "sent";case "dismissed":return "skipped";default:break;}
        long deadline=question.optLong("deadlineAtMs");
        return deadline>0&&nowMs>=deadline?"timed_out":"open";
    }
    /** Not shown yet: Grok reveals it at revealAtMs, unless it was presented or closed first. */
    static boolean heldBack(JSONObject question,long nowMs){
        return question.has("revealAtMs")&&!question.has("presentedAtMs")&&!question.has("closed")&&nowMs<question.optLong("revealAtMs");
    }
    /** "{callee} is asking", as the official card says it. */
    static String asking(JSONObject question){String who=question.optString("askedBy");return (who.isEmpty()?"The person on the call":who)+" is asking";}
    /** "Respond in m:ss" until the deadline. */
    static String remaining(long deadlineAtMs,long nowMs){return "Respond in "+PhoneCalls.clock(Math.max(0,deadlineAtMs-nowMs));}
    /** The input's hint: the question's own, else "Type what {bot} should say". */
    static String placeholder(JSONObject question,String botName){
        String hint=question.optString("placeholder");if(!hint.isEmpty())return hint;
        return "Type what "+(botName==null||botName.isEmpty()?"Bot":botName)+" should say";
    }
    /** A settled question in the official words. */
    static String settled(String state){
        switch(state){case "sent":return "Submitted";case "skipped":return "Skipped";case "timed_out":return "No reply in time";case "ended":return "Call ended";default:return "Closed";}
    }
    /** The answer as typed or dictated, trimmed; an empty or overlong one is refused before anything is sent. */
    static String answer(String text)throws GrokCore.Failure {
        String said=text==null?"":text.trim();
        if(said.isEmpty()||said.length()>MAX_ANSWER)throw new GrokCore.Failure("Use an answer between 1 and 4,096 characters.",true);
        return said;
    }
    /** submitUserForm {agentId, entryId, values:{answer}, platform} or, for Skip (answer null), dismissUserForm {…, mode}, on the gateway (box Bots). */
    static JSONObject gatewayBody(JSONObject question,String answer)throws Exception {
        JSONObject body=ids(question);
        return (answer==null?body.put("mode","dismissed"):body.put("values",new JSONObject().put("answer",answer(answer)))).put("platform","android");
    }
    /** SubmitGrokBotUserForm or DismissGrokBotUserForm on Grok's server (temporal Bots): the same, with the enum names. */
    static JSONObject serverBody(JSONObject question,String answer)throws Exception {
        JSONObject body=ids(question);
        return (answer==null?body.put("mode","GROK_BOT_USER_FORM_DISMISS_MODE_DISMISSED"):body.put("values",new JSONObject().put("answer",answer(answer))))
            .put("platform","GROK_BOT_USER_FORM_CLIENT_PLATFORM_ANDROID");
    }
    private static JSONObject ids(JSONObject question)throws Exception {
        String agent=question.optString("agentId"),entry=question.optString("entryId");
        if(!BotEvents.key(agent)||!BotEvents.key(entry))throw new GrokCore.Failure(FAILED,true);
        return new JSONObject().put("agentId",agent).put("entryId",entry);
    }
    /** Null when Grok took it ({accepted:true}, no refusal); else what to show: the refusal's own words, or the official words. */
    static String refused(JSONObject reply){
        JSONObject refusal=reply==null?null:reply.optJSONObject("refusal");
        if(reply!=null&&Boolean.TRUE.equals(reply.opt("accepted"))&&refusal==null)return null;
        String said=BotEvents.prose(GrokCore.text(refusal,"message"));
        return said.isEmpty()?FAILED:said;
    }
    /** A failed answer in the official words; sign-in trouble keeps its own. */
    static GrokCore.Failure problem(GrokCore.Failure failure){
        if(failure.rejected&&(failure.status==401||failure.status==403))return failure;
        GrokCore.Failure failed=new GrokCore.Failure(FAILED,failure.rejected,failure.code,failure.status);failed.initCause(failure);return failed;
    }
}
