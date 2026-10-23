package si.zya.grokwear;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
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
    @Test public void styledKeepsBoldAndHeadingsAsRangesWithPlainText() {
        MessageText.Styled styled = MessageText.styled("# Fares\nCheapest is **10/10 NT$3,430**, __down 5.8%__ and _calm_");
        assertEquals("Fares\nCheapest is 10/10 NT$3,430, down 5.8% and calm", styled.text);
        assertArrayEquals(new int[]{0, 5, 18, 32, 34, 43}, styled.bold);
        assertEquals("Fares", styled.text.substring(styled.bold[0], styled.bold[1]));
        assertEquals("10/10 NT$3,430", styled.text.substring(styled.bold[2], styled.bold[3]));
    }
    @Test public void styledTextMatchesPlainText() {
        for (String sample : new String[]{"**research bot**: _Ready_\n- Use [the report](https://example.test/report)",
                "2 * 3 = 6; account_key", "**unfinished and [broken", "  **lead** trailing  ", "", "~~old~~ `new`"})
            assertEquals(MessageText.plain(sample), MessageText.styled(sample).text);
        assertArrayEquals(new int[]{0, 4}, MessageText.styled("  **lead** trailing  ").bold);
        assertArrayEquals(new int[0], MessageText.styled("**unfinished and [broken").bold);
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
