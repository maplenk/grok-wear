package si.zya.grokwear;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RosterStreamTest {
    /** Stands in for PhotoShrink: a short WebP data URL naming the size it was given. */
    private final List<String> shrunk=new ArrayList<>();
    private final RosterStream.Photos fake=value->{shrunk.add(value);return "data:image/webp;base64,"+repeat('S',40)+value.length();};
    private byte[] read(String json)throws Exception{return RosterStream.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),new long[1],fake);}
    private static String repeat(char c,int n){StringBuilder b=new StringBuilder(n);for(int i=0;i<n;i++)b.append(c);return b.toString();}
    /** A custom Bot photo as the official apps save it: a 256 px PNG, here about 200 KB as base64. */
    private static String photo(int size){return "data:image/png;base64,"+repeat('A',size-22);}
    private static String bot(int i,String photo){return "{\"id\":\"bot-"+i+"\",\"name\":\"Bot "+i+"\",\"avatarDataUrl\":\""+photo+"\",\"isGroup\":false}";}
    private static String roster(int bots,int photoSize){StringBuilder json=new StringBuilder("{\"agents\":[");
        for(int i=0;i<bots;i++)json.append(i==0?"":",").append(bot(i,photo(photoSize)));return json.append("]}").toString();}

    @Test public void ordinaryListPassesThroughUnchanged()throws Exception{
        String json="[{\"id\":\"a\\\"b\",\"name\":\"Café ☕\",\"unreadCount\":3,\"lastMessagePreview\":\"hi \\\\ there\",\"isGroup\":false}, {\"id\":\"c\",\"tags\":[\"x\",\"y\"]}]";
        long[] raw=new long[1];
        byte[] kept=RosterStream.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),raw,fake);
        assertEquals(json,new String(kept,StandardCharsets.UTF_8));
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length,raw[0]);
        assertTrue(shrunk.isEmpty());
    }
    @Test public void twentyCustomPhotosLoadWhereTheOneMegabyteCapFailed()throws Exception{
        byte[] body=roster(20,200_000).getBytes(StandardCharsets.UTF_8);
        assertThrows(GrokVault.ResponseTooLarge.class,()->GrokVault.readBounded(new ByteArrayInputStream(body),1024*1024));
        long[] raw=new long[1];
        byte[] kept=RosterStream.read(new ByteArrayInputStream(body),raw,fake);
        assertEquals(body.length,raw[0]);
        assertTrue(kept.length<5_000);
        assertEquals(20,shrunk.size());
        assertEquals(photo(200_000),shrunk.get(19));
        JSONArray bots=new JSONObject(new String(kept,StandardCharsets.UTF_8)).getJSONArray("agents");
        int budget=RosterStream.PHOTOS;
        for(int i=0;i<20;i++){JSONObject row=GrokCore.rosterRow(bots.getJSONObject(i),budget);
            assertEquals("bot-"+i,row.getString("id"));
            assertTrue("Bot "+i+" keeps its photo",row.getString("avatarDataUrl").startsWith("data:image/webp;base64,"));
            budget-=row.getString("avatarDataUrl").length();}
    }
    @Test public void unusedLongStringsAreDroppedAndPreviewsKept()throws Exception{
        String preview=repeat('p',10_000),notes=repeat('n',10_000);
        JSONArray bots=new JSONArray(new String(read("[{\"id\":\"1\",\"notes\":\""+notes+"\",\"lastMessagePreview\":\""+preview+"\",\"avatarDataUrl\":\"data:image/png;base64,AQID\"}]"),StandardCharsets.UTF_8));
        assertEquals("",bots.getJSONObject(0).getString("notes"));
        assertEquals(preview,bots.getJSONObject(0).getString("lastMessagePreview"));
        assertEquals("data:image/webp;base64,"+repeat('S',40)+"26",bots.getJSONObject(0).getString("avatarDataUrl"));
    }
    @Test public void shrunkPhotosStopAtTheirBudget()throws Exception{
        RosterStream.Photos large=value->"data:image/webp;base64,"+repeat('W',10_000);
        byte[] kept=RosterStream.read(new ByteArrayInputStream(roster(120,6_000).getBytes(StandardCharsets.UTF_8)),new long[1],large);
        JSONArray bots=new JSONObject(new String(kept,StandardCharsets.UTF_8)).getJSONArray("agents");
        int with=0;for(int i=0;i<bots.length();i++)if(!bots.getJSONObject(i).getString("avatarDataUrl").isEmpty())with++;
        // 1 MB of 10,023-character photos: 104 fit.
        assertEquals(104,with);
        assertTrue(bots.getJSONObject(104).getString("avatarDataUrl").isEmpty());
        assertEquals("Bot 119",bots.getJSONObject(119).getString("name"));
    }
    @Test public void photosOverTheServiceLimitOrUnreadableAreDropped()throws Exception{
        JSONArray bots=new JSONArray(new String(read("["+bot(1,photo(1_600_000))+"]"),StandardCharsets.UTF_8));
        assertEquals("",bots.getJSONObject(0).getString("avatarDataUrl"));
        assertTrue("never decoded",shrunk.isEmpty());
        RosterStream.Photos unreadable=value->"";
        byte[] kept=RosterStream.read(new ByteArrayInputStream(("["+bot(2,photo(9_000))+"]").getBytes(StandardCharsets.UTF_8)),new long[1],unreadable);
        assertEquals("",new JSONArray(new String(kept,StandardCharsets.UTF_8)).getJSONObject(0).getString("avatarDataUrl"));
    }
    @Test public void escapedSlashesReachTheShrinkerUnescaped()throws Exception{
        read("[{\"id\":\"1\",\"avatarDataUrl\":\"data:image\\/png;base64,ab\\/cd\"}]");
        assertEquals("data:image/png;base64,ab/cd",shrunk.get(0));
    }
    @Test public void escapedQuotesInsideADroppedStringDoNotEndIt()throws Exception{
        String tricky=repeat('a',40_000)+"\\\"},{\\\"id\\\":\\\"fake\\\""+repeat('b',40_000);
        JSONArray bots=new JSONArray(new String(read("[{\"id\":\"real\",\"blob\":\""+tricky+"\",\"name\":\"Real\"}]"),StandardCharsets.UTF_8));
        assertEquals(1,bots.length());
        assertEquals("",bots.getJSONObject(0).getString("blob"));
        assertEquals("Real",bots.getJSONObject(0).getString("name"));
    }
    @Test public void stateCarriesAcrossTinyReads()throws Exception{
        String json="[{\"id\":\"1\",\"avatarDataUrl\":\""+photo(70_000)+"\",\"name\":\"One \\\"quoted\\\"\"}]";
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        InputStream trickle=new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,3));}};
        JSONArray bots=new JSONArray(new String(RosterStream.read(trickle,new long[1],fake),StandardCharsets.UTF_8));
        assertEquals(photo(70_000),shrunk.get(0));
        assertEquals("One \"quoted\"",bots.getJSONObject(0).getString("name"));
    }
    @Test public void bothCapsStopTheRead(){
        byte[] json=("[{\"id\":\"1\",\"name\":\""+repeat('n',3000)+"\"}]").getBytes(StandardCharsets.UTF_8);
        long[] raw=new long[1];
        assertThrows(GrokVault.ResponseTooLarge.class,()->RosterStream.read(new ByteArrayInputStream(json),raw,fake,1000,1_000_000));
        assertTrue(raw[0]>1000);
        assertThrows(GrokVault.ResponseTooLarge.class,()->RosterStream.read(new ByteArrayInputStream(json),new long[1],fake,1_000_000,1000));
    }
}
