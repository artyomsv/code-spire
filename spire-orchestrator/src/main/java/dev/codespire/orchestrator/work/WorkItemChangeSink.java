package dev.codespire.orchestrator.work;

import java.util.Set;

/** Where the work-item change feed delivers what the database says changed. */
public interface WorkItemChangeSink {

    /** The ids that changed; {@code "*"} among them means every work item may have changed. */
    void changed(Set<String> ids);

    /** Every open screen reads again: the feed may have missed changes while it was not listening. */
    void refreshAll();
}
