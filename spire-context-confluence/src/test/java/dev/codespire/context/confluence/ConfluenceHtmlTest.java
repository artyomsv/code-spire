package dev.codespire.context.confluence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfluenceHtmlTest {

    @Test
    void stripsTagsAndDecodesEntities() {
        String out = ConfluenceHtml.toText("<p>Use <code>a &amp; b</code> &lt;here&gt;</p>");
        assertEquals("Use a & b <here>", out);
    }

    @Test
    void blockTagsBecomeNewlinesAndBlankRunsCollapse() {
        String out = ConfluenceHtml.toText("<p>First</p><p></p><p></p><ul><li>one</li><li>two</li></ul>");
        assertEquals("First\n\none\ntwo", out);
    }

    @Test
    void dropsScriptAndStyleContent() {
        String out = ConfluenceHtml.toText("<p>keep</p><script>alert(1)</script><style>.x{}</style>");
        assertEquals("keep", out);
        assertFalse(out.contains("alert"));
    }

    @Test
    void blankInputYieldsEmpty() {
        assertTrue(ConfluenceHtml.toText(null).isEmpty());
        assertTrue(ConfluenceHtml.toText("   ").isEmpty());
    }
}
