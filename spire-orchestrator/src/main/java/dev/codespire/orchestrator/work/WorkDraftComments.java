package dev.codespire.orchestrator.work;

import jakarta.enterprise.context.ApplicationScoped;

/** RED: the ticket comment carrying a written specification (M4 slice 3). */
@ApplicationScoped
public class WorkDraftComments {
    static final String SPECIFICATION_HEAD = "Specification written by the factory";

    static String specification(String text, String sha256, UntrustedText.Markup markup) { throw new UnsupportedOperationException("RED"); }
}
