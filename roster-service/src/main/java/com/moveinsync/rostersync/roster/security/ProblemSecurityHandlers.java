package com.moveinsync.rostersync.roster.security;

import java.io.IOException;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.api.Problems;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401/403 raised inside the security filter chain, rendered with the same ProblemDetail contract. */
@Component
public class ProblemSecurityHandlers {

    private final JsonMapper json;

    public ProblemSecurityHandlers(JsonMapper json) {
        this.json = json;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, e) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, ErrorCode.UNAUTHENTICATED);
        };
    }

    public AccessDeniedHandler accessDenied() {
        return (request, response, e) -> write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(Problems.of(code)));
    }
}
