package org.moqui.device.gateway.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

public final class GatewayExceptionMappers {
    private GatewayExceptionMappers() {}

    public record ErrorPayload(String error, String message) {}

    @Provider
    @ApplicationScoped
    public static class IllegalArgumentMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return json(Response.Status.BAD_REQUEST, "bad_request", exception.getMessage());
        }
    }

    @Provider
    @ApplicationScoped
    public static class IllegalStateMapper implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException exception) {
            return json(Response.Status.SERVICE_UNAVAILABLE, "service_unavailable", exception.getMessage());
        }
    }

    @Provider
    @ApplicationScoped
    public static class GenericMapper implements ExceptionMapper<Exception> {
        @Override
        public Response toResponse(Exception exception) {
            return json(Response.Status.INTERNAL_SERVER_ERROR, "internal_error", exception.getMessage());
        }
    }

    private static Response json(Response.Status status, String error, String message) {
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorPayload(error, message))
            .build();
    }
}
