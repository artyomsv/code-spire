package dev.codespire.context.code;

/**
 * Reads one file's content at a specific commit from a repository — the port a context provider
 * fans out through to resolve the definitions a diff depends on, across whichever SCM platform hosts
 * the repository.
 */
public interface SourceFileReader {

    /**
     * @param repo   the repository identifier, in the platform's own shape ({@code owner/repo})
     * @param path   the file path within the repository
     * @param commit the commit the file is read at
     * @return the file's content at {@code commit}, or {@code null} when it does not exist there — an
     *         absent or moved file (a rename, a deleted dependency) is the normal case, not an error,
     *         so implementations swallow a 404 rather than raising
     */
    String read(String repo, String path, String commit);

    /**
     * The API host this reader talks to — {@code api.github.com}, or a self-managed instance's host.
     *
     * <p>Exists so operational state can be keyed per instance rather than per platform, mirroring
     * {@code DiffSource.apiHost()} in {@code spire-contract} — read that javadoc before implementing
     * this method. A later task keys a per-host circuit breaker off this value. <b>Deliberately not a
     * {@code default} method:</b> the obvious default (a constant per platform) would silently collapse
     * every self-managed instance of that platform onto one key, so one instance being down would pause
     * reads on every other instance of the same platform too. A compile error per implementor is the
     * cheaper outcome.
     */
    String apiHost();
}
