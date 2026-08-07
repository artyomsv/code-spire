package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A token count is a quantity of tokens, so a negative one is not a small number — it is not a token
 * count at all. The type refuses it here rather than downstream, because the failure it caused was
 * remote from its cause: a negative reached the {@code llm_charge.tokens >= 0} insert and threw inside
 * the {@code ReviewGenerated} handler BEFORE comments were posted, so a paid review dead-lettered
 * permanently with nothing on the pull request. Flooring at the one reader that produced it fixes the
 * one reader; refusing it here means no future construction site can reintroduce the same outage.
 */
class TokenCountTest {

    @Test
    void aNegativeCountIsRefusedAtConstruction() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TokenCount(TokenType.TOTAL, -1));
        assertEquals(true, refused.getMessage().contains("TOTAL"),
                "the message must name the dimension, so the vendor field that reported it is findable");
    }

    /** Zero is a real answer — a call without caching legitimately used no cached tokens. */
    @Test
    void zeroIsAValidCount() {
        assertEquals(0, new TokenCount(TokenType.CACHED_INPUT, 0).tokens());
    }
}
