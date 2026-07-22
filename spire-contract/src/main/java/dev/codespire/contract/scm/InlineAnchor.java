package dev.codespire.contract.scm;

/**
 * Provider-neutral inline-comment anchor (SCM-MAPPING.md §4), derived from a
 * {@link DiffLine}: ADDED -> side=NEW + newLine; REMOVED -> side=OLD + oldLine;
 * CONTEXT -> both lines set, side=NEW by default. Each adapter maps this to its
 * provider's anchoring scheme (Bitbucket {@code inline}, GitHub line/side,
 * GitLab position, DC anchor). {@code endNewLine} is set only for a NEW-side
 * finding whose range spans multiple lines resolved within one hunk (null
 * otherwise, including every OLD-side anchor) — adapters that support ranges
 * (GitHub) post a start..end comment; adapters that don't anchor at newLine.
 */
public record InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine,
                           Side side, Integer endNewLine) {

    /** Single-line convenience constructor — {@code endNewLine} defaults to null. */
    public InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine, Side side) {
        this(path, srcPath, oldLine, newLine, side, null);
    }

    /** Stable idempotency key for comment posting (ADR-013) — frozen format, ignores endNewLine. */
    public String anchorKey() {
        return path + ":" + (side == Side.OLD ? oldLine : newLine) + ":" + side;
    }
}
