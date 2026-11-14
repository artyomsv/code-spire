package dev.codespire.contract.review;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who wrote the text a set of context links was found in (harness reviews, step 8b).
 *
 * <p>On an untrusted pull request (a fork, or unknown provenance) the orchestrator gathers links only from text
 * written by people with push access, so the review worker reports each source separately: the pull request's own
 * title, branch and description under its author, and each comment under the comment's author. The author is the
 * stable provider id; the handle is for the timeline only and decides nothing. Links and ids only, never the text.
 *
 * <p><b>Bounded.</b> A fork author decides how many comments and links exist, and this rides the Kafka wire, so a
 * source keeps at most {@link #MAX_LINKS} links in insertion order and a message at most {@link #MAX_SOURCES} sources.
 *
 * <p>{@code vouch} is what the forge itself says about the author. It only chooses whose permission is measured
 * first and never trusts anyone: {@link Vouch#UNKNOWN} for a forge that says nothing, and for every message sent
 * before the field existed.
 */
public record LinkSource(Kind kind, String authorId, String authorHandle, Set<String> links, Vouch vouch) {

    /** Links kept per source. */
    public static final int MAX_LINKS = 50;

    /** Sources kept per message, the pull request's own first. */
    public static final int MAX_SOURCES = 100;

    /** Where the text came from. */
    public enum Kind { PULL_REQUEST, COMMENT }

    /** The forge's own word on an author: a member, collaborator or owner of the repository, or not one. */
    public enum Vouch { UNKNOWN, VOUCHED, NOT_VOUCHED }

    public LinkSource {
        links = bounded(links);
        vouch = vouch == null ? Vouch.UNKNOWN : vouch;
    }

    /** A source whose forge says nothing about its author. */
    public LinkSource(Kind kind, String authorId, String authorHandle, Set<String> links) {
        this(kind, authorId, authorHandle, links, Vouch.UNKNOWN);
    }

    private static Set<String> bounded(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String value : values) {
            if (kept.size() == MAX_LINKS) {
                break;
            }
            kept.add(value);
        }
        return Set.copyOf(kept);
    }
}
