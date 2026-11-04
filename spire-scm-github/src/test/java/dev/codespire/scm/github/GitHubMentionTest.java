package dev.codespire.scm.github;

import dev.codespire.contract.scm.Author;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a reply mentions the person it answers on GitHub: `@login`, and nothing for a login the forge would not link. */
class GitHubMentionTest {

    private final GitHubCommentSink sink = new GitHubCommentSink(null);

    @Test
    void aLoginIsMentionedWithAnAt() {
        assertEquals(Optional.of("@TEST-alice"), sink.mention(Author.of("TEST-id-9", "TEST-alice", "TEST Alice")));
    }

    @Test
    void anAuthorWithNoUsableLoginIsNotMentioned() {
        assertEquals(Optional.empty(), sink.mention(Author.of("TEST-id-9", null, "TEST Alice")));
        assertEquals(Optional.empty(), sink.mention(Author.of("TEST-id-9", "TEST alice", "TEST Alice")));
        assertEquals(Optional.empty(), sink.mention(null));
    }
}
