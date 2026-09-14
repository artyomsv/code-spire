package dev.codespire.orchestrator.provider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Fresh JVM reader of the production registry; credentials enter through stdin and never print. */
public final class ActorPolicyRestartProbe {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(input.readLine()); source.setUser(input.readLine()); source.setPassword(input.readLine());
        source.setCurrentSchema("orchestrator");
        ActorPolicyRegistry registry = new ActorPolicyRegistry(); registry.dataSource = source;
        for (ActorDisplay actor : registry.account(UUID.fromString(input.readLine())).actors()) {
            System.out.println(actor.providerUserId() + "|" + actor.handle());
        }
    }
}
