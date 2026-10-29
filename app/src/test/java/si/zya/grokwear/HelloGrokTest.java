package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.*;

/** The words after "Hello Grok" go into the call at its 24 kHz rate, and the check's level; synthetic samples only. */
public class HelloGrokTest {
    private static short at(byte[] pcm,int frame){return (short)((pcm[2*frame]&0xff)|(pcm[2*frame+1]<<8));}

    @Test public void sixteenKilohertzBecomesTwentyFourAsLittleEndianBytes(){
        byte[] out=HelloGrok.toCallRate(new short[16000]);
        assertEquals("one second stays one second",24000*2,out.length);
        assertEquals(0,HelloGrok.toCallRate(new short[0]).length);
        assertEquals(0,HelloGrok.toCallRate(null).length);
    }
    @Test public void checkLevelIsTheLoudestFrames(){
        short[] audio=new short[16000];for(int i=0;i<1600;i++)audio[i]=(short)(i%2==0?3277:-3277); // 100 ms at a tenth of full scale
        assertEquals("ten loud frames among twenty, the rest silent",0.05f,HelloCheck.loudest(audio,audio.length),0.001f);
        assertEquals(0f,HelloCheck.loudest(new short[100],100),0f);
    }
    @Test public void samplesBetweenInputsAreInterpolated(){
        byte[] out=HelloGrok.toCallRate(new short[]{0,300,-300,32767});
        assertEquals(6,out.length/2);
        assertEquals(0,at(out,0));assertEquals("two thirds of the way to 300",200,at(out,1));
        assertEquals("a third of the way from 300 to -300",100,at(out,2));assertEquals(-300,at(out,3));
        assertEquals("the loudest sample survives",32767,at(out,5));
    }
}
