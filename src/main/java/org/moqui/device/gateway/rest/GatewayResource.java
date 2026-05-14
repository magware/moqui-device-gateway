package org.moqui.device.gateway.rest;

import java.util.Base64;
import java.util.Map;

import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.moqui.device.gateway.service.GatewayRequestService;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST API to trigger standalone Camel device routes directly.
 *
 * In standalone mode the REST layer plays the dispatch role that in Moqui lives
 * in the Service Facade and in run#DeviceRequest. The Camel layer then stays
 * intentionally small and protocol-specific.
 */
@Path("/api")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GatewayResource {

    @Inject
    ProducerTemplate producer;

    @Inject
    GatewayRequestService gatewayRequestService;

    @Context
    HttpHeaders httpHeaders;

    @ConfigProperty(name = "gateway.api.auth.enabled", defaultValue = "true")
    boolean authEnabled;

    @ConfigProperty(name = "gateway.api.auth.header", defaultValue = "X-API-Key")
    String authHeader;

    @ConfigProperty(name = "gateway.api.auth.token", defaultValue = "change-me-in-production")
    String expectedToken;

    @POST
    @Path("/device-request/run/{requestName}")
    public Response runDeviceRequest(@PathParam("requestName") String requestName) {
        authorize();
        GatewayRequestService.RequestContext context = gatewayRequestService.loadRequestContext(requestName);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = producer.requestBody(resolveRoute(context), context, Map.class);
        return Response.ok(result).build();
    }

    /**
     * Backward-compatible alias kept for callers that already use the old
     * write-only endpoint.
     */
    @POST
    @Path("/device-request/write/{requestName}")
    public Response writeDeviceRequest(@PathParam("requestName") String requestName) {
        return runDeviceRequest(requestName);
    }

    @POST
    @Path("/device-request/subscribe/{requestName}")
    public Response subscribeDeviceRequest(@PathParam("requestName") String requestName) {
        authorize();
        GatewayRequestService.RequestContext context = gatewayRequestService.loadRequestContext(requestName);
        if (!isSubscribeRequest(context.requestTypeEnumId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "DeviceRequest " + requestName + " is not a subscribe request"))
                .build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = producer.requestBody(resolveSubscribeRoute(context), context, Map.class);
        return Response.ok(result).build();
    }

    @POST
    @Path("/device-request/unsubscribe/{requestName}")
    public Response unsubscribeDeviceRequest(@PathParam("requestName") String requestName) {
        authorize();
        GatewayRequestService.RequestContext context = gatewayRequestService.loadRequestContext(requestName);
        if (!"DrtUnsubscribe".equals(context.requestTypeEnumId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "DeviceRequest " + requestName + " is not an unsubscribe request"))
                .build();
        }
        return Response.ok(gatewayRequestService.unsubscribe(context)).build();
    }

    @POST
    @Path("/device-content/transfer/{requestName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response transferDeviceContent(@PathParam("requestName") String requestName, Map<String, Object> body) {
        authorize();
        if (body == null || !body.containsKey("filename") || !body.containsKey("contentBase64")) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "filename and contentBase64 are required"))
                .build();
        }
        GatewayRequestService.RequestContext context = gatewayRequestService.loadRequestContext(requestName);
        if (context.brokerUri() == null || context.brokerUri().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "DeviceRequest " + requestName + " has no brokerUri (transfer destination)."))
                .build();
        }
        String filename = (String) body.get("filename");
        byte[] fileBytes = Base64.getDecoder().decode((String) body.get("contentBase64"));
        producer.sendBodyAndHeaders("direct:transfer-device-content", fileBytes, Map.of(
            "CamelFileName", filename,
            "gatewayTransferUri", context.brokerUri()
        ));
        return Response.ok(Map.of(
            "status", "completed",
            "requestName", requestName,
            "filename", filename,
            "bytes", fileBytes.length,
            "transferUri", context.brokerUri()
        )).build();
    }

    @POST
    @Path("/device-config/export")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response exportDeviceConfig(Map<String, Object> params) {
        authorize();
        if (params == null || !params.containsKey("deviceRuleSetId")) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "deviceRuleSetId is required"))
                .build();
        }
        // ensure deviceId key is present (possibly null) so the SQL named parameter resolves
        params.putIfAbsent("deviceId", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = producer.requestBody("direct:run-device-config-export", params, Map.class);
        return Response.ok(result).build();
    }

    private String resolveRoute(GatewayRequestService.RequestContext context) {
        String requestType = context.requestTypeEnumId();
        if ("DrtWrite".equals(requestType) || "DrtConfigWrite".equals(requestType)) {
            return isOpcua(context) ? "direct:opcua-write-device-request" : "direct:mqtt-write-device-request";
        }
        if (isSubscribeRequest(requestType)) {
            return resolveSubscribeRoute(context);
        }
        throw new IllegalArgumentException("Unsupported standalone gateway request type " + requestType
            + " for request " + context.requestName() + ".");
    }

    private String resolveSubscribeRoute(GatewayRequestService.RequestContext context) {
        return isOpcua(context) ? "direct:opcua-subscribe-device-request" : "direct:mqtt-subscribe-device-request";
    }

    private boolean isSubscribeRequest(String requestType) {
        return "DrtSubscribe".equals(requestType)
            || "DrtEvent".equals(requestType)
            || "DrtStateChange".equals(requestType)
            || "DrtCyclic".equals(requestType);
    }

    private boolean isOpcua(GatewayRequestService.RequestContext context) {
        String connectionName = context.connectionName();
        if (connectionName != null && !connectionName.isBlank()) {
            if ("DcdOpcUa".equals(context.driverEnumId())) return true;
            throw new IllegalArgumentException("Unsupported gateway direct driver [" + context.driverEnumId() + "] for request ["
                + context.requestName() + "]. Only OPC UA is supported in moqui-device-gateway; "
                + "fieldbus drivers belong to moqui-plc4j.");
        }
        String brokerUri = context.brokerUri();
        return brokerUri != null && (brokerUri.startsWith("opcua:")
            || brokerUri.startsWith("milo-client:")
            || brokerUri.startsWith("opc.tcp://"));
    }

    private void authorize() {
        if (!authEnabled) return;

        String providedToken = httpHeaders != null ? httpHeaders.getHeaderString(authHeader) : null;
        if (providedToken == null || providedToken.isBlank()) {
            String authorization = httpHeaders != null ? httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION) : null;
            if (authorization != null && authorization.startsWith("Bearer ")) {
                providedToken = authorization.substring("Bearer ".length()).trim();
            }
        }

        if (providedToken == null || providedToken.isBlank() || !providedToken.equals(expectedToken)) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized", "message", "Missing or invalid API credential."))
                .build());
        }
    }
}
