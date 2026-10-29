package si.zya.grokwear;

import static org.junit.Assert.*;

import java.net.URLDecoder;
import org.junit.Test;

public class ProblemReportTest {
    @Test public void keepsTheNewestFifteenOnOneLineEach() {
        String recent="";
        for(int i=1;i<=17;i++)recent=ProblemReport.add(recent,"event "+i+"\nsecond line");
        String[] lines=recent.split("\n");
        assertEquals(15,lines.length);
        assertEquals("event 17 second line",lines[0]);
        assertEquals("event 3 second line",lines[14]);
    }

    @Test public void describesWithoutNetworkMessages() {
        assertEquals("HTTP 503",ProblemReport.describe(new GrokCore.Failure("Grok request failed (HTTP 503).",false,"",503)));
        assertEquals("HTTP 403 permission_denied",ProblemReport.describe(new GrokCore.Failure("x",true,"permission_denied",403)));
        GrokCore.Failure lost=new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",false);
        lost.initCause(new java.net.UnknownHostException("Unable to resolve host \"secret.cursorvm.com\""));
        assertEquals("UnknownHostException",ProblemReport.describe(lost));
        GrokCore.Failure tooLarge=new GrokCore.Failure("Grok connection failed. Check sign-in before trying again.",false);
        tooLarge.initCause(new GrokVault.ResponseTooLarge());
        assertEquals("ResponseTooLarge",ProblemReport.describe(tooLarge));
        assertEquals("Bot list format is not recognized. No Bot was selected.",
            ProblemReport.describe(new GrokCore.Failure("Bot list format is not recognized. No Bot was selected.",true)));
        assertEquals("SocketTimeoutException",ProblemReport.describe(new java.net.SocketTimeoutException("timeout to 10.0.0.1")));
        // On screen only the short forms: the app's own refusals already say what happened.
        assertEquals("",ProblemReport.code(new GrokCore.Failure("Bot list format is not recognized. No Bot was selected.",true)));
        assertEquals("ResponseTooLarge",ProblemReport.code(tooLarge));
    }

    @Test public void issueLinkCarriesTheDetailsAndTheLog() throws Exception {
        String url=ProblemReport.url("Google Pixel Watch 5","16 (API 36)","0.6.0 (28)",false,"via phone",
            "10-29 14:02Z Bot list: HTTP 503 · after 0.8 s · via phone\n10-29 14:01Z Bot list: 7 in 1.2 s · Wi-Fi");
        assertTrue(url.startsWith("https://github.com/maplenk/grok-wear/issues/new?title="));
        String decoded=URLDecoder.decode(url,"UTF-8");
        assertTrue(decoded.contains("title=Problem on Google Pixel Watch 5&body="));
        assertTrue(decoded.contains("Watch: Google Pixel Watch 5\nAndroid: 16 (API 36)\nGrok Wear: 0.6.0 (28)\nAssistant button: off\nNetwork: via phone\n"
            +"Log (UTC, newest first):\n10-29 14:02Z Bot list: HTTP 503 - after 0.8 s - via phone\n10-29 14:01Z Bot list: 7 in 1.2 s - Wi-Fi\n"));
        assertTrue(decoded.endsWith("What happened?\n"));
        assertTrue(ProblemReport.url("a","b","c",false,"Wi-Fi","").contains("none"));
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
        String url=ProblemReport.url("samsung SM-L340","15 (API 35)","0.6.0 (28)",true,"Wi-Fi",log);
        assertTrue(url.length()<=ProblemReport.LINK_BUDGET);
        String decoded=URLDecoder.decode(url,"UTF-8");
        assertTrue("newest line kept",decoded.contains("14:15Z"));
        assertFalse("oldest line dropped",decoded.contains("14:01Z"));
        assertTrue(decoded.endsWith("What happened?\n"));
    }
}
