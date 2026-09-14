package dev.codespire.orchestrator.factory;

import io.quarkus.arc.ClientProxy;
import java.util.Map;

/** Test-only deployment replacement: a resumed prepared claim can meet different configuration. */
public final class WorkRunTestConfiguration {
    private WorkRunTestConfiguration() {}
    public static AutoCloseable withoutImages(WorkRunAssembly assembly) {
        WorkRunAssembly target=ClientProxy.unwrap(assembly);
        FactoryConfig original=target.config;
        target.config=new FactoryConfig() {
            public Map<String,String> agentImage(){return Map.of();}
            public long wallClockSeconds(){return original.wallClockSeconds();}
            public Fix fix(){return original.fix();}
        };
        return ()->target.config=original;
    }
}
