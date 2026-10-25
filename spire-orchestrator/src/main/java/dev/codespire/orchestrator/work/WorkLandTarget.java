package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.work.WorkLandEffects.Effect;
import org.jboss.logging.Logger;

/**
 * What one land pass works with: the FACTORY account's lander and its stable provider id, the repository and the saved
 * merge method. The forge calls the sweep and the branch cleanup share go through here, so a failure is logged one way.
 */
record WorkLandTarget(PullRequestLander lander, dev.codespire.contract.scm.RepoRef repo, PullRequestLander.MergeMethod method,
                      String factoryId) {
    // The land sweep's category: its warnings are read under that name.
    private static final Logger LOG = Logger.getLogger(WorkLandSweep.class);

    /** The pull request as GitHub reports it now; null, and logged, when it cannot be read. */
    LandState read(Effect effect) {
        try { return lander.read(repo, effect.pullRequest()); }
        catch (RuntimeException unreadable) {
            warn(effect, "could not be read", unreadable);
            return null;
        }
    }

    /**
     * A merge found after a lost answer is the factory's only at the approved head, which is all the sent merge could
     * have merged, and under the factory's own id, current or recorded at the build claim. A person who merged a moved
     * head, or the approved one, keeps the merge (anti-goal §4, A2).
     */
    boolean mergedByFactory(Effect effect, WorkItemEvent item, LandState pr) {
        String merger = pr.mergedBy();
        if (!effect.approvedHead().equals(pr.headSha()) || merger == null || merger.isBlank()) return false;
        return merger.equals(factoryId) || item.control() != null && merger.equals(item.control().factoryActor());
    }

    /**
     * A forge call that failed, named by the effect, the repository and the exception type with its HTTP status. The
     * message is left out: an adapter's message may quote a response body.
     */
    void warn(Effect effect, String what, RuntimeException failure) {
        LOG.warnf("Land effect %s on %s: pull request #%d %s (%s)", effect.id(), repo.full(), effect.pullRequest(), what, cause(failure));
    }

    /** The exception's type, with the HTTP status when the forge answered one. */
    static String cause(RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        return failure instanceof ScmApiException api ? type + " HTTP " + api.status() : type;
    }
}
