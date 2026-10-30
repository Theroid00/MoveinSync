package com.moveinsync.rostersync.roster.api;

import java.util.Map;

import com.moveinsync.rostersync.roster.security.TokenService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification keys for booking-service and other resource servers. */
@RestController
public class JwksController {

    private final TokenService tokens;

    public JwksController(TokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping("/.well-known/jwks.json")
    @SecurityRequirements
    public Map<String, Object> jwks() {
        return tokens.jwks();
    }
}
