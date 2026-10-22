package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class VoiceStartupTest {
    @Test public void allThreeBranchesStartBeforeAnyCanFinish()throws Exception {
        VoiceStartup startup=new VoiceStartup();
        CountDownLatch entered=new CountDownLatch(3),release=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        JSONObject binding=new JSONObject().put("accountKey","account").put("authGeneration","generation").put("agentId","bot");
        JSONArray turns=new JSONArray().put(new JSONObject().put("speaker","user").put("text","remember me"));
        try {
            Future<VoiceStartup.Result> result=caller.submit(()->startup.prepare(
                ()->{entered.countDown();release.await();return new HistoryCoordinator.Context(binding,turns);},
                ()->{entered.countDown();release.await();return new JSONObject().put("instructions","harness");},
                ()->{entered.countDown();release.await();return new JSONObject().put("clientSecret","secret");}));
            assertTrue("History, harness and mint must overlap",entered.await(5,TimeUnit.SECONDS));
            assertFalse(result.isDone());release.countDown();
            VoiceStartup.Result ready=result.get(5,TimeUnit.SECONDS);
            assertEquals("remember me",ready.history.turns().getJSONObject(0).getString("text"));
            assertEquals("harness",ready.plan.getString("instructions"));
            assertEquals("secret",ready.credential.getString("clientSecret"));
        }finally{release.countDown();startup.close();caller.shutdownNow();}
    }
    @Test public void transportStartsWhileFreshHistoryIsBlocked()throws Exception {
        VoiceStartup startup=new VoiceStartup();CountDownLatch history=new CountDownLatch(1),release=new CountDownLatch(1),transport=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            Future<VoiceStartup.Result> result=caller.submit(()->startup.prepare(
                ()->{history.countDown();release.await();return new HistoryCoordinator.Context(new JSONObject().put("accountKey","account").put("authGeneration","generation").put("agentId","bot"),new JSONArray());},
                ()->{history.await();return new JSONObject().put("instructions","test");},JSONObject::new,
                (plan,credential)->transport.countDown()));
            assertTrue(transport.await(3,TimeUnit.SECONDS));assertFalse("Fresh history still blocks result",result.isDone());
            release.countDown();assertNotNull(result.get(3,TimeUnit.SECONDS).history);
        }finally{release.countDown();startup.close();caller.shutdownNow();}
    }
    @Test public void transportCallbackFailureCancelsPendingHistory()throws Exception {
        VoiceStartup startup=new VoiceStartup();CountDownLatch entered=new CountDownLatch(1),exited=new CountDownLatch(1);
        try {
            try{startup.prepare(()->{entered.countDown();try{new CountDownLatch(1).await();return null;}finally{exited.countDown();}},
                ()->{entered.await();return new JSONObject();},JSONObject::new,
                (plan,credential)->{throw new IllegalStateException("fixture transport failed");});fail();}
            catch(IllegalStateException expected){}
            assertTrue(exited.await(3,TimeUnit.SECONDS));
        }finally{startup.close();}
    }
    @Test public void cancelledPipelineCannotReturnPreparedCredentials()throws Exception {
        VoiceStartup startup=new VoiceStartup();CountDownLatch entered=new CountDownLatch(3),release=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            Future<VoiceStartup.Result> result=caller.submit(()->startup.prepare(
                ()->{entered.countDown();release.await();return null;},
                ()->{entered.countDown();release.await();return new JSONObject();},
                ()->{entered.countDown();release.await();return new JSONObject();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));startup.close();release.countDown();
            try{result.get(5,TimeUnit.SECONDS);fail("Cancelled setup returned");}catch(ExecutionException expected){}
        }finally{release.countDown();startup.close();caller.shutdownNow();}
    }
    @Test public void failedHarnessDoesNotWaitForBlockedHistory()throws Exception {
        VoiceStartup startup=new VoiceStartup();CountDownLatch historyEntered=new CountDownLatch(1),release=new CountDownLatch(1),historyExited=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            Future<VoiceStartup.Result> result=caller.submit(()->startup.prepare(
                ()->{historyEntered.countDown();try{release.await();return null;}finally{historyExited.countDown();}},
                ()->{historyEntered.await();throw new IllegalStateException("fixture failure");},JSONObject::new));
            try{result.get(5,TimeUnit.SECONDS);fail("Failure must propagate");}
            catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
            assertEquals("History was never released by the caller",1,release.getCount());
            assertTrue("Failed sibling cancels history",historyExited.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();startup.close();caller.shutdownNow();}
    }
    @Test public void cancellationRejectsBranchThatIgnoresInterruptUntilReleased()throws Exception {
        VoiceStartup startup=new VoiceStartup();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            Future<VoiceStartup.Result> result=caller.submit(()->startup.prepare(
                ()->{entered.countDown();try{while(true){try{release.await();break;}catch(InterruptedException ignored){}}return null;}finally{exited.countDown();}},JSONObject::new,JSONObject::new));
            assertTrue(entered.await(5,TimeUnit.SECONDS));startup.close();
            try{result.get(5,TimeUnit.SECONDS);fail("Cancelled setup returned");}catch(ExecutionException expected){}
            assertEquals(1,release.getCount());release.countDown();assertTrue(exited.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();startup.close();caller.shutdownNow();}
    }
}
