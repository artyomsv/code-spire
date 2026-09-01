package dev.codespire.workspace;

/**
 * A handoff bundle offered no ref at all.
 *
 * <p>Typed rather than a bare {@code IOException} so a caller can tell it from a malformed bundle,
 * and so a test can assert the condition it names. The first version threw an untyped IOException
 * beside two typed siblings, which is exactly why its test could only assert {@code Exception.class}
 * — and that assertion passed against a fixture which never reached the guard, dying earlier in
 * JGit's parser instead.
 */
public class EmptyBundleException extends RuntimeException {

    public EmptyBundleException(String bundleName) {
        super("bundle contained no ref: " + bundleName);
    }
}
