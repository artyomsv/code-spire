package dev.codespire.contract.scm;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ThreadTranscriptTest {

    @Test
    void messagesAreDefensivelyCopied() {
        List<ThreadMessage> src = new ArrayList<>();
        src.add(new ThreadMessage("octocat", "why is this a bug?", false));
        ThreadTranscript t = new ThreadTranscript(new ThreadRef("c1"), "src/App.java", 42, "abc123", src);
        src.add(new ThreadMessage("intruder", "mutate", false));
        assertEquals(1, t.messages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> t.messages().add(new ThreadMessage("x", "y", false)));
    }

    @Test
    void carriesTheAnchor() {
        ThreadTranscript t = new ThreadTranscript(new ThreadRef("c1"), "src/App.java", 42, "abc123", List.of());
        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertEquals("abc123", t.commit());
    }
}
