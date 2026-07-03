package dev.codespire.contract.scm;

/**
 * Provider-neutral inline-comment anchor (SCM-MAPPING.md §4), derived from a
 * {@link DiffLine}: ADDED -> side=NEW + newLine; REMOVED -> side=OLD + oldLine;
 * CONTEXT -> both lines set, side=NEW by default. Each adapter maps this to its
 * provider's anchoring scheme (Bitbucket {@code inline}, GitHub line/side,
 * GitLab position, DC anchor).
 */
public record InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine, Side side) {

    /** Stable idempotency key for comment posting (ADR-013). */
    public String anchorKey() {
        return path + ":" + (side == Side.OLD ? oldLine : newLine) + ":" + side;
    }
}
