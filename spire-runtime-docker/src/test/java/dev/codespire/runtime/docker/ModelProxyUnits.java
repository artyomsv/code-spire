package dev.codespire.runtime.docker;

import com.github.dockerjava.api.model.Container;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model proxy integration tests' world over {@link DockerUnits}: a stand-in vendor on the default bridge, and units
 * whose agent reaches it only through a model proxy and the egress proxy behind it.
 */
final class ModelProxyUnits {

    static final String MODEL_SECRET = "TEST-SECRET-MODEL-PROXY";

    /** Report mode with one declared host, which a proxied unit's egress proxy enforces anyway. */
    static final EgressPolicy PROXIED_REPORT =
            new EgressPolicy(EgressPolicy.Mode.REPORT, Set.of("declared.example.invalid"));

    static final String MODEL_CALL = modelCall("MODEL", RunUnitSpec.MODEL_PROXY_ADDRESS + "/v1/responses");

    static final String REACHED_MODEL = "MODEL-REACHED";

    /** Answers every GET and POST with the status in TEST_STATUS and a body the agent can recognise. */
    private static final String UPSTREAM_STUB = String.join("\n",
            "import http.server, os",
            "STATUS = int(os.environ['TEST_STATUS'])",
            "class Stub(http.server.BaseHTTPRequestHandler):",
            "    def answer(self):",
            "        self.rfile.read(int(self.headers.get('Content-Length') or 0))",
            "        body = b'TEST-UPSTREAM-ANSWER'",
            "        self.send_response(STATUS)",
            "        self.send_header('Content-Length', str(len(body)))",
            "        self.end_headers()",
            "        self.wfile.write(body)",
            "    do_GET = answer",
            "    do_POST = answer",
            "http.server.ThreadingHTTPServer(('', 80), Stub).serve_forever()");

    private static final String UPSTREAM_IMAGE = "python:3.13-alpine";

    private final DockerUnits units;

    ModelProxyUnits(DockerUnits units) {
        this.units = units;
    }

    /** Both images a proxied unit runs: the egress proxy and the model proxy. */
    static void buildTheImages() throws Exception {
        DockerUnits.buildTheEgressProxyImage();
        DockerUnits.buildTheModelProxyImage();
    }

    /** The stand-in vendor a proxied unit's model proxy forwards to, and the egress policy the unit is built with. */
    record Vendor(String upstream, EgressPolicy egress) {

        /** {@link #PROXIED_REPORT}: report mode, the upstream not among its hosts. */
        static Vendor reporting(String upstream) {
            return new Vendor(upstream, PROXIED_REPORT);
        }

        /** Enforce mode with the upstream as the one declared host. */
        static Vendor enforcing(String upstream) {
            return new Vendor(upstream, new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of(upstream)));
        }
    }

    /**
     * A plain-HTTP stand-in for the model vendor on the default bridge, reached by address since that bridge has no
     * DNS.
     * The model proxy reaches it only through the egress proxy, which is what these tests are about.
     */
    String startUpstream(int status) {
        return units.startOnTheBridge(UPSTREAM_IMAGE, List.of("TEST_STATUS=" + status), "python", "-c", UPSTREAM_STUB);
    }

    /** A unit whose agent runs {@code agentScript} beside a model proxy forwarding /v1/responses to the vendor. */
    RunUnitSpec proxiedUnit(String id, String agentScript, Vendor vendor) {
        ContainerSpec modelProxy = new ContainerSpec(DockerUnits.MODEL_PROXY_IMAGE, List.of(), Map.of(
                "SPIRE_MODEL_UPSTREAM", "http://" + vendor.upstream(),
                "SPIRE_MODEL_INJECTION", "BEARER",
                "SPIRE_MODEL_PATHS_EXACT", "/v1/responses",
                "SPIRE_MODEL_SECRET", MODEL_SECRET,
                "SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true"), List.of());
        return units.egressUnit(id, agentScript, vendor.egress()).withModelProxy(modelProxy);
    }

    /** One model call, printed as a verdict: REACHED only when the upstream's own answer came back. */
    static String modelCall(String label, String url) {
        return "if wget -Y off -T 10 -q -O - --post-data '{}' " + url + " 2>/dev/null | grep -q TEST-UPSTREAM-ANSWER; "
                + "then echo " + label + "-REACHED; else echo " + label + "-FAILED; fi; ";
    }

    Container modelProxyOf(String runId) {
        return units.containerOf(runId, ModelProxyContainer.ROLE);
    }
}
