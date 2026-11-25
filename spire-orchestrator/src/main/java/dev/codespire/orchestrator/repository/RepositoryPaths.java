package dev.codespire.orchestrator.repository;

/** A repository's workspace and slug as stored: trimmed, and refused when a segment could escape its path. */
final class RepositoryPaths {

    private RepositoryPaths() {
    }

    static String path(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Workspace and repository slug are required");
        String result = value.trim();
        for (String part : result.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.contains("\\")
                    || part.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid repository path");
        }
        return result;
    }
}
