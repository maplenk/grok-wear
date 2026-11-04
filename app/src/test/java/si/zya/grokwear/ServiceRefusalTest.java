package si.zya.grokwear;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class ServiceRefusalTest {
    private static byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}

    @Test public void readsTheCodeHintAndScopeButNeverTheMessage(){
        ServiceRefusal why=ServiceRefusal.parse(401,"SAND_CLIENT_UPDATE_REQUIRED",utf8("{\"code\":\"unauthenticated\",\"message\":\"private@example.com\","
                +"\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"value\":\"\",\"debug\":{\"error\":\"ERROR_UNAUTHORIZED\",\"details\":{\"title\":\"t\",\"additionalInfo\":{\"sandUsageScopeError\":\"stale_team\"}}}}]}"));
        assertEquals(401,why.status);assertEquals("unauthenticated",why.code);assertEquals("SAND_CLIENT_UPDATE_REQUIRED",why.hint);assertEquals("stale_team",why.scope);
        assertEquals("HTTP 401 unauthenticated sand_client_update_required stale_team",why.summary());
        assertFalse(why.summary().contains("example"));
        assertTrue(why.staleTeam());assertFalse(why.accessRefusal());
    }

    @Test public void findsTheScopeInTheBinaryDetailWhenThereIsNoDebugForm()throws Exception {
        // ErrorDetails { 1: error enum, 2: CustomErrorDetails { 1: "title", 9: map entry { 1: key, 2: value } } }
        byte[] entry=message(field(1,utf8("sandUsageScopeError")),field(2,utf8("stale_team")));
        byte[] custom=message(field(1,utf8("Team changed")),field(9,entry));
        byte[] details=message(new byte[]{0x08,0x2a},field(2,custom));
        String value=Base64.getEncoder().withoutPadding().encodeToString(details);
        ServiceRefusal why=ServiceRefusal.parse(401,null,utf8("{\"code\":\"unauthenticated\",\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"value\":\""+value+"\"}]}"));
        assertEquals("stale_team",why.scope);assertTrue(why.staleTeam());
        // Another key in the same shape is not the scope.
        byte[] other=message(field(9,message(field(1,utf8("somethingElse")),field(2,utf8("stale_team")))));
        assertEquals("",ServiceRefusal.parse(401,null,utf8("{\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"value\":\""+Base64.getEncoder().encodeToString(other)+"\"}]}")).scope);
    }

    @Test public void unexpectedValuesAndBodiesAreDropped(){
        ServiceRefusal odd=ServiceRefusal.parse(403,"bad hint\nwith newline",utf8("{\"code\":\"Not A Code\",\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"debug\":{\"details\":{\"additionalInfo\":{\"sandUsageScopeError\":\"has spaces and a very long value that goes on\"}}}}]}"));
        assertEquals("",odd.code);assertEquals("",odd.hint);assertEquals("",odd.scope);assertEquals("HTTP 403",odd.summary());
        assertTrue(odd.accessRefusal());assertFalse(odd.staleTeam());
        ServiceRefusal html=ServiceRefusal.parse(401,null,utf8("<html>proxy error</html>"));
        assertEquals("HTTP 401",html.summary());assertTrue(html.accessRefusal());
        assertEquals("HTTP 502",ServiceRefusal.parse(502,null,null).summary());
        assertFalse(ServiceRefusal.parse(502,null,null).accessRefusal());
        // A truncated protobuf can't loop or throw.
        assertNull(ServiceRefusal.mapValue(new byte[]{0x12,0x7f,0x0a},0,3,"sandUsageScopeError",0));
        assertEquals("",ServiceRefusal.parse(401,null,utf8("{\"details\":[{\"type\":\"aiserver.v1.ErrorDetails\",\"value\":\"%%%\"}]}")).scope);
    }

    @Test public void knownHintsHaveTheirOwnWords(){
        assertTrue(new ServiceRefusal(400,"failed_precondition","SAND_CLIENT_UPDATE_REQUIRED","").hintMessage().contains("newer Grok Wear"));
        assertTrue(new ServiceRefusal(403,"permission_denied","CLOUD_AGENT_STORAGE_DISABLED","").hintMessage().contains("Privacy mode"));
        assertTrue(new ServiceRefusal(403,"permission_denied","INVALID_TEAM_ID","").hintMessage().contains("Sign in again"));
        assertEquals("",new ServiceRefusal(403,"permission_denied","SOMETHING_NEW","").hintMessage());
        assertEquals("invalid_team_id",new ServiceRefusal(403,"permission_denied","INVALID_TEAM_ID","").failureCode());
    }

    private static byte[] field(int number,byte[] payload){
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write((number<<3)|2);varint(out,payload.length);out.write(payload,0,payload.length);return out.toByteArray();
    }
    private static byte[] message(byte[]... parts){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part,0,part.length);return out.toByteArray();}
    private static void varint(ByteArrayOutputStream out,int value){while((value&~0x7f)!=0){out.write((value&0x7f)|0x80);value>>>=7;}out.write(value);}
}
