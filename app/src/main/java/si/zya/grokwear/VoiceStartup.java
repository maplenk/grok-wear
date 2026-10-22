package si.zya.grokwear;

import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent setup work overlaps; a transport callback may open the socket, never the microphone. */
final class VoiceStartup implements AutoCloseable {
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final List<Future<Part>> futures=new ArrayList<>();
    private volatile Thread joining;
    private final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
    private static final class Part {final int index;final Object value;Part(int index,Object value){this.index=index;this.value=value;}}
    static final class Result {
        final HistoryCoordinator.Context history;final JSONObject plan,credential;
        Result(HistoryCoordinator.Context history,JSONObject plan,JSONObject credential){this.history=history;this.plan=plan;this.credential=credential;}
    }
    interface TransportReady {void accept(JSONObject plan,JSONObject credential)throws Exception;}
    Result prepare(Callable<HistoryCoordinator.Context> history,Callable<JSONObject> harness,Callable<JSONObject> mint)throws Exception {
        return prepare(history,harness,mint,(plan,credential)->{});
    }
    Result prepare(Callable<HistoryCoordinator.Context> history,Callable<JSONObject> harness,Callable<JSONObject> mint,TransportReady transport)throws Exception {
        joining=Thread.currentThread();
        CompletionService<Part> completed=new ExecutorCompletionService<>(workers);
        Object[] values=new Object[3];boolean announced=false;
        try {
            submit(completed,0,history);submit(completed,1,harness);submit(completed,2,mint);
            for(int i=0;i<3;i++){
                check();long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new TimeoutException("Voice startup deadline exceeded");
                Future<Part> next=completed.poll(remaining,TimeUnit.NANOSECONDS);
                if(next==null)throw new TimeoutException("Voice startup deadline exceeded");
                Part part=next.get();values[part.index]=part.value;
                if(!announced&&values[1]!=null&&values[2]!=null){
                    check();announced=true;transport.accept((JSONObject)values[1],(JSONObject)values[2]);
                }
            }
            check();return new Result((HistoryCoordinator.Context)values[0],(JSONObject)values[1],(JSONObject)values[2]);
        }catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failed;}
        finally{synchronized(this){close();joining=null;}}
    }
    private synchronized void submit(CompletionService<Part> completed,int index,Callable<?> work){
        check();futures.add(completed.submit(()->{check();Object value=work.call();check();return new Part(index,value);}));
    }
    void check(){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException();if(System.nanoTime()>=deadline)throw new CancellationException("Voice startup deadline exceeded");}
    /** The socket shares this original monotonic budget, including account preflight. */
    long remainingMillis(){return Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    public synchronized void close(){cancelled.set(true);Thread waiting=joining;if(waiting!=null&&waiting!=Thread.currentThread())waiting.interrupt();for(Future<Part> future:futures)future.cancel(true);workers.shutdownNow();}
}
