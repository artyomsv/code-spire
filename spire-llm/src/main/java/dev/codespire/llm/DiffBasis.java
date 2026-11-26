package dev.codespire.llm;

/** Which diff a reconcile judges the prior findings against (ADR-019). */
public enum DiffBasis {

    /** The compare from the prior review's head to this one: what changed since. */
    INCREMENTAL,

    /** The pull request's whole diff, when the compare is unavailable (history was rewritten). */
    FULL
}
