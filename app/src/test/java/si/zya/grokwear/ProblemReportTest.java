package si.zya.grokwear;

import static org.junit.Assert.*;

import java.net.URLDecoder;
import org.junit.Test;

public class ProblemReportTest {
    @Test public void keepsTheNewestSixtyOnOneLineEach() {
        String recent="";
        for(int i=1;i<=62;i++)recent=ProblemReport.add(recent,"event "+i+"\nsecond line");
        String[] lines=recent.split("\n");
        assertEquals(60,lines.length);
        assertEquals("event 62 second line",lines[0]);
        assertEquals("event 3 second line",lines[59]);
    }

    @Test public void aLogIdLeadsTheReportAndTheFullTextKeepsEveryLine() throws Exception {
        StringBuilder log=new StringBuilder();
        for(int i=0;i<60;i++)log.append("11-03 14:").append(i<10?"0":"").append(i).append("Z Bot sandbox start: HTTP 401 unauthenticated · 0.4 s · via phone 1.6 Mbps\n");
        String url=ProblemReport.url("samsung SM-R920","16 (API 36)","0.6.3 (35)",false,"via phone","0 Bots","Needs attention: x","",log.toString(),true,"9ec79c33");
        String body=URLDecoder.decode(url.substring(url.indexOf("&body=")+6).replace("+","%2B"),"UTF-8");
        assertTrue(body.startsWith("Log ID: 9ec79c33\n"));
        assertTrue(url.length()<=ProblemReport.LINK_BUDGET);
        String full=ProblemReport.fullText("samsung SM-R920","16 (API 36)","0.6.3 (35)",false,"via phone","0 Bots","Needs attention: x","",log.toString(),"9ec79c33");
        assertTrue(full.startsWith("Log ID: 9ec79c33\nWhere: Needs attention: x\nWatch: samsung SM-R920"));
        assertEquals(60,full.split("Bot sandbox start").length-1);
        assertTrue(full.contains("Log (UTC, newest first):\n11-03 14:00Z"));
    }
    @Test public void refusalsAreShortAndKeepNoMessage() {
        ServiceRefusal why=ServiceRefusal.parse(401,"",("{\"code\":\"unauthenticated\",\"message\":\"user private@example.com has no access\","
            +"\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"debug\":{\"details\":{\"additionalInfo\":{\"sandUsageScopeError\":\"stale_team\"}}}}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GrokCore.Failure failure=new GrokCore.Failure("Grok refused this sign-in.",true,why.failureCode(),401);
        assertEquals("HTTP 401 stale_team",ProblemReport.describe(failure));
        assertEquals("HTTP 401 unauthenticated stale_team",why.summary());
        assertFalse(why.summary().contains("private"));
    }

    @Test public void aRepeatedSuccessKeepsOneLineAndFailuresStay() {
        String log="";
        log=ProblemReport.add(log,"10-29 14:00Z Bot list: ok · 7 Bots in 1.2 s · Wi-Fi","Bot list: ok");
        log=ProblemReport.add(log,"10-29 14:01Z Bot list: ok · 7 Bots in 0.9 s · Wi-Fi","Bot list: ok");
        assertEquals("10-29 14:01Z Bot list: ok · 7 Bots in 0.9 s · Wi-Fi",log);
        log=ProblemReport.add(log,"10-29 14:02Z Bot list: HTTP 503 · after 0.8 s · Wi-Fi");
        log=ProblemReport.add(log,"10-29 14:03Z Bot list: ok · 7 Bots in 1.0 s · Wi-Fi","Bot list: ok");
        assertEquals("10-29 14:03Z Bot list: ok · 7 Bots in 1.0 s · Wi-Fi\n10-29 14:02Z Bot list: HTTP 503 · after 0.8 s · Wi-Fi\n10-29 14:01Z Bot list: ok · 7 Bots in 0.9 s · Wi-Fi",log);
    }

    @Test public void watchSpeedAndSizeReadNaturally() {
        assertEquals("Google Pixel Watch",ProblemReport.watch("Google","Google Pixel Watch"));
        assertEquals("samsung SM-L340",ProblemReport.watch("samsung","SM-L340"));
        assertEquals("1.6 Mbps",ProblemReport.mbps(1600));
        assertEquals("28 Mbps",ProblemReport.mbps(28626));
        assertEquals("412 KB",ProblemReport.size(411_700));
        assertEquals("1 KB",ProblemReport.size(20));
        assertEquals("1.2 MB",ProblemReport.size(1_234_567));
    }

    @Test public void describesWithoutNetworkMessages() {
        assertEquals("HTTP 503",ProblemReport.describe(new GrokCore.Failure("Grok request failed (HTTP 503).",false,"",503)));
        assertEquals("HTTP 403 permission_denied",ProblemReport.describe(new GrokCore.Failure("x",true,"permission_denied",403)));
        GrokCore.Failure lost=new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",false);
        lost.initCause(new java.net.UnknownHostException("Unable to resolve host \"secret.cursorvm.com\""));
        assertEquals("UnknownHostException",ProblemReport.describe(lost));
        GrokCore.Failure tooLarge=new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",false);
        tooLarge.initCause(new GrokVault.ResponseTooLarge("reply",1<<20));
        assertEquals("ResponseTooLarge",ProblemReport.describe(tooLarge));
        assertEquals("Bot list format is not recognized. No Bot was selected.",
            ProblemReport.describe(new GrokCore.Failure("Bot list format is not recognized. No Bot was selected.",true)));
        assertEquals("SocketTimeoutException",ProblemReport.describe(new java.net.SocketTimeoutException("timeout to 10.0.0.1")));
        // On screen only the short forms: the app's own refusals already say what happened.
        assertEquals("",ProblemReport.code(new GrokCore.Failure("Bot list format is not recognized. No Bot was selected.",true)));
        assertEquals("ResponseTooLarge",ProblemReport.code(tooLarge));
    }

    @Test public void issueLinkCarriesTheDetailsAndTheLog() throws Exception {
        String url=ProblemReport.url("Google Pixel Watch 5","16 (API 36)","0.6.0 (28)",false,"via phone","","",
            "10-29 14:02Z Bot list: HTTP 503 · after 0.8 s · via phone\n10-29 14:01Z Bot list: 7 in 1.2 s · Wi-Fi",false);
        assertTrue(url.startsWith("https://github.com/maplenk/grok-wear/issues/new?title="));
        String decoded=URLDecoder.decode(url,"UTF-8");
        assertTrue(decoded.contains("title=Problem on Google Pixel Watch 5&body="));
        assertTrue(decoded.contains("Watch: Google Pixel Watch 5\nAndroid: 16 (API 36)\nGrok Wear: 0.6.0 (28)\nAssistant button: off\nNetwork: via phone\n"
            +"Log (UTC, newest first):\n10-29 14:02Z Bot list: HTTP 503 - after 0.8 s - via phone\n10-29 14:01Z Bot list: 7 in 1.2 s - Wi-Fi\n"));
        assertTrue(decoded.endsWith("What happened?\n"));
        assertTrue(ProblemReport.url("a","b","c",false,"Wi-Fi","","","",false).contains("none"));
    }

    @Test public void aReportFromAScreenNamesIt() throws Exception {
        String decoded=URLDecoder.decode(ProblemReport.url("Google Pixel Watch","15 (API 35)","0.6.0 (28)",true,"via phone","",
            "Bot list: Bots aren’t available yet (HTTP 503). Try again later.","",false),"UTF-8");
        assertTrue(decoded.contains("title=Bot list problem on Google Pixel Watch&body=Where: Bot list: Bots aren’t available yet (HTTP 503). Try again later.\nWatch: Google Pixel Watch\n"));
    }

    @Test public void emailIsAMailtoLinkWithSpacesAsPercent20() throws Exception {
        String url=ProblemReport.url("Google Pixel Watch","15 (API 35)","0.6.0 (29)",true,"Wi-Fi","7 Bots · main Bot set · loaded 2 min ago · reply alerts on · battery 54%","Bot list: Bots aren’t available yet (HTTP 503).",
            "10-29 14:02Z Bot list: HTTP 503 · after 0.8 s · Wi-Fi",true);
        assertTrue(url.startsWith("mailto:namankhator@gmail.com?subject=Bot%20list%20problem%20on%20Google%20Pixel%20Watch&body=Where:%20Bot%20list:"));
        assertFalse("mail apps show + literally",url.contains("+"));
        assertTrue(url.length()<=ProblemReport.LINK_BUDGET);
        String body=URLDecoder.decode(url.substring(url.indexOf("&body=")+6),"UTF-8");
        assertTrue(body.contains("Network: Wi-Fi\nState: 7 Bots - main Bot set - loaded 2 min ago - reply alerts on - battery 54%\nLog (UTC, newest first):\n10-29 14:02Z Bot list: HTTP 503 - after 0.8 s - Wi-Fi\n"));
    }

    @Test public void encodingLeavesQueryCharactersAndDecodesBack() throws Exception {
        String text="10-29 14:02Z Bot list: HTTP 503 (x), y; a&b=c+d #1 100% ü\n";
        String encoded=ProblemReport.encode(text);
        assertTrue(encoded.startsWith("10-29+14:02Z+Bot+list:+HTTP+503+(x),+y;+a%26b%3Dc%2Bd+%231+100%25+%C3%BC%0A"));
        assertEquals(text,URLDecoder.decode(encoded,"UTF-8"));
    }

    @Test public void aFullLogKeepsTheNewestLinesThatFit() throws Exception {
        String log="";
        for(int i=1;i<=15;i++)log=ProblemReport.add(log,String.format("10-29 14:%02dZ Call: transport_closed · 61.0 s · via phone · Voice connection dropped. Try again.",i));
        String url=ProblemReport.url("samsung SM-L340","15 (API 35)","0.6.0 (28)",true,"Wi-Fi","","Call dropped: Voice connection dropped. Try again.",log,false);
        assertTrue(url.length()<=ProblemReport.LINK_BUDGET);
        String decoded=URLDecoder.decode(url,"UTF-8");
        assertTrue("newest line kept",decoded.contains("14:15Z"));
        assertFalse("oldest line dropped",decoded.contains("14:01Z"));
        assertTrue(decoded.endsWith("What happened?\n"));
    }

    @Test public void aTraceNamesTheLimitAndTheAppCodeItPassedThrough() {
        Exception failure=null;
        try{GrokVault.readBounded(new java.io.ByteArrayInputStream(new byte[2*1024*1024]),1024*1024);}catch(Exception tooLarge){failure=tooLarge;}
        GrokCore.Failure wrapped=new GrokCore.Failure("Grok request could not finish.",false);wrapped.initCause(failure);
        String trace=ProblemReport.trace(wrapped);
        assertTrue(trace,trace.startsWith("ResponseTooLarge (reply over 1 MB) at GrokVault.readBounded:"));
        assertTrue(trace,trace.contains(" ← ProblemReportTest.aTraceNamesTheLimitAndTheAppCodeItPassedThrough:"));
        assertTrue(trace,trace.endsWith(" · via Failure"));
    }

    @Test public void aTraceNeverCarriesANetworkMessage() {
        String trace=ProblemReport.trace(new java.net.SocketTimeoutException("timeout talking to private-host.example.com"));
        assertTrue(trace,trace.startsWith("SocketTimeoutException at ProblemReportTest."));
        assertFalse(trace,trace.contains("example"));
        assertEquals("unknown",ProblemReport.trace(null));
    }

    @Test public void theReportCarriesTheLastErrorAboveTheLog() throws Exception {
        String error="11-01 16:18Z Bot list: ResponseTooLarge (reply over 1 MB) at GrokVault.readBounded:121 ← BotAccount.execute:380 · via Failure";
        String decoded=URLDecoder.decode(ProblemReport.url("samsung SM-L300","14 (API 34)","0.6.2 (33)",true,"Wi-Fi","","Bot list: Bots aren’t available yet",error,
            "11-01 16:18Z Bot list: ResponseTooLarge · after 5.4 s · Wi-Fi 30 Mbps",false),"UTF-8");
        assertTrue(decoded,decoded.contains("\nError: 11-01 16:18Z Bot list: ResponseTooLarge (reply over 1 MB) at GrokVault.readBounded:121 < BotAccount.execute:380 - via Failure\nLog (UTC, newest first):\n11-01 16:18Z Bot list"));
    }
    @Test public void clintsReportKeepsItsNewestLogLineByShorteningTheTrace() throws Exception {
        // 0.6.3 (2026-11-11): a long Where and trace left no room, and the report said "Log: none".
        String where="Needs attention: Grok is still connecting or could not finish connecting. Try again shortly. No message was sent.";
        String error="11-11 20:20Z Account: Failure at GrokAuthCore.failure:488 ← GrokAuthCore.bootstrapBusy:449 ← GrokAuthCore.pendingRoot:445 ← GrokAuthCore.poll:101";
        String state="0 Bots (none loaded) · main Bot unknown · loaded never · reply alerts off · sign-in ready, team no, token 86399 min · battery 81%";
        String log="11-11 20:20Z Bot sandbox start: HTTP 401 unauthenticated sand_client_update_required · 0.8 s · Wi-Fi 16 Mbps\n11-11 20:19Z Sign-in check: ok\n";
        String url=ProblemReport.url("samsung SM-L715U","17 (API 37)","0.6.3 (35)",false,"Wi-Fi, est. 16 Mbps down, 12 Mbps up, signal -62 dBm",state,where,error,log,true,"");
        assertTrue(url.length()<=ProblemReport.LINK_BUDGET);
        String body=URLDecoder.decode(url.substring(url.indexOf("&body=")+6).replace("+","%2B"),"UTF-8");
        assertTrue(body,body.contains("Log (UTC, newest first):\n11-11 20:20Z Bot sandbox start: HTTP 401 unauthenticated sand_client_update_required"));
        assertTrue(body,body.contains("\nError: 11-11 20:20Z Account: Failure at GrokAuthCore.failure:488"));
    }
}
