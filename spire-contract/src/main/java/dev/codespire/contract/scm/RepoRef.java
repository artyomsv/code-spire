package dev.codespire.contract.scm;

/** Repository reference: {workspace}/{slug}. */
public record RepoRef(String workspace, String slug) {

    public String full() {
        return workspace + "/" + slug;
    }
}
