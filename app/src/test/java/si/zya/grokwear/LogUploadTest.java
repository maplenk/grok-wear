package si.zya.grokwear;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;
import org.junit.Test;

public class LogUploadTest {
    @Test public void dsnBecomesTheEnvelopeEndpointAndPublicKey(){
        LogUpload.Dsn dsn=LogUpload.parse("https://e12d836b15bb49d7bbf99e64295d995b@o123.ingest.us.sentry.io/4507");
        assertEquals("https://o123.ingest.us.sentry.io/api/4507/envelope/",dsn.endpoint);assertEquals("e12d836b15bb49d7bbf99e64295d995b",dsn.key);
        assertEquals("https://sentry.example.com:8443/glitch/api/1/envelope/",LogUpload.parse("https://abcdef0123:secret@sentry.example.com:8443/glitch/1").endpoint);
        assertNull(LogUpload.parse(""));assertNull(LogUpload.parse(null));
        assertNull(LogUpload.parse("http://e12d836b15bb49d7@o1.ingest.sentry.io/1"));
        assertNull(LogUpload.parse("https://o1.ingest.sentry.io/1"));
        assertNull(LogUpload.parse("https://e12d836b15bb49d7@o1.ingest.sentry.io/1?x=1"));
        assertNull(LogUpload.parse("https://e12d836b15bb49d7@o1.ingest.sentry.io/"));
    }

    @Test public void theEnvelopeHasExplicitLengthsAnEventAndTheLogAsText()throws Exception {
        String id=LogUpload.newEventId();assertTrue(id.matches("[0-9a-f]{32}"));assertEquals(8,LogUpload.shortId(id).length());
        Map<String,String> tags=new TreeMap<>();tags.put("log_id",LogUpload.shortId(id));tags.put("watch","samsung SM-R920");
        String text="Log ID: x\nLog (UTC, newest first):\n11-03 14:33Z Bot sandbox start: HTTP 401 · 0.4 s · via phone\n";
        byte[] envelope=LogUpload.envelope(id,1_800_000_000_123L,"Needs attention: Grok Bot needs a plan",tags,"grok-wear@0.6.3+35",text);
        String all=new String(envelope,StandardCharsets.UTF_8);
        int first=all.indexOf('\n');
        JSONObject header=new JSONObject(all.substring(0,first));
        assertEquals(id,header.getString("event_id"));assertTrue(header.getString("sent_at").endsWith("Z"));
        int second=all.indexOf('\n',first+1);
        JSONObject eventItem=new JSONObject(all.substring(first+1,second));
        assertEquals("event",eventItem.getString("type"));
        // Lengths are byte counts of each payload.
        byte[] rest=all.substring(second+1).getBytes(StandardCharsets.UTF_8);
        String eventJson=new String(rest,0,eventItem.getInt("length"),StandardCharsets.UTF_8);
        JSONObject event=new JSONObject(eventJson);
        assertEquals(id,event.getString("event_id"));assertEquals("info",event.getString("level"));assertEquals("java",event.getString("platform"));
        assertEquals("Needs attention: Grok Bot needs a plan",event.getJSONObject("message").getString("formatted"));
        assertEquals("samsung SM-R920",event.getJSONObject("tags").getString("watch"));
        assertFalse("no user, so no IP field",event.has("user"));assertFalse(event.has("server_name"));
        assertEquals("Sentry must not take the IP (and a location) from the connection","never",event.getJSONObject("sdk").getJSONObject("settings").getString("infer_ip"));
        String afterEvent=new String(rest,eventItem.getInt("length")+1,rest.length-eventItem.getInt("length")-1,StandardCharsets.UTF_8);
        int itemEnd=afterEvent.indexOf('\n');
        JSONObject attachment=new JSONObject(afterEvent.substring(0,itemEnd));
        assertEquals("attachment",attachment.getString("type"));assertEquals("grok-wear-log.txt",attachment.getString("filename"));assertEquals("text/plain",attachment.getString("content_type"));
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length,attachment.getInt("length"));
        assertEquals(text+"\n",afterEvent.substring(itemEnd+1));
    }

    @Test public void theAuthHeaderFollowsProtocolSeven(){
        assertEquals("Sentry sentry_version=7, sentry_client=grok-wear/"+BuildConfig.VERSION_NAME+", sentry_key=abc123def456",LogUpload.authHeader("abc123def456"));
    }
}
