package si.zya.grokwear;

import org.json.JSONObject;
import java.util.Locale;

/**
 * The account's access to Grok Bot, from aiserver.v1.DashboardService/GetSandAccessStatus: the call the
 * official clients make when the backend refuses an account (desktop classifyRefusal, Android useSandAccessGate).
 * Only enum values and the server's own https paywall link are kept.
 */
final class SandAccess {
    static final String ONBOARDING="https://cursor.com/bot/onboarding";
    static final String SHARE_DATA=ONBOARDING+"?step=share_data";
    /** granted, unavailable, payment_required or unknown. */
    final String state;
    /** none, team_privacy_mode, team_setup_required, team_access_required, not_offered, free_trial_available, paywall_individual, paywall_team_member, paywall_team_admin or unspecified. */
    final String reason;
    final boolean privacyDisclaimerRequired;
    /** The server's paywall link (https only), or "". */
    final String paywallHref;

    SandAccess(String state,String reason,boolean privacyDisclaimerRequired,String paywallHref){
        this.state=state;this.reason=reason;this.privacyDisclaimerRequired=privacyDisclaimerRequired;this.paywallHref=paywallHref;
    }

    static SandAccess parse(JSONObject body){
        String state=enumValue(body.opt("state"),"SAND_ACCESS_STATE_");
        String reason=enumValue(body.opt("blockReason"),"SAND_ACCESS_BLOCK_REASON_");
        if(state.isEmpty())state="unknown";if(reason.isEmpty())reason="unspecified";
        String href="";JSONObject paywall=body.optJSONObject("unifiedAccessPaywall");
        if(paywall!=null){String link=paywall.optString("href","");if(httpsLink(link))href=link;}
        return new SandAccess(state,reason,body.optBoolean("privacyDisclaimerRequired",false),href);
    }
    /** "SAND_ACCESS_STATE_PAYMENT_REQUIRED" → "payment_required"; numbers and unknown names → "". */
    private static String enumValue(Object raw,String prefix){
        if(!(raw instanceof String))return "";
        String name=(String)raw;if(!name.startsWith(prefix))return "";
        String value=name.substring(prefix.length()).toLowerCase(Locale.ROOT);
        return value.matches("[a-z_]{1,40}")?value:"";
    }
    static boolean httpsLink(String link){
        if(link==null||link.length()>600)return false;
        try{java.net.URI uri=new java.net.URI(link);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getRawUserInfo()==null;}
        catch(Exception bad){return false;}
    }

    boolean granted(){return "granted".equals(state);}

    /** For the log: "Access: payment_required paywall_individual". */
    String logLine(){return "Access: "+state+" "+reason+(privacyDisclaimerRequired?" privacy_step":"");}

    /** Short watch wording: what's wrong and who can fix it. */
    String message(){
        switch(reason){
            case "free_trial_available":return "Grok Bot has a free trial for this account. Start it in the Grok Bot app on your phone, then try again.";
            case "paywall_individual":return "Grok Bot needs a plan on this account. Subscribe on your phone, then try again.";
            case "paywall_team_member":return "Your team’s plan doesn’t include Grok Bot yet. Ask a team admin to add it.";
            case "paywall_team_admin":return "Your team needs a Grok Bot plan. As an admin, add it on your phone or the web.";
            case "team_privacy_mode":return "Your team’s privacy mode blocks Grok Bot. A team admin can change it.";
            case "team_setup_required":return "Your team hasn’t set up Grok Bot yet. Ask a team admin.";
            case "team_access_required":return "Ask your team admin for access to Grok Bot.";
            case "not_offered":return "Grok Bot isn’t offered for this account yet.";
            default:break;
        }
        if(privacyDisclaimerRequired)return "Finish Grok Bot setup on your phone, including the data-sharing step, then try again.";
        if("payment_required".equals(state))return "Grok Bot needs a plan on this account. Subscribe on your phone, then try again.";
        if("unavailable".equals(state))return "Grok Bot isn’t available for this account right now.";
        return "Grok refused this account. Check that Grok Bot opens in its phone app with the same account.";
    }

    /** Where the phone can fix it: the server's paywall, the data-sharing step or onboarding. */
    String link(){
        if(!paywallHref.isEmpty())return paywallHref;
        if(privacyDisclaimerRequired)return SHARE_DATA;
        return ONBOARDING;
    }
}
