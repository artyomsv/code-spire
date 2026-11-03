package dev.codespire.scm.bitbucket;

import dev.codespire.contract.scm.Author;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a reply mentions the person it answers on Bitbucket Cloud: the braced account id, never a display name. */
class BitbucketCloudMentionTest {

    private final BitbucketCloudCommentSink sink = new BitbucketCloudCommentSink(null);

    @Test
    void anAccountIdIsMentionedInBraces() {
        assertEquals(Optional.of("@{TEST-557058:abc}"),
                sink.mention(Author.of("TEST-557058:abc", "TEST-alice", "TEST Alice")));
    }

    @Test
    void anAuthorWithNoUsableAccountIdIsNotMentioned() {
        assertEquals(Optional.empty(), sink.mention(Author.of(null, "TEST-alice", "TEST Alice")));
        assertEquals(Optional.empty(), sink.mention(Author.of("TEST}bad", "TEST-alice", "TEST Alice")));
        assertEquals(Optional.empty(), sink.mention(null));
    }
}
