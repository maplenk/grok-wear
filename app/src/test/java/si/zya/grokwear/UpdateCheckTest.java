package si.zya.grokwear;

import static org.junit.Assert.*;

import org.junit.Test;

public class UpdateCheckTest {
    @Test public void readsReleaseTagsAndNothingElse(){
        assertEquals("0.7.3",UpdateCheck.versionOf("v0.7.3"));
        assertEquals("1.0",UpdateCheck.versionOf("1.0"));
        for(String odd:new String[]{null,"","latest","v0.7.3-rc1","v0.7.3 ","vv0.7.3","0.7.3.1.2"})assertEquals(odd,"",UpdateCheck.versionOf(odd));
    }
    @Test public void comparesVersionsByNumber(){
        assertTrue(UpdateCheck.compare("0.7.10","0.7.9")>0);
        assertTrue(UpdateCheck.compare("0.8","0.7.9")>0);
        assertEquals(0,UpdateCheck.compare("0.7","0.7.0"));
        assertTrue(UpdateCheck.compare("0.7.3","0.7.3-debug")==0);
    }
    @Test public void onlyANewerReleaseIsNews(){
        assertEquals("0.7.4",UpdateCheck.newer("v0.7.4","0.7.3"));
        assertEquals("",UpdateCheck.newer("v0.7.3","0.7.3"));
        assertEquals("",UpdateCheck.newer("v0.7.2","0.7.3"));
        assertEquals("",UpdateCheck.newer("nightly","0.7.3"));
        assertEquals("https://github.com/maplenk/grok-wear/releases/tag/v0.7.4",UpdateCheck.page("0.7.4"));
    }
    @Test public void asksAtMostOnceADay(){
        long day=UpdateCheck.DAY_MS;
        assertTrue(UpdateCheck.due(0,5));
        assertFalse(UpdateCheck.due(1000,1000+day-1));
        assertTrue(UpdateCheck.due(1000,1000+day));
        assertTrue("a clock set back asks again",UpdateCheck.due(5000,1000));
    }
}
