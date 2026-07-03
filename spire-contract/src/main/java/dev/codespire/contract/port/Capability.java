package dev.codespire.contract.port;

import java.util.Set;

/**
 * A self-contained capability (review ships in v1; describe/changelog later).
 * Declares the "/commands" it responds to — ScmIngress uses this set to parse
 * manual commands out of PR comments (CONTRACT §10). The execution context
 * (diff, assembled context, ports, resolved config) lands with P2.
 */
public interface Capability {

    String name();

    Set<String> commands();
}
