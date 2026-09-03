package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ContainerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks one image against the published agent-image contract (FR-F13).
 *
 * <p>Two kinds of clause, kept apart by the report's own shape — see {@link ConformanceReport}.
 * Everything this class can prove it proves; everything it cannot, it declares as unproven rather
 * than guessing.
 *
 * <p><b>Some clauses need the image RUN, not merely read.</b> Directory ownership, the presence of a
 * binary, and the entrypoint's own behaviour are not in the image config: an image that declares
 * {@code USER 1001} may still have a root-owned {@code /workspace}, and that combination is the one
 * that produces a run which clones correctly and then does nothing. So the checker starts one
 * short-lived container per probe rather than reading metadata and hoping.
 */
public final class AgentImageVerifier {

    /** Where the reference entrypoint writes bundles, and where a probe looks for them. */
    static final String HANDOFF = "/handoff";

    /** The workspace the entrypoint commits in. */
    static final String WORKSPACE = "/workspace";

    private final ImageProbe probe;

    public AgentImageVerifier(ImageProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** The default probe, against the daemon this host talks to. */
    public static AgentImageVerifier againstDocker(DockerClient client) {
        return new AgentImageVerifier(new DockerImageProbe(client));
    }

    public ConformanceReport verify(String image) {
        Objects.requireNonNull(image, "image");
        InspectImageResponse inspected = probe.inspect(image);

        List<ConformanceReport.Verification> verified = new ArrayList<>();
        verified.add(entrypoint(inspected));
        verified.add(nonRoot(inspected));
        verified.addAll(runtimeClauses(image));

        return new ConformanceReport(image, verified, declarations(inspected));
    }

    private static ConformanceReport.Verification entrypoint(InspectImageResponse inspected) {
        String[] entrypoint = config(inspected) == null ? null : config(inspected).getEntrypoint();
        if (entrypoint == null || entrypoint.length == 0) {
            return ConformanceReport.Verification.failed(Clauses.ENTRYPOINT,
                    "the image declares no ENTRYPOINT, so the harness would run with none of the "
                            + "handoff protocol around it and the run would produce nothing");
        }
        return ConformanceReport.Verification.passed(Clauses.ENTRYPOINT,
                String.join(" ", entrypoint));
    }

    /**
     * An empty {@code USER} is root.
     *
     * <p>Docker's default when the instruction is absent, which makes "no USER" and "USER root" the
     * same fact — and the absent form is the one an author does not notice.
     */
    private static ConformanceReport.Verification nonRoot(InspectImageResponse inspected) {
        String user = config(inspected) == null ? null : config(inspected).getUser();
        if (user == null || user.isBlank() || user.equals("root") || user.startsWith("0:")
                || user.equals("0")) {
            return ConformanceReport.Verification.failed(Clauses.NON_ROOT,
                    "runs as root (USER=" + (user == null || user.isBlank() ? "<unset>" : user)
                            + "); this container runs untrusted model output at full shell access");
        }
        return ConformanceReport.Verification.passed(Clauses.NON_ROOT, "USER=" + user);
    }

    private static ContainerConfig config(InspectImageResponse inspected) {
        return inspected == null ? null : inspected.getConfig();
    }

    /**
     * The clauses that need the image running.
     *
     * <p>One container, not six: each probe costs a container create, start, wait and remove, and a
     * conformance check an operator runs before every deploy is one they will stop running if it
     * takes a minute. The script writes one line per clause and the results are read back by
     * prefix.
     */
    private List<ConformanceReport.Verification> runtimeClauses(String image) {
        String script = String.join("; ",
                "id -u > /tmp/uid 2>/dev/null || true",
                "echo git=$(command -v git >/dev/null 2>&1 && echo yes || echo no)",
                "echo ca=$( { [ -f /etc/ssl/certs/ca-certificates.crt ] "
                        + "|| [ -f /etc/ssl/cert.pem ] "
                        + "|| [ -d /etc/ssl/certs ] && [ -n \"$(ls /etc/ssl/certs 2>/dev/null)\" ]; } "
                        + "&& echo yes || echo no)",
                "echo workspace=$( [ -d " + WORKSPACE + " ] && [ -w " + WORKSPACE + " ] "
                        + "&& echo writable || echo no)",
                "echo handoff=$( [ -d " + HANDOFF + " ] && [ -w " + HANDOFF + " ] "
                        + "&& echo writable || echo no)");

        ImageProbe.Result result;
        try {
            result = probe.run(image, List.of("sh", "-c", script));
        } catch (RuntimeException unreachable) {
            // Every runtime clause is unknown, and saying so is the honest answer. Reporting them
            // as failures would send an operator to fix an image that may be perfectly good.
            return unreachableClauses(unreachable.getMessage());
        }

        Map<String, String> answers = parse(result.output());
        List<ConformanceReport.Verification> verified = new ArrayList<>();
        verified.add(mountPoints(answers));
        verified.add(booleanClause(Clauses.GIT, answers.get("git"),
                "a git binary is on PATH", "no git binary: the handoff is git bundles, so "
                        + "checkpointing would silently produce nothing"));
        verified.add(booleanClause(Clauses.CA_CERTIFICATES, answers.get("ca"),
                "a system trust store is present", "no system trust store: every TLS call fails "
                        + "with UnknownIssuer, and at least one harness retries silently"));
        verified.addAll(handoffProtocol(image));
        return verified;
    }

    private static ConformanceReport.Verification mountPoints(Map<String, String> answers) {
        boolean workspace = "writable".equals(answers.get("workspace"));
        boolean handoff = "writable".equals(answers.get("handoff"));
        if (workspace && handoff) {
            return ConformanceReport.Verification.passed(Clauses.MOUNT_POINTS,
                    WORKSPACE + " and " + HANDOFF + " exist and belong to the run user");
        }
        List<String> wrong = new ArrayList<>();
        if (!workspace) {
            wrong.add(WORKSPACE);
        }
        if (!handoff) {
            wrong.add(HANDOFF);
        }
        return ConformanceReport.Verification.failed(Clauses.MOUNT_POINTS,
                String.join(" and ", wrong) + " missing or not writable by the run user; a fresh "
                        + "volume inherits the directory's ownership, so the agent could not write "
                        + "its own workspace");
    }

    /**
     * The entrypoint's own behaviour: the prompt on stdin, a bundle, and DONE last.
     *
     * <p>A stub harness rather than a real one — it reads stdin, writes what it saw, and makes one
     * commit. Running a real harness would need a model credential and would make a conformance
     * check cost money.
     */
    private List<ConformanceReport.Verification> handoffProtocol(String image) {
        String stub = String.join("; ",
                "cat > /tmp/seen",
                "grep -q SPIRE-CONFORMANCE-PROMPT /tmp/seen && echo stdin=yes || echo stdin=no",
                "cd " + WORKSPACE,
                "echo conformance > probe.txt");

        ImageProbe.Result result;
        try {
            result = probe.runAgent(image, List.of("sh", "-c", stub), "SPIRE-CONFORMANCE-PROMPT");
        } catch (RuntimeException unreachable) {
            return List.of(
                    unknown(Clauses.PROMPT_ON_STDIN, unreachable.getMessage()),
                    unknown(Clauses.HANDOFF_BUNDLES, unreachable.getMessage()),
                    unknown(Clauses.HANDOFF_DONE_LAST, unreachable.getMessage()));
        }

        List<ConformanceReport.Verification> verified = new ArrayList<>();
        verified.add(booleanClause(Clauses.PROMPT_ON_STDIN,
                result.output().contains("stdin=yes") ? "yes" : "no",
                "the harness received the work item on stdin",
                "the harness did not see the work item on stdin; on argv it is printed by "
                        + "docker inspect and by the host process list"));
        verified.add(result.handoff().stream().anyMatch(name -> name.endsWith(".bundle"))
                ? ConformanceReport.Verification.passed(Clauses.HANDOFF_BUNDLES,
                        "commits left as bundles on " + HANDOFF)
                : ConformanceReport.Verification.failed(Clauses.HANDOFF_BUNDLES,
                        "no *.bundle on " + HANDOFF + " after the harness committed; this container "
                                + "holds no credential, so a bundle is the only way work leaves it"));
        verified.add(result.doneWrittenLast()
                ? ConformanceReport.Verification.passed(Clauses.HANDOFF_DONE_LAST,
                        "DONE was written after the last bundle")
                : ConformanceReport.Verification.failed(Clauses.HANDOFF_DONE_LAST,
                        "DONE was not written last; the publisher treats it as \"everything is "
                                + "here\" and would drain before the final bundle was written"));
        return verified;
    }

    private static ConformanceReport.Verification booleanClause(String id, String answer,
                                                                String onPass, String onFail) {
        if (answer == null) {
            return unknown(id, "the probe returned no answer for this clause");
        }
        return "yes".equals(answer) || "writable".equals(answer)
                ? ConformanceReport.Verification.passed(id, onPass)
                : ConformanceReport.Verification.failed(id, onFail);
    }

    /**
     * A clause the checker could not reach.
     *
     * <p>Reported as a FAILURE, not silently omitted: a conformance report missing a clause reads as
     * a shorter contract, and "the daemon was unreachable" must not be mistaken for "this image is
     * fine". The detail says which it is.
     */
    private static ConformanceReport.Verification unknown(String id, String why) {
        return ConformanceReport.Verification.failed(id,
                "NOT CHECKED — " + why + ". This is a checker problem, not necessarily an image one.");
    }

    private static List<ConformanceReport.Verification> unreachableClauses(String why) {
        List<ConformanceReport.Verification> verified = new ArrayList<>();
        for (String id : List.of(Clauses.MOUNT_POINTS, Clauses.GIT, Clauses.CA_CERTIFICATES,
                Clauses.PROMPT_ON_STDIN, Clauses.HANDOFF_BUNDLES, Clauses.HANDOFF_DONE_LAST)) {
            verified.add(unknown(id, why));
        }
        return verified;
    }

    private static List<ConformanceReport.Declaration> declarations(InspectImageResponse inspected) {
        Map<String, String> labels = config(inspected) == null || config(inspected).getLabels() == null
                ? Map.of() : config(inspected).getLabels();
        return List.of(
                new ConformanceReport.Declaration(Clauses.TOOLCHAIN,
                        labels.get(Clauses.TOOLCHAIN_LABEL),
                        "verifying this needs the repository the image would build, which a "
                                + "checker holding only the image does not have"),
                new ConformanceReport.Declaration(Clauses.HARNESS,
                        labels.get(Clauses.HARNESS_LABEL),
                        "verifying this needs a model credential and a paid call, so a "
                                + "conformance check would cost money to run"));
    }

    private static Map<String, String> parse(String output) {
        Map<String, String> answers = new java.util.LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                answers.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return answers;
    }
}
