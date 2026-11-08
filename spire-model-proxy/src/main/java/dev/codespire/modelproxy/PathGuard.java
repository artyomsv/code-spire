package dev.codespire.modelproxy;

import java.util.Optional;

/**
 * Decides whether a request path may reach the vendor.
 *
 * <p>The raw path is checked before matching, so nothing the vendor's server might decode or
 * normalise differently from this check can pass: a path may hold only unreserved characters and
 * {@code /} (no {@code %} escape, no backslash, no {@code ;}), no {@code ..}, no {@code //} and no
 * {@code .} segment. What remains is already normal, so the normalized path is the raw path. The
 * vendors' model paths need nothing more.
 */
final class PathGuard {

    private PathGuard() {
    }

    /** The normalized path when the route allows it, otherwise empty. */
    static Optional<String> allowed(String rawPath, ProxyRoute route) {
        if (!isPlain(rawPath)) {
            return Optional.empty();
        }
        if (route.exactPaths().contains(rawPath)) {
            return Optional.of(rawPath);
        }
        for (String prefix : route.pathPrefixes()) {
            String boundary = prefix.endsWith("/") ? prefix : prefix + "/";
            if (rawPath.startsWith(boundary)) {
                return Optional.of(rawPath);
            }
        }
        return Optional.empty();
    }

    private static boolean isPlain(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/") || rawPath.contains("..") || rawPath.contains("//")) {
            return false;
        }
        for (int i = 0; i < rawPath.length(); i++) {
            if (!isPathCharacter(rawPath.charAt(i))) {
                return false;
            }
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    /** RFC 3986 unreserved characters, plus the segment separator. */
    private static boolean isPathCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
    }
}
