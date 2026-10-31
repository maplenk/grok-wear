package si.zya.grokwear;

/** Duration shown on a call. A live call counts from its start; a finished one stays stamped. */
final class CallElapsed {
    private CallElapsed(){}
    /** Zero startedAt means not started or already finished, so the stamped duration is unchanged. */
    static long millis(long startedAt,long stampedMs,long now){
        return startedAt==0?stampedMs:Math.max(0,now-startedAt);
    }
}
