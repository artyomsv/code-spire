package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 8b's wire additions: who wrote each link on {@code DiffFetched}, and the provenance on {@code FetchDiff}. A
 * message from before either field existed must decode and behave as step 8 does: no link sources, not untrusted.
 */
class LinkSourceWireTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final LinkSource COMMENT =
            new LinkSource(LinkSource.Kind.COMMENT, "TEST-101", "TEST-maintainer", Set.of("TEST-1"));

    // As the services read the bus: Quarkus leaves FAIL_ON_UNKNOWN_PROPERTIES off, and CodeReferences writes a
    // derived "empty" property that no constructor takes.
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void anOldDiffFetchedDecodesWithNoLinkSources() throws Exception {
        String legacy = """
                {"type":"DiffFetched","reviewId":"TEST-review","prId":7,"commit":"abc123","changedFiles":1,
                 "languages":["java"],"sizeBytes":10,"truncated":false,"references":["TEST-1"],"repoRules":null}""";

        DiffFetched back = (DiffFetched) mapper.readValue(legacy, IntegrationEvent.class);

        assertTrue(back.linkSources().isEmpty(), back.linkSources().toString());
        assertEquals(Set.of("TEST-1"), back.references());
    }

    @Test
    void linkSourcesRoundTrip() throws Exception {
        DiffFetched original = diffFetched().withLinkSources(List.of(COMMENT));

        DiffFetched back = (DiffFetched) mapper.readValue(mapper.writeValueAsString(original), IntegrationEvent.class);

        assertEquals(original, back);
    }

    @Test
    void theWitherKeepsEveryOtherComponent() {
        DiffFetched original = diffFetched();

        DiffFetched with = original.withLinkSources(List.of(COMMENT));

        assertEquals(original, with.withLinkSources(List.of()));
        assertEquals(List.of(COMMENT), with.linkSources());
    }

    @Test
    void anOldFetchDiffDecodesAsNotUntrusted() throws Exception {
        String legacy = """
                {"type":"FetchDiff","reviewId":"TEST-review","repo":{"workspace":"TEST-WS","slug":"TEST-REPO"},
                 "prId":7,"commit":"abc123","scmCredential":null}""";

        FetchDiff back = (FetchDiff) mapper.readValue(legacy, ActionCommand.class);

        assertFalse(back.untrustedPullRequest());
    }

    @Test
    void provenanceRoundTrips() throws Exception {
        FetchDiff marked = new FetchDiff("TEST-review", REPO, 7L, "abc123", "TEST-cred", true);

        FetchDiff back = (FetchDiff) mapper.readValue(mapper.writeValueAsString(marked), ActionCommand.class);

        assertEquals(marked, back);
        assertTrue(back.untrustedPullRequest());
    }

    @Test
    void aSourceFromBeforeTheForgesWordDecodesAsUnknown() throws Exception {
        String legacy = """
                {"kind":"COMMENT","authorId":"TEST-101","authorHandle":"TEST-a","links":["TEST-1"]}""";

        LinkSource back = mapper.readValue(legacy, LinkSource.class);

        assertEquals(LinkSource.Vouch.UNKNOWN, back.vouch());
    }

    @Test
    void theForgesWordRoundTrips() throws Exception {
        LinkSource vouched = new LinkSource(LinkSource.Kind.COMMENT, "TEST-101", "TEST-a", Set.of("TEST-1"),
                LinkSource.Vouch.VOUCHED);

        LinkSource back = mapper.readValue(mapper.writeValueAsString(vouched), LinkSource.class);

        assertEquals(LinkSource.Vouch.VOUCHED, back.vouch());
    }

    @Test
    void aSourceKeepsAtMostItsLinkCap() {
        Set<String> many = new LinkedHashSet<>();
        for (int i = 0; i < LinkSource.MAX_LINKS + 5; i++) {
            many.add("TEST-" + i);
        }

        LinkSource source = new LinkSource(LinkSource.Kind.COMMENT, "TEST-101", "TEST-a", many);

        assertEquals(LinkSource.MAX_LINKS, source.links().size());
        assertTrue(source.links().contains("TEST-0"));
    }

    @Test
    void aMessageKeepsAtMostItsSourceCap() {
        List<LinkSource> many = new ArrayList<>();
        for (int i = 0; i < LinkSource.MAX_SOURCES + 5; i++) {
            many.add(new LinkSource(LinkSource.Kind.COMMENT, "TEST-" + i, "TEST-a", Set.of("TEST-1")));
        }

        DiffFetched event = diffFetched().withLinkSources(many);

        assertEquals(LinkSource.MAX_SOURCES, event.linkSources().size());
        assertEquals("TEST-0", event.linkSources().getFirst().authorId());
    }

    private static DiffFetched diffFetched() {
        return new DiffFetched("TEST-review", 7L, "abc123", 1, List.of("java"), 10, false, Set.of("TEST-1"),
                "TEST-RULES", new CodeReferences(Set.of("src/TEST.java"), Set.of("TESTSymbol")));
    }
}
