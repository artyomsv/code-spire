package dev.codespire.gateway.registry;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** CRUD for webhook registrations (spire-ui Settings -> Webhooks). Gateway-owned. */
@Path("/api/webhook-repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookRepoResource {

    static final String SCOPE_REPO = "repo";
    static final String SCOPE_ORG = "org";

    private static final Set<String> PROVIDER_TYPES = Set.of("github", "gitlab", "bitbucket-cloud");

    // A single path segment; leading/trailing-alphanumeric blocks '.'/'..' traversal.
    private static final String SEG = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?";
    private static final Pattern OWNER = Pattern.compile(SEG);
    private static final Pattern OWNER_REPO = Pattern.compile(SEG + "/" + SEG);

    @Inject
    WebhookRepoRegistry registry;

    @GET
    public List<WebhookRepoView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public WebhookRepoView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No webhook repo " + id));
    }

    @POST
    public Response create(WebhookRepoInput in) {
        validate(in, true);
        return Response.status(Response.Status.CREATED).entity(registry.create(in)).build();
    }

    @PUT
    @Path("/{id}")
    public WebhookRepoView update(@PathParam("id") String id, WebhookRepoInput in) {
        validate(in, false);
        return registry.update(uuid(id), in)
                .orElseThrow(() -> new NotFoundException("No webhook repo " + id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No webhook repo " + id);
        }
        return Response.noContent().build();
    }

    private void validate(WebhookRepoInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("Webhook repo body is required");
        }
        if (in.providerType() == null || !PROVIDER_TYPES.contains(in.providerType())) {
            throw new BadRequestException("providerType must be one of: "
                    + String.join(", ", PROVIDER_TYPES.stream().sorted().toList()));
        }
        String target = in.target() == null ? "" : in.target().trim();
        switch (in.scope() == null ? "" : in.scope()) {
            case SCOPE_REPO -> {
                if (!OWNER_REPO.matcher(target).matches()) {
                    throw new BadRequestException("repo scope: target must be 'owner/repo'");
                }
            }
            case SCOPE_ORG -> {
                if (!OWNER.matcher(target).matches()) {
                    throw new BadRequestException("org scope: target must be the 'owner' (no slash)");
                }
            }
            default -> throw new BadRequestException("scope must be 'repo' or 'org'");
        }
        if (creating && (in.secret() == null || in.secret().isBlank())) {
            throw new BadRequestException("secret (webhook HMAC/token secret) is required");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid webhook repo id");
        }
    }
}
