package dev.codespire.runtime;

import java.util.UUID;
import java.util.function.Consumer;

/** Optional runtime capability for retaining a built workspace until trusted publication is permitted. */
public interface PublicationRuntime extends RunRuntime {
    /** Stamp the hold on every resource before starting any part, including a partial init failure. */
    RunHandle createHeld(RunUnitSpec spec,PublicationKey binding);

    /** Read trusted runtime metadata, independently of worker memory or lease availability. */
    boolean publicationHeld(RunHandle handle);

    /**
     * Create or observe this permit's publisher, with no init or agent execution.
     * A repeated permit observes the same publisher instance, including after worker restart.
     * The caller commits its claim before invoking this operation and supplies validated configuration.
     */
    default Finalization publishHeld(RunHandle handle,PublicationKey binding,UUID permitId,
                                    RunUnitSpec publication,Consumer<String> lines) {
        return publishHeld(handle,binding,permitId,publication,lines,()->true);
    }

    /** Recheck durable cancellation around publisher creation/start; an existing exited instance is observed. */
    Finalization publishHeld(RunHandle handle,PublicationKey binding,UUID permitId,
                             RunUnitSpec publication,Consumer<String> lines,java.util.function.BooleanSupplier mayStart);

    /** Explicit release by the owning control plane; ordinary destroy must refuse held resources. */
    void destroyHeld(RunHandle handle,PublicationKey binding);
}
