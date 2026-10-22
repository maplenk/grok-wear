package si.zya.grokwear;

import java.util.Arrays;

/** Bounded 24 kHz mono PCM16 backlog, independent of network message boundaries. */
public final class PcmPlaybackBuffer implements AutoCloseable {
    public static final int DEFAULT_CAPACITY=2*1024*1024;
    public enum Admission { ACCEPTED, INVALID_FORMAT, CAPACITY, CLOSED }
    /** Must return promptly (AudioTrack.WRITE_NON_BLOCKING); no callbacks into another lock. */
    @FunctionalInterface public interface Writer { int write(byte[] pcm,int offset,int length); }
    private final byte[] ring;
    private int head,size;
    private boolean closed;
    public PcmPlaybackBuffer(){this(DEFAULT_CAPACITY);}
    public PcmPlaybackBuffer(int evenCapacity){
        if(evenCapacity<2||(evenCapacity&1)!=0)throw new IllegalArgumentException("PCM capacity must contain whole 16-bit samples");
        ring=new byte[evenCapacity];
    }
    public int capacity(){return ring.length;}
    public synchronized int size(){return size;}
    public synchronized Admission offer(byte[] pcm){
        if(closed)return Admission.CLOSED;
        if(pcm==null||(pcm.length&1)!=0)return Admission.INVALID_FORMAT;
        if(pcm.length>ring.length-size)return Admission.CAPACITY;
        int tail=(int)(((long)head+size)%ring.length),first=Math.min(pcm.length,ring.length-tail);
        System.arraycopy(pcm,0,ring,tail,first);
        System.arraycopy(pcm,first,ring,0,pcm.length-first);
        size+=pcm.length;
        return Admission.ACCEPTED;
    }
    /**
     * Returns the sink's count (or zero when empty/closed). Only valid positive writes consume PCM.
     * The caller must serialize this call and clear/flush with its playback-generation lock.
     * Scratch is temporary and is zeroed before return, including on sink failure.
     */
    public synchronized int writeTo(Writer writer,byte[] scratch){
        if(writer==null||scratch==null||scratch.length<2)throw new IllegalArgumentException("A writer and PCM scratch space are required");
        try {
            if(closed||size==0)return 0;
            int requested=Math.min(size,scratch.length)&~1;
            int first=Math.min(requested,ring.length-head);
            System.arraycopy(ring,head,scratch,0,first);
            System.arraycopy(ring,0,scratch,first,requested-first);
            int written=writer.write(scratch,0,requested);
            if(written<=0)return written;
            if(written>requested||(written&1)!=0)throw new IllegalStateException("PCM writer returned an invalid transfer count");
            int cleared=Math.min(written,ring.length-head);
            Arrays.fill(ring,head,head+cleared,(byte)0);
            Arrays.fill(ring,0,written-cleared,(byte)0);
            head=(int)(((long)head+written)%ring.length);
            size-=written;
            if(size==0)head=0;
            return written;
        } finally {Arrays.fill(scratch,(byte)0);}
    }
    public synchronized void clear(){Arrays.fill(ring,(byte)0);head=0;size=0;}
    @Override public synchronized void close(){closed=true;clear();}
}
