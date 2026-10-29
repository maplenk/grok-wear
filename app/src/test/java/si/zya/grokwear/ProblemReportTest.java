package si.zya.grokwear;

import static org.junit.Assert.*;

import java.net.URLDecoder;
import org.junit.Test;

public class ProblemReportTest {
    @Test public void keepsTheNewestThreeOnOneLineEach() {
        String recent="";
        for(int i=1;i<=4;i++)recent=ProblemReport.add(recent,"error "+i+"\nsecond line");
        assertEquals("error 4 second line\nerror 3 second line\nerror 2 second line",recent);
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

    @Test public void issueLinkCarriesTheDetailsAndStaysShort() throws Exception {
        String url=ProblemReport.url("Google Pixel Watch 5","16 (API 36)","0.6.0 (27)",false,"10-29 14:02Z Bot list: HTTP 503");
        assertTrue(url.startsWith("https://github.com/maplenk/grok-wear/issues/new?title="));
        String decoded=URLDecoder.decode(url,"UTF-8");
        assertTrue(decoded.contains("title=Problem on Google Pixel Watch 5&body="));
        assertTrue(decoded.contains("Watch: Google Pixel Watch 5\nAndroid: 16 (API 36)\nGrok Wear: 0.6.0 (27)\nAssistant button: off\nRecent errors (UTC):\n10-29 14:02Z Bot list: HTTP 503\n"));
        assertTrue(decoded.endsWith("What happened?\n"));
        // Three long errors still fit a QR code a phone reads off a watch screen.
        String recent="";for(int i=0;i<3;i++)recent=ProblemReport.add(recent,"10-29 14:02Z Call stopped: "+"x".repeat(200));
        assertTrue(ProblemReport.url("samsung SM-L340","15 (API 35)","0.6.0 (27)",true,recent).length()<700);
        assertTrue(ProblemReport.url("a","b","c",false,"").contains("none"));
    }
}
