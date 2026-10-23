package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AvatarDesignTest {
    private JSONObject designs()throws Exception {
        Path file=Paths.get("src/main/assets/bot-avatar.json");if(!Files.exists(file))file=Paths.get("app/src/main/assets/bot-avatar.json");
        return new JSONObject(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void curatedSourceContainsActualPaletteAndEveryBaseOutline()throws Exception {
        JSONObject data=designs();assertEquals("0.72.0",data.getString("version"));
        assertEquals("b9ebccdfb06c077fd51a9b10f336c8c3a732531958e9f8f9f233bcbee2991bf3",data.getString("sourceSha256"));
        assertEquals(11,data.getJSONArray("palette").length());assertEquals(26,data.getJSONObject("shapes").length());
        JSONArray colors=data.getJSONArray("palette");for(int i=0;i<colors.length();i++){JSONObject c=colors.getJSONObject(i);assertTrue(AvatarPolicy.validColor(c.getString("id")));if(c.getString("id").equals("blue"))assertEquals("#1084FE",c.getString("value"));if(c.getString("id").equals("orange"))assertEquals("#FF6700",c.getString("value"));}
        JSONObject shapes=data.getJSONObject("shapes");java.util.Iterator<String> shapeNames=shapes.keys();while(shapeNames.hasNext()){String shape=shapeNames.next();
            assertTrue(AvatarPolicy.validShape(shape));JSONObject vector=shapes.getJSONObject(shape);assertTrue(vector.getString("body").startsWith("M"));assertTrue(vector.getString("body").endsWith("Z"));
            JSONObject eyes=vector.getJSONObject("eyes");assertEquals(9,eyes.length());java.util.Iterator<String> expressionNames=eyes.keys();while(expressionNames.hasNext()){String expression=expressionNames.next();assertEquals(shape+expression,2,eyes.getJSONArray(expression).length());}
        }
    }
    @Test public void pumpkinMatchesAndroid1170WithItsStemInFixedGreys()throws Exception {
        assertTrue(AvatarPolicy.validShape("pumpkin"));
        JSONObject pumpkin=designs().getJSONObject("shapes").getJSONObject("pumpkin");JSONArray painted=pumpkin.getJSONArray("painted");
        assertEquals(2,painted.length());assertEquals("#3d3d3d",painted.getJSONObject(0).getString("fill"));assertEquals("#525252",painted.getJSONObject(1).getString("fill"));
        for(String path:new String[]{pumpkin.getString("body"),painted.getJSONObject(0).getString("d"),painted.getJSONObject(1).getString("d")}){
            java.util.regex.Matcher n=java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(path);while(n.find()){double v=Double.parseDouble(n.group());assertTrue(path,v>=0&&v<=100);}
        }
    }
    @Test public void sourceShortHexNormalizesForAndroidColorParser(){
        assertEquals("#000000",AvatarPolicy.colorHex("#000"));assertEquals("#aabbcc",AvatarPolicy.colorHex("#abc"));assertEquals("#1084FE",AvatarPolicy.colorHex("#1084FE"));
    }
    @Test public void invalidMetadataDoesNotBecomeArbitraryAssets(){
        assertFalse(AvatarPolicy.validShape(null));assertFalse(AvatarPolicy.validShape("bat"));assertFalse(AvatarPolicy.validShape("../../file"));
        assertFalse(AvatarPolicy.validColor(null));assertFalse(AvatarPolicy.validColor("#ff0000"));assertFalse(AvatarPolicy.validColor("Blue"));
    }
    @Test public void settledFacesStopLoopingWhileActiveVoiceCanAnimate(){
        for(String state:new String[]{"idle","sleepy","muted","happy","sad","connecting"})assertFalse(state,AvatarPolicy.loops(state));
        for(String state:new String[]{"listening","thinking","speaking","confused"})assertTrue(state,AvatarPolicy.loops(state));
    }
    @Test public void stateMappingKeepsFaceMeaningWithoutGenericCircles() {
        assertEquals("sleepy",AvatarPolicy.expression(AvatarPolicy.state("connecting")));
        assertEquals("sleepy",AvatarPolicy.expression(AvatarPolicy.state("paused")));
        assertEquals("calm",AvatarPolicy.expression("listening"));assertEquals("inspecting",AvatarPolicy.expression("thinking"));
        assertEquals("confused",AvatarPolicy.expression(AvatarPolicy.state("reconnecting")));
        assertEquals("sad",AvatarPolicy.expression(AvatarPolicy.state("error")));assertEquals("happy",AvatarPolicy.expression(AvatarPolicy.state("done")));
    }
    @Test public void everyWatchStateHasARealClipAndExpression()throws Exception {
        JSONObject data=designs();JSONObject eyes=data.getJSONObject("shapes").getJSONObject("blob").getJSONObject("eyes");
        java.util.Set<String> clips=new java.util.HashSet<>();JSONArray source=data.getJSONObject("timings").getJSONArray("clips");for(int i=0;i<source.length();i++)clips.add(source.getJSONObject(i).getString("name"));
        for(String raw:new String[]{"ready","connecting","listening","thinking","speaking","muted","paused","reconnecting","error","done"}){String state=AvatarPolicy.state(raw);assertTrue(eyes.has(AvatarPolicy.expression(state)));assertTrue(clips.contains(AvatarPolicy.clip(state)));}
    }
    @Test public void sourceTimingsAndCubicInterpolationSurviveNativeConversion()throws Exception {
        JSONObject data=designs().getJSONObject("timings");assertEquals(.26,data.getDouble("blinkSeconds"),0);assertEquals(.28,data.getDouble("expressionSeconds"),0);assertEquals(.35,data.getDouble("bridgeSeconds"),0);
        AvatarMotion.Track track=new AvatarMotion.Track(new JSONArray("[{t:0,v:[1,2],ease:[0,0,1,1]},{t:2,v:[3,6]}]"));
        assertEquals(1,track.at(-1,0,0),0);assertEquals(2,track.at(1,0,0),.0001);assertEquals(4,track.at(1,1,0),.0001);assertEquals(3,track.at(9,0,0),0);
        assertEquals(7,new AvatarMotion.Track(null).at(1,0,7),0);
    }
}
