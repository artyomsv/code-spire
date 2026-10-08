package dev.codespire.orchestrator.factory;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.Map;

/**
 * Harness name → how the orchestrator renews that harness's subscription seats: the vendor CLI's own
 * OAuth client and token address. A harness not listed is never renewed. Keyed by name, like the agent
 * image, so this module never spells a vendor; how the values were measured is written beside them in
 * the configuration.
 */
@ConfigMapping(prefix = "spire.seat-renewal")
public interface SeatRenewalConfig {

    Map<String, Client> clients();

    interface Client {
        @WithName("client-id")
        String clientId();

        String url();
    }
}
