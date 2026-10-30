package com.moveinsync.rostersync.roster.api;


import com.moveinsync.rostersync.roster.security.AuthProperties;
import com.moveinsync.rostersync.roster.security.TokenService;
import com.moveinsync.rostersync.roster.security.UserDirectory;
import com.moveinsync.rostersync.roster.service.UnauthenticatedException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only token issuer (DEC-16): exchanges a demo username/password for a 15-minute JWT. Disabled
 * unless app.auth.dev-issuer.enabled=true; production would use the company IdP instead.
 */
@RestController
@ConditionalOnProperty(name = "app.auth.dev-issuer.enabled", havingValue = "true")
public class AuthController {

    private final UserDirectory users;
    private final TokenService tokens;

    public AuthController(UserDirectory users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public record TokenRequest(String username, String password) {
    }

    public record TokenResponse(String accessToken, long expiresIn) {
    }

    @PostMapping("/api/v1/auth/token")
    @SecurityRequirements      // public: no token needed to obtain one
    public TokenResponse token(@RequestBody TokenRequest request) {
        AuthProperties.User user = users.authenticate(request.username(), request.password())
                .orElseThrow(UnauthenticatedException::new);
        String token = tokens.issueForUser(user.username(), user.tenant(), user.roles(), user.employeeId());
        return new TokenResponse(token, TokenService.USER_TTL.toSeconds());
    }
}
