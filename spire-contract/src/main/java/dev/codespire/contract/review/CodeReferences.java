package dev.codespire.contract.review;

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
 */
public record CodeReferences(Set<String> changedPaths, Set<String> identifiers) {

    private static final CodeReferences EMPTY = new CodeReferences(Set.of(), Set.of());

    public CodeReferences {
        changedPaths = changedPaths == null ? Set.of() : Set.copyOf(changedPaths);
        identifiers = identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }

    public static CodeReferences empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return changedPaths.isEmpty() || identifiers.isEmpty();
    }
}
