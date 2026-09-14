package dev.codespire.orchestrator.work;
import org.eclipse.microprofile.config.ConfigProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Packaged production JVM, isolated test services and a fresh directory with no dev .env. */
final class WorkProcessHarness {
    static Process start(Path temporary,String name,Map<String,String> overrides) throws Exception {
        Path app=Path.of(System.getProperty("spire.test.packaged-app"));assertTrue(Files.isRegularFile(app),"The test task must package the current production scanner");
        var config=ConfigProvider.getConfig();Properties properties=new Properties();
        for(String key:List.of("quarkus.datasource.jdbc.url","quarkus.datasource.username","quarkus.datasource.password","kafka.bootstrap.servers","spire.encryption.keyset"))
            properties.setProperty(key,config.getValue(key,String.class));
        properties.setProperty("quarkus.http.port","0");properties.setProperty("quarkus.oidc.tenant-enabled","false");
        properties.setProperty("quarkus.oidc.auth-server-url","http://localhost/TEST-disabled-oidc");
        properties.setProperty("quarkus.oidc.credentials.secret","TEST-disabled-oidc");
        properties.setProperty("quarkus.http.proxy.trusted-proxies","127.0.0.1");
        properties.setProperty("spire.security.auth-enabled","true");properties.setProperty("spire.security.allow-insecure-provider-urls","true");
        properties.setProperty("quarkus.http.auth.permission.operator.policy","permit");
        properties.setProperty("quarkus.log.console.json.enabled","false");properties.setProperty("spire.work-scan-interval","0.2s");
        properties.setProperty("spire.work-effects-interval","off");properties.setProperty("spire.work-outbox-interval","off");properties.setProperty("spire.repository-history-interval","off");
        properties.setProperty("spire.work-gate-expiry-interval","off");
        properties.setProperty("spire.work-run-interval","off");
        properties.setProperty("spire.work-delivery-interval","off");
        properties.setProperty("spire.work-activity-interval","off");
        // A killed recovery JVM must not retain partitions in the test parent's consumer groups.
        // These proofs drive the scanner/expiry scheduler, so give every incoming channel its own empty topic and group.
        String namespace="TEST-recovery-"+UUID.randomUUID();
        for(String key:config.getPropertyNames())if(key.startsWith("mp.messaging.incoming.") && key.endsWith(".connector")) {
            String channel=key.substring(0,key.length()-".connector".length());
            String isolated=namespace+"-"+channel.substring("mp.messaging.incoming.".length());
            properties.setProperty(channel+".topic",isolated);
            properties.setProperty(channel+".group.id",isolated);
        }
        properties.putAll(overrides);
        Path configFile=temporary.resolve(name+".properties");try(var output=Files.newOutputStream(configFile)){properties.store(output,"TEST-only process recovery configuration");}
        // The test task puts its selected JDK first on PATH; the executable is a literal.
        ProcessBuilder builder=new ProcessBuilder("java","-Xmx256m","-jar",app.toAbsolutePath().toString());
        // A fresh directory prevents Quarkus from reading the worktree's dev .env.
        builder.directory(temporary.toFile());builder.environment().keySet().removeIf(key->key.startsWith("SPIRE_")||key.startsWith("QUARKUS_")||key.startsWith("KAFKA_")||key.startsWith("POSTGRES_"));
        builder.environment().put("QUARKUS_CONFIG_LOCATIONS",configFile.toUri().toString());
        Path log=temporary.resolve(name+".log");builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }
}
