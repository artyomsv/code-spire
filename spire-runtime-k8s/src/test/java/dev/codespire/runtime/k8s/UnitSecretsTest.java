package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.TinyproxyConfig;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnitSecretsTest {

    private static final String RUN = UnitNamesTest.RUN;
    private static final String FETCH_TOKEN = "TEST-token";
    private static final String MODEL_SECRET = "TEST-model-secret";
    private static final String PLACEHOLDER = "TEST-placeholder";

    private static final KubernetesRuntimeConfig CONFIG = new KubernetesRuntimeConfig("test-ns",
            "TEST-publisher:TEST", "TEST-egress:TEST", List.of("TEST-canary"), "TEST-api-canary", "10.0.0.10",
            "kube-system", Map.of("k8s-app", "kube-dns"), List.of(), List.of(), 3, Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60), "100m", "128Mi");

    private static final OwnerReference ANCHOR = new OwnerReferenceBuilder()
            .withApiVersion("v1").withKind("ConfigMap").withName(UnitNames.anchor(RUN)).withUid("TEST-uid").build();

    private static RunUnitSpec spec() {
        RunUnitSpec base = TestUnits.proxiedReview(RUN);
        ContainerSpec init = new ContainerSpec(base.init().image(), base.init().argv(),
                Map.of("TEST_TOKEN", FETCH_TOKEN), base.init().mounts());
        ContainerSpec agent = new ContainerSpec(base.agent().image(), base.agent().argv(),
                Map.of("TEST_API_KEY", PLACEHOLDER), base.agent().mounts());
        ContainerSpec model = new ContainerSpec(base.modelProxy().image(), base.modelProxy().argv(),
                Map.of("TEST_REAL_KEY", MODEL_SECRET), base.modelProxy().mounts());
        return base.withInit(init).withAgent(agent).withModelProxy(model);
    }

    private static Secret named(List<Secret> secrets, String name) {
        return secrets.stream().filter(secret -> secret.getMetadata().getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no Secret " + name));
    }

    private static String value(Secret secret, String key) {
        String encoded = secret.getData().get(key);
        return encoded == null ? null : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String everything(Secret secret) {
        StringBuilder text = new StringBuilder();
        secret.getData().values().forEach(encoded ->
                text.append(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)).append('\n'));
        return text.toString();
    }

    @Test
    void everyContainerGetsItsOwnSecretAndNoValueAppearsTwice() {
        List<Secret> secrets = UnitSecrets.of(spec(), CONFIG, ANCHOR);
        for (Secret secret : secrets) {
            String name = secret.getMetadata().getName();
            assertEquals(name.equals(UnitNames.environmentSecret(RUN, "fetch")),
                    everything(secret).contains(FETCH_TOKEN), name);
            assertEquals(name.equals(UnitNames.environmentSecret(RUN, "model")),
                    everything(secret).contains(MODEL_SECRET), name);
        }
    }

    @Test
    void theAgentsSecretHoldsThePlaceholderNeverTheRealCredential() {
        Secret agent = named(UnitSecrets.of(spec(), CONFIG, ANCHOR), UnitNames.environmentSecret(RUN, "agent"));
        assertEquals(PLACEHOLDER, value(agent, "TEST_API_KEY"));
        assertFalse(everything(agent).contains(MODEL_SECRET));
        assertNull(agent.getData().get("TEST_REAL_KEY"));
    }

    @Test
    void theInputsSecretHoldsEachFileByteForByte() {
        RunUnitSpec spec = TestUnits.inputsOfTwoHundredTwentyFourKibibytes(RUN);
        Secret inputs = named(UnitSecrets.of(spec, CONFIG, ANCHOR), UnitNames.inputsSecret(RUN));
        assertEquals(spec.inputs().files().size(), inputs.getData().size());
        for (Map.Entry<String, String> file : spec.inputs().files().entrySet()) {
            assertEquals(file.getValue(), value(inputs, file.getKey()));
        }
    }

    @Test
    void aReviewWithNoInputFilesStillGetsAnEmptyInputsSecret() {
        RunUnitSpec spec = spec().withInputs(new dev.codespire.runtime.UnitInputs(
                TestUnits.INPUTS_VOLUME, TestUnits.INPUTS_PATH, Map.of()));
        Secret inputs = named(UnitSecrets.of(spec, CONFIG, ANCHOR), UnitNames.inputsSecret(RUN));
        assertTrue(inputs.getData().isEmpty());
    }

    @Test
    void theEgressProxyAcceptsLoopbackOnlyAndTheFetchProxyAllowsTheForgeHostAlone() {
        RunUnitSpec spec = spec();
        List<Secret> secrets = UnitSecrets.of(spec, CONFIG, ANCHOR);
        Secret egress = named(secrets, UnitNames.environmentSecret(RUN, "egress"));
        Secret fetch = named(secrets, UnitNames.environmentSecret(RUN, "fetchproxy"));

        String egressConf = value(egress, "SPIRE_EGRESS_CONF");
        assertTrue(egressConf.contains("Port 8888"));
        assertTrue(egressConf.contains("Allow 127.0.0.1"));
        assertEquals(TinyproxyConfig.filterFor(new EgressPolicy(EgressPolicy.Mode.ENFORCE, spec.egress().hosts())),
                value(egress, "SPIRE_EGRESS_FILTER"));

        String fetchConf = value(fetch, "SPIRE_EGRESS_CONF");
        assertTrue(fetchConf.contains("Port 8889"));
        assertFalse(fetchConf.contains("Allow"));
        assertEquals(TinyproxyConfig.filterFor(new EgressPolicy(EgressPolicy.Mode.ENFORCE, spec.fetchHosts())),
                value(fetch, "SPIRE_EGRESS_FILTER"));
    }

    @Test
    void aProxiedUnitAlwaysEnforcesWhateverModeTheSpecCarries() {
        RunUnitSpec report = TestUnits.proxiedReview(RUN);
        RunUnitSpec reporting = new RunUnitSpec(RUN, report.init(), report.agent(), report.publisher(),
                report.enterprise(), new EgressPolicy(EgressPolicy.Mode.REPORT, report.egress().hosts()),
                1L << 30, 1_000_000_000L, 1L << 30, Duration.ofMinutes(10))
                .withModelProxy(report.modelProxy()).withInputs(report.inputs()).withFetchHosts(report.fetchHosts());
        Secret egress = named(UnitSecrets.of(reporting, CONFIG, ANCHOR), UnitNames.environmentSecret(RUN, "egress"));
        assertTrue(value(egress, "SPIRE_EGRESS_CONF").contains("FilterDefaultDeny Yes"));
    }

    @Test
    void everySecretIsOwnedByTheAnchorAndCarriesTheUnitLabel() {
        for (Secret secret : UnitSecrets.of(spec(), CONFIG, ANCHOR)) {
            assertEquals(List.of(ANCHOR), secret.getMetadata().getOwnerReferences());
            assertEquals(UnitNames.digest(RUN), secret.getMetadata().getLabels().get(UnitNames.UNIT_LABEL));
            assertEquals("Opaque", secret.getType());
            assertTrue(secret.getImmutable());
        }
    }

    @Test
    void namesComeFromUnitNames() {
        Set<String> produced = new HashSet<>();
        UnitSecrets.of(spec(), CONFIG, ANCHOR).forEach(secret -> produced.add(secret.getMetadata().getName()));
        assertEquals(new HashSet<>(UnitNames.secretNames(RUN)), produced);
    }
}
