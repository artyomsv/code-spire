package dev.codespire.contract.review;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a diff says about the code it depends on: the paths it changed, and the identifiers appearing
 * in its changed lines.
 *
 * <p>Deliberately NOT folded into {@link ContextRequest}'s neutral {@code references} set, which
 * carries issue keys and page links. Two hazards run in opposite directions. The aggregator's
 * level-2 collection mines contributed item bodies for new references, so a {@code PROJ-123} inside a
 * code comment would be fetched as a ticket. And {@code references} is documented as recall-favouring
 * because "a false candidate costs nothing but an unmatched string" — true at ticket-key volume, and
 * not true of the tens-to-hundreds of identifiers a diff yields, scanned by every registered provider.
 *
 * <p><b>Metadata only.</b> Paths and identifiers, never hunk text — ADR-011 is untouched.
 *
 * <p><b>Bounded.</b> Both sets are truncated in the compact constructor. This value rides the Kafka
 * wire on {@code DiffFetched}, {@code GatherContext} and {@code ContextRequest}, and its size is
 * decided by the pull request: {@code WorkerCodeReferences.inDiff} collects every non-keyword token on
 * every changed line of every supported file, so a large refactor's identifier set is effectively a
 * re-encoding of most of the tokens the diff touched. Unbounded, it can exceed the broker's
 * {@code max.message.bytes} (1 MB by default) — and the produce then fails <em>after</em> the diff was
 * fetched, so the review dead-letters with nothing posted, at any author's option. Truncating loses
 * some resolution recall on a pathological diff, which is the far cheaper outcome; the caps are set
 * an order of magnitude above what a normal review carries, so an ordinary pull request never reaches
 * them. Insertion order decides what survives, so the truncation is deterministic.
 */
public record CodeReferences(Set<String> changedPaths, Set<String> identifiers) {

    private static final CodeReferences EMPTY = new CodeReferences(Set.of(), Set.of());

    /** Changed paths kept — a path can run to a few hundred characters, so this is the tighter cap. */
    public static final int MAX_CHANGED_PATHS = 1_000;

    /** Identifiers kept. Short strings, but the set a big refactor produces is the larger of the two. */
    public static final int MAX_IDENTIFIERS = 2_000;

    public CodeReferences {
        changedPaths = bounded(changedPaths, MAX_CHANGED_PATHS);
        identifiers = bounded(identifiers, MAX_IDENTIFIERS);
    }

    private static Set<String> bounded(Set<String> values, int max) {
        if (values == null) {
            return Set.of();
        }
        if (values.size() <= max) {
            return Set.copyOf(values);
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String value : values) {
            if (kept.size() == max) {
                break;
            }
            kept.add(value);
        }
        return Set.copyOf(kept);
    }

    public static CodeReferences empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return changedPaths.isEmpty() || identifiers.isEmpty();
    }
}
