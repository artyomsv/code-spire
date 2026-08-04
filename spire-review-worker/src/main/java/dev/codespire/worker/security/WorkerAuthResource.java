package dev.codespire.worker.security;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * This service's own login entry point — see {@code GatewayAuthResource} for why each prefix needs
 * one of these.
 *
 * <p>The dashboard reads a review's assembled context from {@code /wk}, so a review detail page that
 * could not obtain a {@code /wk} session rendered its Context card as a failed request while every
 * other card on the same page worked.
 *
 * <p>Both roles: reading a review's context is part of reading the review.
 */
@Path("/wk")
public class WorkerAuthResource {

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login() {
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * A code-flow callback the framework did not claim — see {@code AuthResource.staleCallback} in the
     * orchestrator for the full reasoning.
     */
    @GET
    @Path("/auth/callback")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response staleCallback() {
        return Response.seeOther(URI.create("/")).build();
    }
}
