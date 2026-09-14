package dev.codespire.worksource;

/** Normalized webhook control facts only. Ticket content never crosses the durable ingress topic. */
public record WorkSourceSignal(String externalScope, WorkIssueLocation issue, LabelEvent hint,WorkSourceActivity activity) {
    public WorkSourceSignal(String externalScope,WorkIssueLocation issue,LabelEvent hint) {
        this(externalScope,issue,hint,null);
    }
}
