package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A Bot's question with option buttons, as Grok Bot Android 1.25.0 shows it (MORE-FEATURES-SPEC §5): a send-message
 * chat entry whose message is a widget, answered on that same entry (respondedValue) or dismissed (widgetDismissed).
 * Its options, its state, the value an answer sends, and the requests and reply. Also the phone-tool permission asks
 * only the phone can answer (§5.4). No Android here, so it can be tested.
 */
final class Widgets {
    private Widgets(){}
    /** The words when an answer didn't go through and Grok gave none. */
    static final String FAILED="Couldn't send. Open on phone.";
    /** Values the official app handles on the phone itself (pick the main Bot, start a voice call): never offered here. */
    static final Set<String> RESERVED=Set.of("sand:choose-main-agent","sand:start-voice-call");
    /** More options than a watch should list; past this the rest are left to the phone. */
    static final int MAX_OPTIONS=12;

    /**
     * The widget in a transcript entry: {entryId, agentId, prompt, helpText?, options:[{label, value, danger?,
     * description?}], multiSelect, allowCustom, dismissOnMoveOn, special?, answer?, echoed?, dismissed?}. agentId is
     * chatBot (the chat's Bot; live entries carry no author), else the author's. The value an option sends is its value,
     * else its label; reserved options are left out. special is a form the watch doesn't draw (a rule change, steps, an
     * order). Null for any other entry, or a widget whose prompt isn't text (the official app shows those as a plain card).
     */
    static JSONObject of(JSONObject entry,String chatBot){
        try{
            if(entry==null||!"send-message".equals(GrokCore.text(entry,"kind")))return null;
            JSONObject message=entry.optJSONObject("message");if(message==null||!"widget".equals(GrokCore.text(message,"type")))return null;
            JSONObject widget=message.optJSONObject("widget");if(widget==null||!(widget.opt("prompt") instanceof String))return null;
            String entryId=GrokCore.text(entry,"id"),agentId=BotEvents.key(chatBot)?chatBot:GrokCore.text(entry.optJSONObject("author"),"id");
            if(!BotEvents.key(entryId))return null;if(!BotEvents.key(agentId))agentId="";
            JSONObject out=new JSONObject().put("entryId",entryId).put("agentId",agentId).put("prompt",BotEvents.prose(widget.getString("prompt")));
            String help=BotEvents.prose(GrokCore.text(widget,"helpText"));if(!help.isEmpty())out.put("helpText",help);
            JSONArray options=new JSONArray(),raw=widget.optJSONArray("options");
            if(raw!=null)for(int i=0;i<raw.length()&&options.length()<MAX_OPTIONS;i++){JSONObject o=raw.optJSONObject(i);if(o==null)continue;
                String label=BotEvents.prose(GrokCore.text(o,"label"));Object given=o.opt("value");
                // value ?? label, as the official app sends it; an empty value would answer nothing, so it reads as absent.
                String value=given instanceof String&&!((String)given).isEmpty()?(String)given:GrokCore.text(o,"label");
                if(label.isEmpty()||value.length()>4096||RESERVED.contains(value))continue;
                JSONObject option=new JSONObject().put("label",label).put("value",value);
                if("danger".equals(GrokCore.text(o,"style")))option.put("danger",true);
                String caption=BotEvents.prose(GrokCore.text(o,"description"));if(!caption.isEmpty())option.put("description",caption);
                options.put(option);}
            out.put("options",options).put("multiSelect",Boolean.TRUE.equals(widget.opt("multiSelect"))).put("allowCustom",Boolean.TRUE.equals(widget.opt("allowCustom")))
                .put("dismissOnMoveOn",Boolean.TRUE.equals(widget.opt("dismissOnMoveOn")));
            for(String form:new String[]{"ruleChange","steps","order"})if(widget.opt(form)!=null&&widget.opt(form)!=JSONObject.NULL)out.put("special",true);
            if(entry.opt("respondedValue") instanceof String){String answer=entry.getString("respondedValue");out.put("answer",answer.length()>4096?answer.substring(0,4096):answer);
                if(Boolean.TRUE.equals(entry.opt("respondedValueEchoed")))out.put("echoed",true);}
            if(Boolean.TRUE.equals(entry.opt("widgetDismissed")))out.put("dismissed",true);
            return out;
        }catch(Exception unreadable){return null;}
    }
    /**
     * As the official presentation computes it: answered (or echoed, when the answer is also your own chat message),
     * dismissed (or stale: you wrote in the chat since and it goes once you move on), else open.
     */
    static String state(JSONObject widget,boolean stale){
        if(widget.has("answer"))return widget.optBoolean("echoed")?"echoed":"answered";
        if(widget.optBoolean("dismissed")||stale&&widget.optBoolean("dismissOnMoveOn"))return "dismissed";
        return "open";
    }
    /** You wrote in the chat after this row (rows as the chat holds them, oldest first): a dismissOnMoveOn question is then stale. */
    static boolean movedOn(JSONArray rows,int index){
        for(int i=index+1;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&"user".equals(row.optString("role")))return true;}
        return false;
    }
    /** Whether the watch can answer it with its buttons: open, drawn here, with options left. */
    static boolean answerable(JSONObject widget){
        JSONArray options=widget.optJSONArray("options");return !widget.optBoolean("special")&&options!=null&&options.length()>0;
    }
    /** The answer in words: each line of the value as the option it came from, else as sent. */
    static String answerText(JSONObject widget,String value){
        if(value==null)return "";JSONArray options=widget.optJSONArray("options");List<String> said=new ArrayList<>();
        for(String part:value.split("\n")){if(part.trim().isEmpty())continue;String shown=part.trim();
            if(options!=null)for(int i=0;i<options.length();i++){JSONObject o=options.optJSONObject(i);if(o!=null&&o.optString("value").equals(part)){shown=o.optString("label");break;}}
            said.add(shown);}
        return BotEvents.prose(String.join(", ",said));
    }
    /** Several picks (option indexes), as the official Done sends them: in option order, once each by their text, one per line. */
    static String joinPicked(JSONObject widget,Set<Integer> picked){
        JSONArray options=widget.optJSONArray("options");LinkedHashMap<String,String> kept=new LinkedHashMap<>();
        if(options!=null)for(int i=0;i<options.length();i++){if(!picked.contains(i))continue;String value=options.optJSONObject(i).optString("value");
            String canonical=value.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);if(!canonical.isEmpty())kept.putIfAbsent(canonical,value);}
        return String.join("\n",kept.values());
    }
    /**
     * respondToWidget / RespondGrokBotWidget {agentId, entryId, value}, or for ✕ (value null) dismissWidget /
     * DismissGrokBotWidget {agentId, entryId}: the same body on the gateway (box Bots) and Grok's server (temporal Bots).
     */
    static JSONObject body(JSONObject widget,String value)throws Exception {
        String agent=widget.optString("agentId"),entry=widget.optString("entryId");
        if(!BotEvents.key(agent)||!BotEvents.key(entry))throw new GrokCore.Failure(FAILED,true);
        JSONObject body=new JSONObject().put("agentId",agent).put("entryId",entry);
        if(value!=null){if(value.isEmpty()||value.length()>4096)throw new GrokCore.Failure(FAILED,true);body.put("value",value);}
        return body;
    }
    /**
     * Null when Grok took it, else the refusal's words ("" when it gave none). Grok's server says accepted:true; the
     * gateway's reply takes it unless it carries a refusal or accepted:false (an empty reply is accepted).
     */
    static String refused(JSONObject reply,boolean server){
        JSONObject refusal=reply==null?null:reply.optJSONObject("refusal");
        boolean accepted=refusal==null&&(server?reply!=null&&Boolean.TRUE.equals(reply.opt("accepted")):reply==null||!Boolean.FALSE.equals(reply.opt("accepted")));
        return accepted?null:BotEvents.prose(GrokCore.text(refusal,"message"));
    }
    /** A failed answer in plain words; sign-in trouble keeps its own. */
    static GrokCore.Failure problem(GrokCore.Failure failure){
        if(failure.rejected&&(failure.status==401||failure.status==403))return failure;
        GrokCore.Failure failed=new GrokCore.Failure(FAILED,failure.rejected,failure.code,failure.status);failed.initCause(failure);return failed;
    }
    /**
     * A pending ask to run one of the Bot's tools on your phone (a local-tool-permission with action run-phone-tool,
     * §5.4): {entryId}. It's bound to the phone, so the watch only says so. Null for anything else, or a settled ask.
     */
    static JSONObject phoneTool(JSONObject entry){
        try{
            if(entry==null||!"send-message".equals(GrokCore.text(entry,"kind")))return null;
            JSONObject message=entry.optJSONObject("message");if(message==null||!"local-tool-permission".equals(GrokCore.text(message,"type")))return null;
            JSONObject ask=message.optJSONObject("ask");String entryId=GrokCore.text(entry,"id");
            if(ask==null||!"pending".equals(GrokCore.text(ask,"status"))||!"run-phone-tool".equals(GrokCore.text(ask,"action"))||!BotEvents.key(entryId))return null;
            return new JSONObject().put("entryId",entryId);
        }catch(Exception unreadable){return null;}
    }
}
