package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.LinkSource;
import dev.codespire.orchestrator.factory.FixAuthorization;
import dev.codespire.orchestrator.factory.FixPermissionService;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ProviderView;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Whose links count on an untrusted pull request (harness reviews, step 8b): a person with push access by the
 * {@code /fix} rule ({@link FixPermissionService}: the explicit ALLOW/DENY override, then the forge's measured
 * permission). The reviewer bot never counts, and when its identity is not known nobody does, because its own
 * comments could not be told apart. Anything unreadable counts as not trusted, and the review goes on.
 *
 * <p>One permission read per distinct author, run side by side: each read is bounded at 20 seconds, and this runs on
 * the ordered results consumer, so serial reads would let a few slow ones age the record out. At most
 * {@link #MAX_AUTHORS} authors are read, because a fork author decides how many commenters there are, so the reads go
 * in this order: the pull request's author, an author the forge does not vouch for but who has an explicit ALLOW
 * override, authors the forge vouches for (GitHub's owner, member or collaborator), then authors of a forge that says
 * nothing (GitLab), in comment order. An author the forge says is none of those, with no ALLOW override, is dropped
 * without a read, so ten drive-by commenters cannot crowd out a maintainer. GitLab keeps the first-10-authors limit.
 */
@ApplicationScoped
public class LinkAuthorTrust {

    private static final Logger LOG = Logger.getLogger(LinkAuthorTrust.class);

    static final int MAX_AUTHORS = 10;
    static final String REVIEWER_BOT = "REVIEWER_BOT";
    static final String REVIEWER_IDENTITY_UNKNOWN = "REVIEWER_IDENTITY_UNKNOWN";
    static final String AUTHOR_LIMIT = "AUTHOR_LIMIT";
    static final String NOT_ASSOCIATED = "NOT_ASSOCIATED";

    /** Whether one author's links count, and the reason in the {@code /fix} vocabulary or one of the above. */
    public record Verdict(boolean trusted, String reason) {
    }

    @Inject
    ReviewProjection projection;

    @Inject
    RepositoryAccounts accounts;

    @Inject
    FixPermissionService permissions;

    /** One verdict per distinct author id ({@code ""} for a source with none), in the order the sources name them. */
    public Map<String, Verdict> decide(String reviewId, List<LinkSource> sources) {
        Map<String, LinkSource> authors = new LinkedHashMap<>();
        for (LinkSource source : sources) {
            authors.putIfAbsent(keyOf(source), source);
        }
        try {
            Optional<UUID> repositoryId = projection.repositoryIdOf(reviewId);
            if (repositoryId.isEmpty()) {
                return all(authors, FixAuthorization.Reason.REPOSITORY_UNAVAILABLE.name());
            }
            Optional<ProviderView> reviewer = accounts.registration(repositoryId.get(), ProviderRole.REVIEWER);
            if (reviewer.isEmpty() || isBlank(reviewer.get().botAccountId())) {
                return all(authors, REVIEWER_IDENTITY_UNKNOWN);
            }
            return measured(new Reading(repositoryId.get(), reviewer.get()), authors);
        } catch (RuntimeException e) {
            LOG.warnf("Could not read who may add context links to %s: %s", reviewId, e.getMessage());
            return all(authors, FixAuthorization.Reason.PERMISSION_UNAVAILABLE.name());
        }
    }

    static String keyOf(LinkSource source) {
        return source.authorId() == null ? "" : source.authorId().trim();
    }

    private record Reading(UUID repositoryId, ProviderView reviewer) {
    }

    private record Candidate(String id, int rank) {
    }

    private Map<String, Verdict> measured(Reading reading, Map<String, LinkSource> authors) {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, LinkSource> author : authors.entrySet()) {
            String id = author.getKey();
            LinkSource source = author.getValue();
            if (id.isEmpty()) {
                verdicts.put(id, new Verdict(false, FixAuthorization.Reason.UNKNOWN_ACTOR.name()));
            } else if (isBot(source, reading.reviewer())) {
                verdicts.put(id, new Verdict(false, REVIEWER_BOT));
            } else {
                int rank = rank(reading, id, source);
                if (rank < 0) {
                    verdicts.put(id, new Verdict(false, NOT_ASSOCIATED));
                } else {
                    candidates.add(new Candidate(id, rank));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::rank));
        Map<String, Future<FixAuthorization.Decision>> reads = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Candidate candidate : candidates) {
                String id = candidate.id();
                if (reads.size() == MAX_AUTHORS) {
                    verdicts.put(id, new Verdict(false, AUTHOR_LIMIT));
                } else {
                    reads.put(id, pool.submit(() -> permissions.authorize(reading.repositoryId(), id)));
                }
            }
        }
        reads.forEach((id, read) -> verdicts.put(id, verdictOf(read)));
        return ordered(authors, verdicts);
    }

    /** Lower reads first; negative means the forge says the author has no standing and no ALLOW override saves them. */
    private int rank(Reading reading, String id, LinkSource source) {
        if (source.kind() == LinkSource.Kind.PULL_REQUEST) {
            return 0;
        }
        return switch (source.vouch()) {
            case VOUCHED -> 2;
            case UNKNOWN -> 3;
            case NOT_VOUCHED -> allowed(reading, id) ? 1 : -1;
        };
    }

    private boolean allowed(Reading reading, String id) {
        try {
            return permissions.overrideOf(reading.repositoryId(), id) == FixAuthorization.Override.ALLOW;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Verdict verdictOf(Future<FixAuthorization.Decision> read) {
        try {
            FixAuthorization.Decision decision = read.get();
            return new Verdict(decision.allowed(), decision.reason().name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Verdict(false, FixAuthorization.Reason.PERMISSION_UNAVAILABLE.name());
        } catch (ExecutionException e) {
            return new Verdict(false, FixAuthorization.Reason.PERMISSION_UNAVAILABLE.name());
        }
    }

    /** By the stable id; a handle can only exclude, never admit, so matching the bot's handle is safe either way. */
    private static boolean isBot(LinkSource source, ProviderView reviewer) {
        if (reviewer.botAccountId().equals(keyOf(source))) {
            return true;
        }
        String handle = source.authorHandle();
        return !isBlank(handle) && handle.equalsIgnoreCase(reviewer.botUsername());
    }

    private static Map<String, Verdict> all(Map<String, LinkSource> authors, String reason) {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        authors.keySet().forEach(id -> verdicts.put(id, new Verdict(false, reason)));
        return verdicts;
    }

    private static Map<String, Verdict> ordered(Map<String, LinkSource> authors, Map<String, Verdict> verdicts) {
        Map<String, Verdict> ordered = new LinkedHashMap<>();
        authors.keySet().forEach(id -> ordered.put(id, verdicts.get(id)));
        return ordered;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
