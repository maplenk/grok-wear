package si.zya.grokwear;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Settling an unanswered send with GetGrokBotSendStatus, on Grok Bot Android 1.25.0's clock; all values synthetic. */
public class SendStatusTest {
    private static final long T=1_800_000_000_000L;
    private static SendStatus.Check check()throws Exception {return new SendStatus.Check(new JSONObject().put("agentId","A").put("messageId","n"),T);}

    @Test public void grokAnswersAcceptedRejectedNotFoundOrNotYet()throws Exception {
        assertEquals(SendStatus.SENT,SendStatus.answer(new JSONObject().put("status","GROK_BOT_SEND_STATUS_ACCEPTED")));
        assertEquals(SendStatus.REJECTED,SendStatus.answer(new JSONObject().put("status","GROK_BOT_SEND_STATUS_REJECTED")));
        assertEquals(SendStatus.NOT_FOUND,SendStatus.answer(new JSONObject().put("status","GROK_BOT_SEND_STATUS_NOT_FOUND")));
        for(Object other:new Object[]{"GROK_BOT_SEND_STATUS_PENDING","GROK_BOT_SEND_STATUS_UNSPECIFIED","",2,JSONObject.NULL})
            assertEquals(String.valueOf(other),SendStatus.WAITING,SendStatus.answer(new JSONObject().put("status",other)));
        assertEquals(SendStatus.WAITING,SendStatus.answer(new JSONObject()));assertEquals(SendStatus.WAITING,SendStatus.answer(null));
    }
    @Test public void asksEveryTwoSecondsOneAtATime()throws Exception {
        SendStatus.Check c=check();
        assertEquals("2 s after the send failed",SendStatus.WAITING,c.next(T+1999));
        assertEquals(SendStatus.ASK,c.next(T+2000));assertEquals("one ask at a time",SendStatus.WAITING,c.next(T+9000));
        assertEquals(SendStatus.WAITING,c.answered(SendStatus.WAITING,T+3000));
        assertEquals("2 s after the last answer",SendStatus.WAITING,c.next(T+4999));assertEquals(SendStatus.ASK,c.next(T+5000));
        assertTrue(c.open(T+5000));
    }
    @Test public void anAnswerSettlesIt()throws Exception {
        for(String answer:new String[]{SendStatus.SENT,SendStatus.REJECTED,SendStatus.NOT_FOUND}){
            SendStatus.Check c=check();c.next(T+2000);
            assertEquals(answer,c.answered(answer,T+2500));assertFalse(c.open(T+2500));assertEquals(SendStatus.GIVE_UP,c.next(T+10_000));
        }
        SendStatus.Check late=check();late.next(T+59_000);
        assertEquals("an answer after the minute still counts",SendStatus.SENT,late.answered(SendStatus.SENT,T+61_000));
    }
    @Test public void givesUpAfterAMinuteOrThreeAsksInARowThatDidNotGetThrough()throws Exception {
        SendStatus.Check c=check();long now=T;
        for(int i=0;i<2;i++){now+=2000;assertEquals(SendStatus.ASK,c.next(now));assertEquals(SendStatus.WAITING,c.answered(SendStatus.UNREACHED,now));}
        now+=2000;c.next(now);assertEquals("an answer starts the count again",SendStatus.WAITING,c.answered(SendStatus.WAITING,now));
        for(int i=0;i<2;i++){now+=2000;c.next(now);assertEquals(SendStatus.WAITING,c.answered(SendStatus.UNREACHED,now));}
        now+=2000;c.next(now);assertEquals("the third in a row",SendStatus.GIVE_UP,c.answered(SendStatus.UNREACHED,now));
        assertFalse(c.open(now));assertEquals(SendStatus.GIVE_UP,c.next(now+2000));

        SendStatus.Check slow=check();slow.next(T+58_000);
        assertEquals("still waiting at the minute",SendStatus.GIVE_UP,slow.answered(SendStatus.WAITING,T+60_000));
        SendStatus.Check idle=check();assertTrue(idle.open(T+59_999));
        assertEquals("nobody asked within the minute",SendStatus.GIVE_UP,idle.next(T+60_000));assertFalse(idle.open(T+60_000));
        SendStatus.Check refused=check();refused.next(T+2000);
        assertEquals("a refused ask leaves it to the chat",SendStatus.GIVE_UP,refused.answered(SendStatus.GIVE_UP,T+2100));
    }
    @Test public void checksAreKeptByNonceAndBounded()throws Exception {
        SendStatus all=new SendStatus();JSONObject ask=new JSONObject().put("agentId","A").put("messageId","n1");
        all.start("n1",ask,T);assertSame(ask,all.get("n1").request);assertNull(all.get("n2"));assertNull(all.get(null));
        all.start("n1",ask,T+5000);assertEquals("a retry starts the clock again",T+5000,all.get("n1").failedAt);
        all.end("n1");assertNull(all.get("n1"));all.start("",ask,T);assertNull(all.get(""));
        for(int i=0;i<=SendStatus.MAX;i++)all.start("m"+i,ask,T);
        assertNull("the oldest went first",all.get("m0"));assertNotNull(all.get("m"+SendStatus.MAX));
    }
}
