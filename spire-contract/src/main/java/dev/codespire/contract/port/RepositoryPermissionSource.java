package dev.codespire.contract.port;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;

/** Effective access, including inherited rights, bound to a stable actor and configured origin. */
public interface RepositoryPermissionSource {
    RepositoryPermission permission(RepoRef repository, String providerUserId);
}
