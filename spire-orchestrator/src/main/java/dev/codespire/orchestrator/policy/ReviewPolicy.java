package dev.codespire.orchestrator.policy;

import dev.codespire.contract.scm.Author;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operator gates applied before a webhook PR event becomes a review run —
 * first-contact safety for the live SCM integration:
 *
 * <ul>
 *   <li><b>observe-only mode</b> ({@code spire.review.mode=observe}) — the PR is
 *       received and the review is registered (ReviewRequested is persisted and
 *       shown on the dashboard), but NO action commands are emitted: no diff
 *       fetch, no LLM call, no comments. "Receive, register, do nothing."</li>
 *   <li><b>author allowlist</b> ({@code spire.review.author-allowlist}) — when
 *       non-empty, only PRs whose author matches an entry (by stable account id
 *       OR username, case-insensitive) are registered; everyone else is skipped,
 *       so colleagues unaware of the prototype never get a bot interaction. An
 *       empty allowlist reviews everyone (the product default).</li>
 * </ul>
 */
@Startup // eager: log the posture at boot so a first-contact run is visibly safe
@ApplicationScoped
public class ReviewPolicy {

    private static final Logger LOG = Logger.getLogger(ReviewPolicy.class);

    private final boolean observeOnly;
    private final Set<String> allowlist;

    // Optional (not String): SmallRye's String converter maps an empty
    // allowlist to null, which a plain @ConfigProperty String rejects at boot.
    @Inject
    public ReviewPolicy(
            @ConfigProperty(name = "spire.review.mode", defaultValue = "active") String mode,
            @ConfigProperty(name = "spire.review.author-allowlist") Optional<String> authorAllowlist) {
        this(mode, authorAllowlist.orElse(""));
    }

    /** Direct construction without the config layer (also used by tests). */
    public ReviewPolicy(String mode, String authorAllowlistCsv) {
        this.observeOnly = "observe".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.allowlist = authorAllowlistCsv == null ? Set.of()
                : Arrays.stream(authorAllowlistCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        // Operator visibility: log the posture, not the entries (no identifiers in logs).
        LOG.infof("Review policy: mode=%s, author-allowlist=%s",
                observeOnly ? "OBSERVE (register only, no diff/LLM/comments)" : "active",
                allowlist.isEmpty() ? "everyone" : allowlist.size() + " author(s)");
    }

    /** True when a run must be registered but emit no action commands. */
    public boolean observeOnly() {
        return observeOnly;
    }

    /** True when the PR author may be reviewed (an empty allowlist allows everyone). */
    public boolean allows(Author author) {
        if (allowlist.isEmpty()) {
            return true;
        }
        if (author == null) {
            return false;
        }
        return matches(author.providerUserId()) || matches(author.username());
    }

    private boolean matches(String value) {
        return value != null && allowlist.contains(value.toLowerCase(Locale.ROOT));
    }
}
