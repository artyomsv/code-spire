package dev.codespire.llm;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;

import java.util.List;

/** Minimal, real (not fabricated-looking) fixtures shared by prompt-builder tests. */
final class TestFixtures {

    private static final String DIFF = """
            diff --git a/src/App.java b/src/App.java
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
            """;

    private TestFixtures() {
    }

    static PullRequest pr() {
        return new PullRequest(
                new RepoRef("sandbox", "demo-repo"), 42,
                "Add feature", "Implements the thing.",
                "feature/x", "main", "abc123",
                Author.of("id-1", "jdoe", "J. Doe"), "https://example.invalid/pr/42");
    }

    static List<FilePatch> patches() {
        return UnifiedDiffParser.parse(DIFF);
    }
}
