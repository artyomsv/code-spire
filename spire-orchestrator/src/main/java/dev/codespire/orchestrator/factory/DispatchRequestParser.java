package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

import java.util.regex.Pattern;

/**
 * Turns a {@link RunResource.DispatchRequest} into validated inputs, or a 400 that says which field
 * and why. Every rule here exists so a bad value is refused at the door rather than by a container
 * after the agent has run and been paid for — the branch name the publisher would refuse, the
 * abbreviated commit JGit would reject, the prompt that would not fit a Kafka record.
 */
final class DispatchRequestParser {

    /** A single path segment's charset — the same guard the manual-register endpoint applies. */
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    /**
     * A FULL object id, not an abbreviation. The publisher hands this to JGit's ObjectId.fromString,
     * which accepts exactly 40 hex characters; a 7-character prefix passed every check here, was
     * cloned and run against, and then failed every bundle as BUNDLE_UNREADABLE — after the agent
     * had been paid for. SHA-256 repositories (64) are not what the publisher's git library speaks.
     */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");

    /** Reaches the harness's argv as {@code --model <value>}; a flag-shaped value is refused here. */
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    /** The prompt rides every command copy, the DLQ row and the agent's environment. */
    private static final int MAX_PROMPT_CHARS = 64 * 1024;

    private static final String DEFAULT_BASE_BRANCH = "main";

    /** The branch is {@code spire/<subject>}; git refuses these two shapes, so refuse them here. */
    private static final String REF_DOTDOT = "..";

    private static final String REF_LOCK_SUFFIX = ".lock";

    private static final Pattern REF_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private static final int MAX_REF_CHARS = 255;

    private DispatchRequestParser() {
    }

    /** The validated inputs a dispatch is built from. */
    record Parsed(ScmType scmType, String workspace, String slug, String baseCommit, String prompt,
                  String harness, String agentImage, String model, String baseBranch, String subject) {
    }

    static Parsed parse(RunResource.DispatchRequest req, FactoryConfig config) {
        if (req == null) {
            throw badRequest("a request body is required");
        }
        ScmType scmType = ScmType.fromProviderType(req.providerType())
                .orElseThrow(() -> badRequest("unknown providerType: " + req.providerType()));
        String workspace = namespace(req.workspace());
        String slug = segment(req.slug(), "slug");
        String baseCommit = required(req.baseCommit(), "baseCommit");
        if (!COMMIT.matcher(baseCommit).matches()) {
            throw badRequest("baseCommit must be a full 40-character hex commit id");
        }
        String prompt = required(req.prompt(), "prompt");
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw badRequest("prompt exceeds " + MAX_PROMPT_CHARS + " characters");
        }
        String harness = required(req.harness(), "harness");
        String agentImage = config.agentImage().get(harness);
        if (agentImage == null) {
            throw badRequest("no agent image is configured for harness '" + harness
                    + "'; this deployment configures " + config.agentImage().keySet()
                    + " (spire.factory.agent-image.<harness>)");
        }
        String model = required(req.model(), "model");
        if (!MODEL.matcher(model).matches()) {
            throw badRequest("model is not a valid model name");
        }
        String baseBranch = req.baseBranch() == null || req.baseBranch().isBlank()
                ? DEFAULT_BASE_BRANCH : refName(req.baseBranch(), "baseBranch");
        return new Parsed(scmType, workspace, slug, baseCommit, prompt, harness, agentImage, model,
                baseBranch, subject(req.subject(), baseCommit));
    }

    /**
     * The subject names the run and its branch. Validated like a path segment, plus the two shapes
     * git refuses in a ref, so a bad name is a 400 here rather than a publisher that refuses to
     * start after the agent has already run and been paid for.
     */
    private static String subject(String raw, String baseCommit) {
        if (raw == null || raw.isBlank()) {
            return "manual-" + baseCommit.substring(0, 7);
        }
        String subject = segment(raw, "subject");
        if (subject.contains(REF_DOTDOT) || subject.endsWith(REF_LOCK_SUFFIX)) {
            throw badRequest("subject must be usable as a branch name: no '..' and no '.lock' suffix");
        }
        return subject;
    }

    private static String namespace(String value) {
        String v = required(value, "workspace");
        if (v.startsWith("/") || v.endsWith("/")) {
            throw badRequest("workspace must not start or end with '/'");
        }
        for (String part : v.split("/", -1)) {
            if (!SLUG.matcher(part).matches()) {
                throw badRequest("workspace is not a valid repository namespace");
            }
        }
        return v;
    }

    private static String segment(String value, String field) {
        String v = required(value, field);
        if (!SLUG.matcher(v).matches()) {
            throw badRequest(field + " is not a valid repository segment");
        }
        return v;
    }

    private static String refName(String value, String field) {
        if (value.length() > MAX_REF_CHARS || value.startsWith("/") || value.endsWith("/")
                || value.contains(REF_DOTDOT) || value.endsWith(REF_LOCK_SUFFIX)) {
            throw badRequest(field + " is not a valid branch name");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.startsWith(".") || part.startsWith("-") || !REF_SEGMENT.matcher(part).matches()) {
                throw badRequest(field + " is not a valid branch name");
            }
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        return value.strip();
    }

    /** A 400 whose body says why — the bare exception's message never reaches the client. */
    static BadRequestException badRequest(String message) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }
}
