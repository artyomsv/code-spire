package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ContainerConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p><b>A false accusation is worse than a miss.</b> This is a checker, so an operator acts on what
 * it says: a clause reported FAIL sends them to change an image that may be correct. Every path that
 * cannot answer therefore reports {@link #unknown} — a checker problem, said as one — rather than
 * letting "no answer" collapse into "the answer was no". A review found three clauses doing exactly
 * that collapse, and the resulting report blamed a conforming entrypoint for three defects it did
 * not have.
 */
public final class AgentImageVerifier {

    /** Where the reference entrypoint writes bundles, and where a probe looks for them. */
    static final String HANDOFF = "/handoff";

    /** The workspace the entrypoint commits in. */
    static final String WORKSPACE = "/workspace";

    /** The sentinel the stub harness looks for on stdin. Obviously synthetic, never a real prompt. */
    static final String PROMPT_SENTINEL = "SPIRE-CONFORMANCE-PROMPT";

    /** The suffix the handoff protocol names. */
    private static final String BUNDLE_SUFFIX = ".bundle";

    /** The probe script's answer keys, written once and read once. A typo would be a silent null. */
    private static final String GIT_KEY = "git";

    private static final String CA_KEY = "ca";

    private static final String WORKSPACE_KEY = "workspace";

    private static final String HANDOFF_KEY = "handoff";

    private static final String YES = "yes";

    /** The longest image-controlled string that reaches a report line. */
    private static final int MAX_QUOTED_CHARS = 200;

    /**
     * What a clause says when it passes and when it fails.
     *
     * <p>A record rather than two more string parameters: {@code booleanClause} took four, over the
     * rule, and the two prose arguments were transposable with no compiler complaint — a
     * transposition prints the failure text on a passing image and nothing catches it.
     */
    private record ClauseText(String id, String onPass, String onFail) {
    }

    private static final ClauseText GIT_CLAUSE = new ClauseText(Clauses.GIT,
            "a git binary is on PATH",
            "no git binary: the handoff is git bundles, so checkpointing would silently produce "
                    + "nothing");

    private static final ClauseText CA_CLAUSE = new ClauseText(Clauses.CA_CERTIFICATES,
            "a system trust store is present",
            "no system trust store: every TLS call fails with UnknownIssuer, and at least one "
                    + "harness retries silently");

    private static final ClauseText STDIN_CLAUSE = new ClauseText(Clauses.PROMPT_ON_STDIN,
            "the harness received the work item on stdin",
            "the harness did not see the work item on stdin; on argv it is visible in the host "
                    + "process list and can be committed by an autosave");

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
                bounded(String.join(" ", entrypoint)));
    }

    /**
     * Root is uid 0, however the image spells it.
     *
     * <p>Decided on the UID FIELD alone. An earlier version tested the whole string against
     * {@code "root"}, {@code "0"} and a {@code "0:"} prefix — so {@code USER root:root}, a
     * documented and common Dockerfile form, matched none of them and an image running as uid 0 was
     * reported as conforming. That is the one clause whose whole purpose is that this container runs
     * untrusted model output at full shell access.
     */
    private static ConformanceReport.Verification nonRoot(InspectImageResponse inspected) {
        String user = config(inspected) == null ? null : config(inspected).getUser();
        String uid = user == null ? "" : user.split(":", 2)[0].trim();
        if (uid.isEmpty() || uid.equals("root") || uid.equals("0")) {
            return ConformanceReport.Verification.failed(Clauses.NON_ROOT,
                    "runs as root (USER=" + (user == null || user.isBlank()
                            ? "<unset>" : bounded(user))
                            + "); this container runs untrusted model output at full shell access");
        }
        return ConformanceReport.Verification.passed(Clauses.NON_ROOT, "USER=" + bounded(user));
    }

    private static ContainerConfig config(InspectImageResponse inspected) {
        return inspected == null ? null : inspected.getConfig();
    }

    /**
     * The clauses that need the image running.
     *
     * <p>One container for the four cheap ones: each probe costs a container create, start, wait and
     * remove, and a check an operator runs before every deploy is one they stop running if it takes
     * a minute. The script writes one line per clause and the results are read back by key.
     */
    private List<ConformanceReport.Verification> runtimeClauses(String image) {
        ImageProbe.Result result;
        try {
            result = probe.run(image, List.of("sh", "-c", runtimeProbeScript()));
        } catch (RuntimeException unreachable) {
            // Every runtime clause is unknown, and saying so is the honest answer. Reporting them
            // as failures would send an operator to fix an image that may be perfectly good.
            return unreachableClauses(unreachable.getMessage());
        }

        Map<String, String> answers = parse(result.output());
        List<ConformanceReport.Verification> verified = new ArrayList<>();
        verified.add(mountPoints(answers));
        verified.add(booleanClause(GIT_CLAUSE, answers.get(GIT_KEY)));
        verified.add(booleanClause(CA_CLAUSE, answers.get(CA_KEY)));
        verified.addAll(handoffProtocol(image));
        return verified;
    }

    /**
     * The one shell program the cheap clauses are decided by.
     *
     * <p>A text block, because the earlier concatenated form hid a real defect: the trust-store test
     * read {@code A || B || C && D}, and POSIX gives {@code &&} and {@code ||} equal precedence with
     * left associativity — so {@code D} gated all three alternatives and an image whose store is
     * only {@code /etc/ssl/cert.pem} was told it had none. The third alternative is braced now, and
     * the shape is visible.
     *
     * <p>Ownership as well as writability for the mount points. Root can write a 1001-owned
     * directory, so writability alone passed the one image where the mismatch was real — and that
     * mismatch is what makes git refuse the workspace as dubiously owned.
     */
    private static String runtimeProbeScript() {
        return String.join("\n",
                "echo " + GIT_KEY + "=$(command -v git >/dev/null 2>&1 && echo " + YES + " || echo no)",
                "echo " + CA_KEY + "=$({ [ -f /etc/ssl/certs/ca-certificates.crt ] "
                        + "|| [ -f /etc/ssl/cert.pem ] "
                        + "|| { [ -d /etc/ssl/certs ] && [ -n \"$(ls /etc/ssl/certs 2>/dev/null)\" ]; }; } "
                        + "&& echo " + YES + " || echo no)",
                ownershipProbe(WORKSPACE_KEY, WORKSPACE),
                ownershipProbe(HANDOFF_KEY, HANDOFF));
    }

    private static String ownershipProbe(String key, String path) {
        return "echo " + key + "=$( [ -d " + path + " ] && [ -w " + path + " ] "
                + "&& [ \"$(stat -c %u " + path + " 2>/dev/null)\" = \"$(id -u)\" ] "
                + "&& echo " + YES + " || echo no)";
    }

    /**
     * Both mount points, or a clear statement that the probe answered about neither.
     *
     * <p>The null path is not decoration: without it, a probe that never ran a shell at all (an
     * image with no {@code sh}, an exec-format failure) produced "missing or not writable by the run
     * user" beside two clauses correctly reading NOT CHECKED — one report, two standards of honesty.
     */
    private static ConformanceReport.Verification mountPoints(Map<String, String> answers) {
        String workspace = answers.get(WORKSPACE_KEY);
        String handoff = answers.get(HANDOFF_KEY);
        if (workspace == null && handoff == null) {
            return unknown(Clauses.MOUNT_POINTS, "the probe returned no answer for this clause");
        }
        if (YES.equals(workspace) && YES.equals(handoff)) {
            return ConformanceReport.Verification.passed(Clauses.MOUNT_POINTS,
                    WORKSPACE + " and " + HANDOFF + " exist and belong to the run user");
        }
        List<String> wrong = new ArrayList<>();
        if (!YES.equals(workspace)) {
            wrong.add(WORKSPACE);
        }
        if (!YES.equals(handoff)) {
            wrong.add(HANDOFF);
        }
        return ConformanceReport.Verification.failed(Clauses.MOUNT_POINTS,
                String.join(" and ", wrong) + " missing, or not owned and writable by the run user; "
                        + "a fresh volume inherits the directory's ownership, so the agent could not "
                        + "write its own workspace and git would refuse it as dubiously owned");
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
                "grep -q " + PROMPT_SENTINEL + " /tmp/seen && echo stdin=" + YES + " || echo stdin=no",
                "cd " + WORKSPACE,
                "echo conformance > probe.txt");

        ImageProbe.Result result;
        try {
            result = probe.runAgent(image, List.of("sh", "-c", stub), PROMPT_SENTINEL);
        } catch (RuntimeException unreachable) {
            return handoffUnknown(unreachable.getMessage());
        }
        if (!result.started()) {
            // The entrypoint never reached the harness. Blaming the three clauses would name three
            // specific defects the image does not have — measured, on a conforming entrypoint.
            return handoffUnknown(result.output());
        }

        List<ConformanceReport.Verification> verified = new ArrayList<>();
        verified.add(booleanClause(STDIN_CLAUSE,
                result.output().contains("stdin=" + YES) ? YES : "no"));
        verified.add(bundlesClause(result));
        verified.add(doneClause(result));
        return verified;
    }

    private static List<ConformanceReport.Verification> handoffUnknown(String why) {
        return List.of(unknown(Clauses.PROMPT_ON_STDIN, why),
                unknown(Clauses.HANDOFF_BUNDLES, why),
                unknown(Clauses.HANDOFF_DONE_LAST, why));
    }

    private static ConformanceReport.Verification bundlesClause(ImageProbe.Result result) {
        return result.handoff().stream().anyMatch(name -> name.endsWith(BUNDLE_SUFFIX))
                ? ConformanceReport.Verification.passed(Clauses.HANDOFF_BUNDLES,
                        "commits left as bundles on " + HANDOFF)
                : ConformanceReport.Verification.failed(Clauses.HANDOFF_BUNDLES,
                        "no *" + BUNDLE_SUFFIX + " on " + HANDOFF + " after the harness committed; "
                                + "this container holds no credential, so a bundle is the only way "
                                + "work leaves it");
    }

    /**
     * Three answers, not two.
     *
     * <p>An absent {@code DONE} and a {@code DONE} written early both mean the clause fails, and
     * they call for opposite fixes — so a single boolean printed "DONE was not written last" about
     * an entrypoint that never wrote one at all, while the shell had already computed the
     * distinction and Java discarded it.
     */
    private static ConformanceReport.Verification doneClause(ImageProbe.Result result) {
        return switch (result.done()) {
            case WRITTEN_LAST -> ConformanceReport.Verification.passed(Clauses.HANDOFF_DONE_LAST,
                    "DONE was written after the last bundle");
            case BUNDLE_AFTER_DONE -> ConformanceReport.Verification.failed(Clauses.HANDOFF_DONE_LAST,
                    "a bundle was written AFTER DONE; the publisher treats DONE as \"everything is "
                            + "here\" and would drain before that bundle existed");
            case NEVER_WRITTEN -> ConformanceReport.Verification.failed(Clauses.HANDOFF_DONE_LAST,
                    "DONE was never written; the publisher waits for it, so it would drain only on "
                            + "its timeout and report whatever it had");
        };
    }

    private static ConformanceReport.Verification booleanClause(ClauseText text, String answer) {
        if (answer == null) {
            return unknown(text.id(), "the probe returned no answer for this clause");
        }
        return YES.equals(answer)
                ? ConformanceReport.Verification.passed(text.id(), text.onPass())
                : ConformanceReport.Verification.failed(text.id(), text.onFail());
    }

    /**
     * A clause the checker could not reach.
     *
     * <p>Reported as a FAILURE in the report — a clause silently omitted reads as a shorter contract
     * — but marked, so {@link ConformanceReport#anyNotChecked()} can tell the CLI to exit "could not
     * check" rather than "this image is wrong". Those call for opposite actions.
     */
    static ConformanceReport.Verification unknown(String id, String why) {
        return ConformanceReport.Verification.notChecked(id,
                bounded(why == null ? "the probe gave no reason" : why));
    }

    /**
     * Every clause that needs a container, derived rather than listed.
     *
     * <p>A hardcoded list here would silently omit the ninth clause somebody adds, and the omission
     * is the failure {@code unknown} exists to prevent. The two config clauses are answered without
     * a container, so they are the exclusion.
     */
    private static List<ConformanceReport.Verification> unreachableClauses(String why) {
        return Clauses.VERIFIED.stream()
                .filter(id -> !id.equals(Clauses.ENTRYPOINT) && !id.equals(Clauses.NON_ROOT))
                .map(id -> unknown(id, why))
                .toList();
    }

    private static List<ConformanceReport.Declaration> declarations(InspectImageResponse inspected) {
        Map<String, String> labels = config(inspected) == null || config(inspected).getLabels() == null
                ? Map.of() : config(inspected).getLabels();
        return List.of(
                new ConformanceReport.Declaration(Clauses.TOOLCHAIN,
                        bounded(labels.get(Clauses.TOOLCHAIN_LABEL)),
                        "verifying this needs the repository the image would build, which a "
                                + "checker holding only the image does not have"),
                new ConformanceReport.Declaration(Clauses.HARNESS,
                        bounded(labels.get(Clauses.HARNESS_LABEL)),
                        "verifying this needs a model credential and a paid call, so a "
                                + "conformance check would cost money to run"));
    }

    /**
     * Bounds an image-controlled string before it reaches a report line.
     *
     * <p>A label has no length limit, and neither does an ENTRYPOINT array. Control characters are
     * removed separately, in {@link ConformanceReport}'s own constructors, so nothing can reach
     * {@code render()} carrying them whatever path it took.
     */
    private static String bounded(String value) {
        if (value == null || value.length() <= MAX_QUOTED_CHARS) {
            return value;
        }
        return value.substring(0, MAX_QUOTED_CHARS) + "…";
    }

    private static Map<String, String> parse(String output) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                answers.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return answers;
    }
}
