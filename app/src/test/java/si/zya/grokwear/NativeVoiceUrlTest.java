package si.zya.grokwear;

import org.junit.Test;
import okhttp3.HttpUrl;
import static org.junit.Assert.*;

/** Exercises the external minted URL trust boundary without any network calls. */
public final class NativeVoiceUrlTest {
    @Test public void preservesReturnedPathAndReplacesModel()throws Exception {
        HttpUrl url=NativeVoiceSession.voiceUrl("wss://api.x.ai/vendor/voice?keep=1&model=old","grok voice");
        assertEquals("api.x.ai",url.host());assertEquals("https",url.scheme());
        assertEquals("/vendor/voice",url.encodedPath());assertEquals("1",url.queryParameter("keep"));
        assertEquals("grok voice",url.queryParameter("model"));assertEquals(1,url.queryParameterValues("model").size());
    }
    @Test public void rejectsUntrustedDestinations()throws Exception {
        String[] denied={"ws://api.x.ai/voice","https://api.x.ai/voice","wss://evil.test/voice",
            "wss://api.x.ai.evil.test/voice","wss://api.x.ai@evil.test/voice","wss://user@api.x.ai/voice",
            "wss://api.x.ai:444/voice","wss://api.x.ai/voice#fragment","wss://127.0.0.1/voice",
            "wss://api.x.ai./voice","wss://api.x.ai%2eexample/voice"};
        for(String url:denied){try{NativeVoiceSession.voiceUrl(url,"model");fail("Accepted untrusted destination");}
            catch(IllegalArgumentException|java.net.URISyntaxException expected){}}
    }
    @Test public void permitsExplicitTlsPortAndEmptyReturnedPath()throws Exception {
        assertEquals(443,NativeVoiceSession.voiceUrl("wss://api.x.ai:443","model").port());
    }
}
