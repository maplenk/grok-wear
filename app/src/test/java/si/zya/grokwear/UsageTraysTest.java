package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The process's usage-limit notices and their reset line; all values synthetic. */
public class UsageTraysTest {
    private static final String ME="account/1";
    private static BotEvents.Event pushed(String id,String bot,long createdAt)throws Exception {return BotEvents.usage(id,bot,createdAt,"Usage limit reached","");}
    private static BotEvents.Event event(String type,String id)throws Exception {
        JSONObject value=new JSONObject().put("type",type);if(id!=null)value.put("id",id);return new BotEvents.Event("tray","",value);
    }

    @Test public void pushedAddsDismissedRemovesAndClearedEmpties()throws Exception {
        UsageTrays trays=new UsageTrays();
        assertTrue(trays.apply(ME,pushed("tray_1","bot-1",100)));
        assertFalse("the same notice again is no change",trays.apply(ME,pushed("tray_1","bot-1",100)));
        assertTrue("an update is",trays.apply(ME,pushed("tray_1","bot-1",200)));
        assertEquals(200,trays.latest(ME,"bot-1").getLong("createdAt"));
        assertNull(trays.latest(ME,"bot-2"));
        assertTrue(trays.apply(ME,pushed("server-turn:9","bot-1",300)));assertTrue(trays.apply(ME,pushed("tray_2","bot-2",50)));
        assertEquals("the Bot's newest","server-turn:9",trays.latest(ME,"bot-1").getString("id"));
        assertTrue(trays.apply(ME,event("dismissed","server-turn:9")));assertEquals("tray_1",trays.latest(ME,"bot-1").getString("id"));
        assertFalse("an unknown id is no change",trays.apply(ME,event("dismissed","tray_x")));
        assertTrue(trays.apply(ME,event("cleared",null)));assertNull(trays.latest(ME,"bot-1"));assertNull(trays.latest(ME,"bot-2"));
        assertFalse("nothing left to clear",trays.apply(ME,event("cleared",null)));
    }
    private static JSONObject tray(String id,String bot,String errorKind)throws Exception {
        return new JSONObject().put("kind","error").put("id",id).put("agentId",bot).put("createdAt",500).put("title","Usage limit reached").put("errorKind",errorKind);
    }
    @Test public void getTraysOnConnectBringsMissedNoticesAndDropsGoneOnes()throws Exception {
        UsageTrays trays=new UsageTrays();
        trays.apply(ME,pushed("tray_old","bot-1",100));trays.apply(ME,pushed("server-turn:9","bot-2",100));
        Set<String> held=trays.gatewayIds(ME);assertEquals(Set.of("tray_old"),held);
        // Pushed while getTrays was on its way: not in its reply, yet not gone.
        trays.apply(ME,pushed("tray_new","bot-4",100));
        JSONArray listed=new JSONArray().put(tray("tray_missed","bot-3","usage_limit")).put(tray("tray_other","bot-1","provider_overloaded"))
            .put("not a tray").put(new JSONObject().put("kind","error"));
        List<BotEvents.Event> changes=trays.reconcile(ME,listed,held);
        assertEquals(2,changes.size());
        assertEquals("pushed",changes.get(0).value.getString("type"));assertEquals("tray_missed",changes.get(0).value.getJSONObject("tray").getString("id"));
        assertEquals("dismissed",changes.get(1).value.getString("type"));assertEquals("tray_old",changes.get(1).value.getString("id"));
        assertEquals("missed while offline",500,trays.latest(ME,"bot-3").getLong("createdAt"));
        assertNull("no longer listed, and only usage-limit trays count",trays.latest(ME,"bot-1"));
        assertEquals("the server's own notice stays","server-turn:9",trays.latest(ME,"bot-2").getString("id"));
        assertNotNull("pushed meanwhile, stays",trays.latest(ME,"bot-4"));
        // The next connect's read no longer lists it: gone then. The same read again changes nothing.
        changes=trays.reconcile(ME,listed,trays.gatewayIds(ME));
        assertEquals(1,changes.size());assertEquals("tray_new",changes.get(0).value.getString("id"));assertNull(trays.latest(ME,"bot-4"));
        assertTrue(trays.reconcile(ME,listed,trays.gatewayIds(ME)).isEmpty());assertNotNull(trays.latest(ME,"bot-3"));
    }
    @Test public void getTraysForAnotherAccountStartsItsOwnNotices()throws Exception {
        UsageTrays trays=new UsageTrays();trays.apply(ME,pushed("tray_1","bot-1",100));
        assertTrue("another account holds nothing",trays.gatewayIds("account/2").isEmpty());
        assertEquals(1,trays.reconcile("account/2",new JSONArray().put(tray("tray_2","bot-2","usage_limit")),Set.of()).size());
        assertNull(trays.latest(ME,"bot-1"));assertNotNull(trays.latest("account/2","bot-2"));
        assertTrue("no account, nothing",trays.reconcile("",new JSONArray().put(tray("tray_3","bot-3","usage_limit")),Set.of()).isEmpty());
        assertTrue(trays.reconcile("account/2",null,Set.of()).isEmpty());
    }
    @Test public void noticesBelongToOneAccount()throws Exception {
        UsageTrays trays=new UsageTrays();trays.apply(ME,pushed("tray_1","bot-1",100));
        assertNull("another account sees nothing",trays.latest("account/2","bot-1"));
        assertTrue(trays.apply("account/2",pushed("tray_2","bot-2",100)));
        assertNull("its notices replaced the old account's",trays.latest(ME,"bot-1"));assertNull(trays.latest("account/2","bot-1"));
        assertNotNull(trays.latest("account/2","bot-2"));
        assertFalse("no account, no change",trays.apply("",pushed("tray_3","bot-3",1)));
        assertFalse("not a tray event",trays.apply("account/2",new BotEvents.Event("bot","bot-2",new JSONObject())));
        trays.clear();assertNull(trays.latest("account/2","bot-2"));
    }
    @Test public void aCopyComesBackAndTheStoreStaysBounded()throws Exception {
        UsageTrays trays=new UsageTrays();trays.apply(ME,pushed("tray_1","bot-1",100));
        trays.latest(ME,"bot-1").put("agentId","bot-9");assertNotNull("changing a copy changes nothing",trays.latest(ME,"bot-1"));
        for(int i=0;i<UsageTrays.MAX;i++)trays.apply(ME,pushed("more_"+i,"bot-x",1000+i));
        assertNull("the oldest went first",trays.latest(ME,"bot-1"));assertEquals("more_"+(UsageTrays.MAX-1),trays.latest(ME,"bot-x").getString("id"));
    }
    @Test public void theResetTimeIsAskedOncePerNotice()throws Exception {
        UsageTrays trays=new UsageTrays();
        assertFalse("no such notice",trays.claimReset("tray_1"));
        trays.apply(ME,pushed("tray_1","bot-1",100));
        assertTrue(trays.claimReset("tray_1"));assertFalse("once",trays.claimReset("tray_1"));assertEquals(0,trays.resetAt("tray_1"));
        trays.reset("tray_1",5000);assertEquals(5000,trays.resetAt("tray_1"));
        trays.apply(ME,pushed("tray_1","bot-1",100));assertFalse("pushed again, still once",trays.claimReset("tray_1"));assertEquals(5000,trays.resetAt("tray_1"));
        trays.apply(ME,event("dismissed","tray_1"));assertEquals(0,trays.resetAt("tray_1"));
        trays.reset("tray_1",7000);assertEquals("a gone notice keeps nothing",0,trays.resetAt("tray_1"));
    }
    @Test public void theResetTimeComesOnlyFromAReachedPersonalLimit()throws Exception {
        JSONObject status=new JSONObject().put("usagePercent",100).put("nextResetTimestampUtc","2026-11-16T00:00:00Z").put("hasNonZeroIncludedLimit",true);
        assertEquals(ZonedDateTime.of(2026,11,16,0,0,0,0,ZoneId.of("UTC")).toInstant().toEpochMilli(),UsageTrays.resetMs(status));
        assertEquals("fractions and offsets parse",ZonedDateTime.of(2026,11,16,0,0,0,500_000_000,ZoneId.of("UTC")).toInstant().toEpochMilli(),
            UsageTrays.resetMs(new JSONObject(status.toString()).put("nextResetTimestampUtc","2026-11-16T01:00:00.5+01:00")));
        assertEquals("over the limit",UsageTrays.resetMs(status),UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent",104.5)));
        assertEquals("a number sent as text",UsageTrays.resetMs(status),UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent","100")));
        assertEquals("under the limit",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent",99.9)));
        assertEquals("pooled",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("usesPooledEnterpriseAllowance",true)));
        assertEquals("not a number",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent","NaN")));
        assertEquals(0,UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent","Infinity")));
        assertEquals(0,UsageTrays.resetMs(new JSONObject(status.toString()).put("usagePercent","lots")));
        assertEquals("no percent at all",0,UsageTrays.resetMs(new JSONObject().put("nextResetTimestampUtc","2026-11-16T00:00:00Z")));
        assertEquals("unreadable time",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("nextResetTimestampUtc","next Monday")));
        assertEquals("no time",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("nextResetTimestampUtc",JSONObject.NULL)));
        assertEquals("before 1970",0,UsageTrays.resetMs(new JSONObject(status.toString()).put("nextResetTimestampUtc","1969-12-31T00:00:00Z")));
        assertEquals(0,UsageTrays.resetMs(new JSONObject()));assertEquals(0,UsageTrays.resetMs(null));
    }
    @Test public void theResetLineNamesTheDayAndTimeAsTheWatchShowsTime(){
        ZoneId zone=ZoneId.of("America/New_York");
        long now=ZonedDateTime.of(2026,11,12,8,0,0,0,zone).toInstant().toEpochMilli(); // a Thursday
        long later=ZonedDateTime.of(2026,11,12,21,5,0,0,zone).toInstant().toEpochMilli();
        assertEquals("Resets today at 9:05 PM",UsageTrays.resetLine(later,now,zone,Locale.US,false));
        assertEquals("Resets today at 21:05",UsageTrays.resetLine(later,now,zone,Locale.US,true));
        long tuesday=ZonedDateTime.of(2026,11,17,9,0,0,0,zone).toInstant().toEpochMilli();
        assertEquals("Resets Tue at 9:00 AM",UsageTrays.resetLine(tuesday,now,zone,Locale.US,false));
        assertEquals("Resets Tue at 9:00",UsageTrays.resetLine(tuesday,now,zone,Locale.US,true));
        long nextWeek=ZonedDateTime.of(2026,11,19,0,30,0,0,zone).toInstant().toEpochMilli();
        assertEquals("a week or more out names the date","Resets Nov 19 at 12:30 AM",UsageTrays.resetLine(nextWeek,now,zone,Locale.US,false));
        // The same moment in another zone can be another day.
        long utcMidnight=ZonedDateTime.of(2026,11,13,1,0,0,0,ZoneId.of("UTC")).toInstant().toEpochMilli();
        assertEquals("Resets today at 8:00 PM",UsageTrays.resetLine(utcMidnight,now,zone,Locale.US,false));
        assertEquals("Resets Fri at 1:00",UsageTrays.resetLine(utcMidnight,now,ZoneId.of("UTC"),Locale.US,true));
        assertEquals("passed","",UsageTrays.resetLine(now,now,zone,Locale.US,false));
        assertEquals("",UsageTrays.resetLine(now-1,now,zone,Locale.US,false));
    }
}
