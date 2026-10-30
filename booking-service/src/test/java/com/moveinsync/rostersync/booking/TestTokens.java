package com.moveinsync.rostersync.booking;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import com.moveinsync.rostersync.booking.security.SecurityConfig;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Test keypair standing in for roster's signing key: tokens minted here are accepted by the test
 * JwtDecoder, which applies the production validators (issuer, audience booking, timestamps).
 */
public final class TestTokens {

    static final KeyPair KEYS = generate();
    static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
            new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build())));

    private TestTokens() {
    }

    public static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(SecurityConfig.validator());
        return decoder;
    }

    public static String mint(String tenant, List<String> roles, String employeeId, List<String> audience,
                              Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER)
                .subject(String.join(",", roles).toLowerCase() + "@" + tenant).audience(audience)
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("tenant", tenant).claim("roles", roles);
        if (employeeId != null) {
            claims.claim("employeeId", employeeId);
        }
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(),
                claims.build())).getTokenValue();
    }

    /** A valid token for the given role; SERVICE tokens carry tenant "*" and audience booking only. */
    public static String forRole(String tenant, String role, String employeeId) {
        Instant now = Instant.now();
        boolean service = "SERVICE".equals(role);
        return mint(service ? "*" : tenant, List.of(role), "EMPLOYEE".equals(role) ? employeeId : null,
                service ? List.of(SecurityConfig.AUDIENCE) : List.of("roster-sync", SecurityConfig.AUDIENCE),
                now, now.plusSeconds(900));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
