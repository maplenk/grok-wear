package si.zya.grokwear;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PcmPlaybackBufferTest {
    private static byte[] pcm(int length){byte[] b=new byte[length];for(int i=0;i<length;i++)b[i]=(byte)(i*31+i/17+1);return b;}
    private static final class Sink implements PcmPlaybackBuffer.Writer {
        final ByteArrayOutputStream heard=new ByteArrayOutputStream();
        int allowance;
        Sink(int allowance){this.allowance=allowance;}
        @Override public int write(byte[] data,int offset,int length){int n=Math.min(length,allowance)&~1;if(n>0){heard.write(data,offset,n);allowance-=n;}return n;}
    }
    private static byte[] drain(PcmPlaybackBuffer buffer){Sink sink=new Sink(Integer.MAX_VALUE);byte[] scratch=new byte[2048];while(buffer.size()>0)assertTrue(buffer.writeTo(sink,scratch)>0);return sink.heard.toByteArray();}
    private static byte[] internal(PcmPlaybackBuffer buffer)throws Exception {Field field=PcmPlaybackBuffer.class.getDeclaredField("ring");field.setAccessible(true);return (byte[])field.get(buffer);}
    private static void zero(byte[] data){for(byte b:data)assertEquals(0,b);}

    @Test public void admitsSixSecondSingleDeltaAndPreservesEverySample(){
        byte[] sixSeconds=pcm(24000*2*6);PcmPlaybackBuffer b=new PcmPlaybackBuffer();
        assertEquals(PcmPlaybackBuffer.Admission.ACCEPTED,b.offer(sixSeconds));
        assertArrayEquals(sixSeconds,drain(b));assertEquals(0,b.size());
    }
    @Test public void sixtyFiveSmallDeltasDoNotHaveAnObjectCountLimit(){
        PcmPlaybackBuffer b=new PcmPlaybackBuffer();ByteArrayOutputStream expected=new ByteArrayOutputStream();
        for(int i=0;i<65;i++){byte[] delta=pcm(960);delta[0]=(byte)i;assertEquals(PcmPlaybackBuffer.Admission.ACCEPTED,b.offer(delta));expected.write(delta,0,delta.length);}
        assertArrayEquals(expected.toByteArray(),drain(b));
    }
    @Test public void splitAndCoalescedNetworkBoundariesHaveIdenticalPlayback(){
        byte[] expected=pcm(48000);PcmPlaybackBuffer whole=new PcmPlaybackBuffer(),split=new PcmPlaybackBuffer();whole.offer(expected);
        int[] sizes={2,960,2048,140,4096,18};int offset=0,index=0;
        while(offset<expected.length){int length=Math.min(sizes[index++%sizes.length],expected.length-offset);split.offer(Arrays.copyOfRange(expected,offset,offset+length));offset+=length;}
        assertArrayEquals(drain(whole),drain(split));
    }
    @Test public void twentySecondResponseAtFiveTimesRealtimePlaysCompletelyThroughRateLimitedSink(){
        PcmPlaybackBuffer b=new PcmPlaybackBuffer();Sink sink=new Sink(0);byte[] expected=pcm(48000*20),scratch=new byte[2048];int sent=0,peak=0;
        // Virtual10ms ticks: synthesis emits50ms of PCM; sink accepts only10ms.
        for(int tick=0;tick<2200&&(sent<expected.length||b.size()>0);tick++){
            if(sent<expected.length){int n=Math.min(2400,expected.length-sent);assertEquals(PcmPlaybackBuffer.Admission.ACCEPTED,b.offer(Arrays.copyOfRange(expected,sent,sent+n)));sent+=n;}
            peak=Math.max(peak,b.size());sink.allowance=480;
            while(b.writeTo(sink,scratch)>0){}
            assertTrue(b.size()<=b.capacity());zero(scratch);
        }
        assertTrue("Burst backlog must exceed the old256KiB guard",peak>256*1024);
        assertEquals(expected.length,sent);assertEquals(0,b.size());assertArrayEquals(expected,sink.heard.toByteArray());
    }
    @Test public void partialZeroAndNegativeWritesRetainExactlyTheUnacceptedPcm(){
        PcmPlaybackBuffer b=new PcmPlaybackBuffer(32);byte[] expected=pcm(20),scratch=new byte[7];b.offer(expected);Sink sink=new Sink(2);
        assertEquals(2,b.writeTo(sink,scratch));assertEquals(18,b.size());zero(scratch);
        assertEquals(0,b.writeTo(sink,scratch));assertEquals(18,b.size());
        assertEquals(-6,b.writeTo((data,offset,n)->-6,scratch));assertEquals(18,b.size());
        sink.allowance=100;while(b.size()>0)b.writeTo(sink,scratch);
        assertArrayEquals(expected,sink.heard.toByteArray());zero(scratch);
    }
    @Test public void wrappedBufferAndOverflowLeaveExistingDataUnchanged(){
        PcmPlaybackBuffer b=new PcmPlaybackBuffer(16);byte[] a=pcm(12),c=pcm(10),scratch=new byte[8];b.offer(a);Sink sink=new Sink(8);
        assertEquals(8,b.writeTo(sink,scratch));assertEquals(PcmPlaybackBuffer.Admission.ACCEPTED,b.offer(c));assertEquals(14,b.size());
        assertEquals(PcmPlaybackBuffer.Admission.CAPACITY,b.offer(new byte[4]));assertEquals(14,b.size());
        sink.allowance=100;while(b.size()>0)b.writeTo(sink,scratch);
        ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(a,0,a.length);expected.write(c,0,c.length);assertArrayEquals(expected.toByteArray(),sink.heard.toByteArray());
    }
    @Test public void clearAndCloseZeroMemoryAndNeverReplayAnOldTurn()throws Exception {
        PcmPlaybackBuffer b=new PcmPlaybackBuffer(64);byte[] old=pcm(40);b.offer(old);b.writeTo(new Sink(8),new byte[8]);b.clear();assertEquals(0,b.size());zero(internal(b));
        byte[] fresh={9,8,7,6};b.offer(fresh);assertArrayEquals(fresh,drain(b));zero(internal(b));
        b.offer(old);b.close();zero(internal(b));assertEquals(0,b.size());assertEquals(PcmPlaybackBuffer.Admission.CLOSED,b.offer(fresh));
        assertEquals(0,b.writeTo((data,offset,n)->{fail("Closed buffer wrote to sink");return n;},new byte[4]));b.clear();assertEquals(PcmPlaybackBuffer.Admission.CLOSED,b.offer(fresh));
    }
    @Test public void malformedInputAndWriterFailureCannotConsumeOrCorruptQueuedAudio(){
        PcmPlaybackBuffer b=new PcmPlaybackBuffer(16);byte[] expected=pcm(12),scratch=new byte[8];b.offer(expected);
        assertEquals(PcmPlaybackBuffer.Admission.INVALID_FORMAT,b.offer(new byte[3]));assertEquals(PcmPlaybackBuffer.Admission.INVALID_FORMAT,b.offer(null));
        for(int invalid:new int[]{1,10}){try{b.writeTo((data,offset,n)->invalid,scratch);fail("Invalid sink count accepted");}catch(IllegalStateException expectedError){}assertEquals(12,b.size());zero(scratch);}
        try{b.writeTo((data,offset,n)->{throw new IllegalStateException();},scratch);fail();}catch(IllegalStateException expectedError){}
        assertEquals(12,b.size());zero(scratch);assertArrayEquals(expected,drain(b));
    }
    @Test public void offeredDataIsCopiedAndConstructorRequiresWholePcmSamples(){
        for(int invalid:new int[]{-2,0,1,3}){try{new PcmPlaybackBuffer(invalid);fail();}catch(IllegalArgumentException expected){}}
        PcmPlaybackBuffer b=new PcmPlaybackBuffer(8);byte[] source={1,2,3,4};b.offer(source);Arrays.fill(source,(byte)0);assertArrayEquals(new byte[]{1,2,3,4},drain(b));
    }
}
