package com.moveinsync.rostersync.roster;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.moveinsync.rostersync.roster.security.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test client for the roster API. Every call states the role it acts as; authentication details live
 * here only, so tests do not change when the auth mechanism does.
 */
public final class RosterApi {

    public enum Role { HR_INTEGRATION, ADMIN, OPERATOR, EMPLOYEE, SERVICE }

    public record Response(int status, String raw, JsonNode body, MockHttpServletResponse servlet) {

        public String text(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node == null || node.isNull() ? null : node.asString();
        }

        public String header(String name) {
            return servlet.getHeader(name);
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;

    public RosterApi(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public Response postEvent(String tenant, Object event) {
        return perform(post("/api/v1/hr/events").contentType(MediaType.APPLICATION_JSON).content(toJson(event)),
                tenant, Role.HR_INTEGRATION);
    }

    public Response postBatch(String tenant, String ndjson) {
        return perform(post("/api/v1/hr/events/batch").contentType("application/x-ndjson").content(ndjson),
                tenant, Role.HR_INTEGRATION);
    }

    public Response getEmployee(String tenant, String id) {
        return perform(get("/api/v1/employees/{id}", id), tenant, Role.ADMIN);
    }

    public Response history(String tenant, String id) {
        return perform(get("/api/v1/employees/{id}/history", id), tenant, Role.ADMIN);
    }

    public Response patchTransport(String tenant, String id, String ifMatch, Object body) {
        MockHttpServletRequestBuilder req = patch("/api/v1/employees/{id}/transport", id)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (ifMatch != null) {
            req.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return perform(req, tenant, Role.ADMIN);
    }

    public Response deactivate(String tenant, String id) {
        return perform(delete("/api/v1/employees/{id}", id), tenant, Role.ADMIN);
    }

    public Response outboxStatus(String tenant) {
        return perform(get("/api/v1/outbox/status"), tenant, Role.OPERATOR);
    }

    public Response replayOutbox(String tenant, long outboxId) {
        return perform(post("/api/v1/outbox/{id}/replay", outboxId), tenant, Role.OPERATOR);
    }

    public Response perform(MockHttpServletRequestBuilder request, String tenant, Role role) {
        if (tenant != null) {
            request.header(TenantContext.TENANT_HEADER, tenant).header(TenantContext.PRINCIPAL_HEADER, role.name().toLowerCase());
        }
        try {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            String raw = response.getContentAsString();
            JsonNode body = raw.isBlank() ? null : json.readTree(raw);
            return new Response(response.getStatus(), raw, body, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object body) {
        return body instanceof String s ? s : json.writeValueAsString(body);
    }
}
