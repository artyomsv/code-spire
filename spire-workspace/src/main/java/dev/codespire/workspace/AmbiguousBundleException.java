package dev.codespire.workspace;

import java.util.List;

/**
 * A handoff bundle offered more than one branch.
 *
 * <p>The publisher gates and pushes exactly one sha, so "take the first ref" is not a decision — it
 * is an ordering accident of the ref database. A bundle carrying a second branch is refused rather
 * than silently resolved, because the branch that wins would be the one nobody chose.
 */
public class AmbiguousBundleException extends RuntimeException {

    public AmbiguousBundleException(List<String> refs) {
        super("bundle offers " + refs.size() + " branches, expected exactly one: " + String.join(", ", refs));
    }
}
