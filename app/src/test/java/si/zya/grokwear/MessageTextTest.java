package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MessageTextTest {
    @Test public void formatsBotRepliesWithoutExposingMarkup() {
        assertEquals("research bot: Ready\n• Use the report",
            MessageText.plain("**research bot**: _Ready_\n- Use [the report](https://example.test/report)"));
    }
    @Test public void preservesMathIdentifiersAndLiteralHtmlAsText() {
        assertEquals("2 * 3 = 6; account_key; <script>alert(1)</script>",
            MessageText.plain("2 * 3 = 6; account_key; <script>alert(1)</script>"));
    }
    @Test public void formatsCommonBlockAndCodeSyntax() {
        assertEquals("Summary\nReviewed\nold new\nhello_world()", MessageText.plain(
            "# Summary\n> Reviewed\n~~old~~ `new`\n```java\nhello_world()\n```"));
    }
    @Test public void handlesEmptyAndUnbalancedTextWithoutDroppingIt() {
        assertEquals("", MessageText.plain(null));
        assertEquals("**unfinished and [broken", MessageText.plain("**unfinished and [broken"));
    }
    @Test public void sentenceAtFollowsThePositionThroughTheAnswer(){
        String answer="Rainbows start with sunlight. Each color bends differently! Then it reflects? Done";
        assertEquals("Rainbows start with sunlight.",MessageText.sentenceAt(answer,0));
        assertEquals("Each color bends differently!",MessageText.sentenceAt(answer,0.45));
        assertEquals("Then it reflects?",MessageText.sentenceAt(answer,0.8));
        assertEquals("Done",MessageText.sentenceAt(answer,1));
        assertEquals("",MessageText.sentenceAt("",0.5));
    }
}
