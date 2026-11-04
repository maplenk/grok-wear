package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class SandAccessTest {
    private static SandAccess access(String state,String reason)throws Exception {
        return SandAccess.parse(new JSONObject().put("state","SAND_ACCESS_STATE_"+state).put("blockReason","SAND_ACCESS_BLOCK_REASON_"+reason));
    }

    @Test public void eachReasonSaysWhoCanFixIt()throws Exception {
        assertTrue(access("GRANTED","NONE").granted());
        assertTrue(access("PAYMENT_REQUIRED","FREE_TRIAL_AVAILABLE").message().contains("free trial"));
        assertTrue(access("PAYMENT_REQUIRED","PAYWALL_INDIVIDUAL").message().contains("needs a plan"));
        assertTrue(access("PAYMENT_REQUIRED","PAYWALL_TEAM_MEMBER").message().contains("team admin"));
        assertTrue(access("PAYMENT_REQUIRED","PAYWALL_TEAM_ADMIN").message().contains("As an admin"));
        assertTrue(access("UNAVAILABLE","TEAM_PRIVACY_MODE").message().contains("privacy mode"));
        assertTrue(access("UNAVAILABLE","TEAM_SETUP_REQUIRED").message().contains("set up"));
        assertTrue(access("UNAVAILABLE","TEAM_ACCESS_REQUIRED").message().contains("team admin"));
        assertTrue(access("UNAVAILABLE","NOT_OFFERED").message().contains("isn’t offered"));
        assertTrue(access("UNAVAILABLE","NONE").message().contains("isn’t available"));
        assertEquals("Access: payment_required paywall_individual",access("PAYMENT_REQUIRED","PAYWALL_INDIVIDUAL").logLine());
    }

    @Test public void theLinkIsTheServersHttpsPaywallOrOnboarding()throws Exception {
        JSONObject body=new JSONObject().put("state","SAND_ACCESS_STATE_PAYMENT_REQUIRED").put("blockReason","SAND_ACCESS_BLOCK_REASON_PAYWALL_INDIVIDUAL")
                .put("unifiedAccessPaywall",new JSONObject().put("title","t").put("body","b").put("actionLabel","a").put("href","https://cursor.com/bot/subscribe"));
        assertEquals("https://cursor.com/bot/subscribe",SandAccess.parse(body).link());
        body.getJSONObject("unifiedAccessPaywall").put("href","http://insecure.example/pay");
        assertEquals(SandAccess.ONBOARDING,SandAccess.parse(body).link());
        body.getJSONObject("unifiedAccessPaywall").put("href","javascript:alert(1)");
        assertEquals(SandAccess.ONBOARDING,SandAccess.parse(body).link());
        SandAccess privacy=SandAccess.parse(new JSONObject().put("state","SAND_ACCESS_STATE_PAYMENT_REQUIRED").put("blockReason","SAND_ACCESS_BLOCK_REASON_NONE").put("privacyDisclaimerRequired",true));
        assertEquals(SandAccess.SHARE_DATA,privacy.link());assertTrue(privacy.message().contains("data-sharing"));
        assertTrue(privacy.logLine().endsWith("privacy_step"));
    }

    @Test public void unknownOrNumericEnumsAreUnknown()throws Exception {
        SandAccess odd=SandAccess.parse(new JSONObject().put("state",2).put("blockReason","SOMETHING"));
        assertEquals("unknown",odd.state);assertEquals("unspecified",odd.reason);assertFalse(odd.granted());
        assertTrue(odd.message().contains("Grok refused this account"));
    }
}
