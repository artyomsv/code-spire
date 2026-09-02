package dev.codespire.workspace;

/**
 * A path in the agent's tree that the push gate cannot safely reason about.
 *
 * <p>The fetch does not run {@code fsck}, so a tree can carry a {@code ..} segment, an absolute
 * path, a backslash or a NUL. None of those can execute here — nothing is ever checked out — but
 * they become the strings the gate matches globs against, and a gate written as
 * {@code .github/workflows/**} does not match {@code ../.github/workflows/ci.yml} while a checkout
 * on the forge honours it.
 *
 * <p>Refused rather than normalised on purpose: a normalised path is a different path, and quietly
 * matching a glob against something the forge will not write is how a gate comes to disagree with
 * the repository it protects.
 */
public class UnsafeTreePathException extends RuntimeException {

    public UnsafeTreePathException(String path) {
        super("refusing to gate an unsafe tree path: \"" + path + "\"");
    }
}
