package com.moveinsync.rostersync.roster.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.roster.outbox.ServiceTokenSupplier;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Mints RS256 JWTs (spec §12.1): iss roster-sync, aud, sub, tenant, roles, optional employeeId.
 * The key id is the RSA key's SHA-256 thumbprint, published at /.well-known/jwks.json.
 * Also supplies the relay's cached SERVICE token (tenant "*", aud booking, 10 min, refreshed at 80%).
 */
@Component
public class TokenService implements ServiceTokenSupplier {

    public static final String ISSUER = "roster-sync";
    public static final String AUD_ROSTER = "roster-sync";
    public static final String AUD_BOOKING = "booking";
    public static final Duration USER_TTL = Duration.ofMinutes(15);
    public static final Duration SERVICE_TTL = Duration.ofMinutes(10);

    public record TokenSpec(String subject, String tenant, List<String> roles, String employeeId, List<String> audience,
                            Instant issuedAt, Instant expiresAt) {
    }

    private final RSAKey jwk;
    private final NimbusJwtEncoder encoder;
    private final Clock clock;
    private String serviceToken;
    private Instant serviceRefreshAt = Instant.MIN;

    public TokenService(JwtKeyStore keys) throws JOSEException {
        RSAKey key = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).build();
        this.jwk = new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.clock = Clock.systemUTC();
    }

    public String issue(TokenSpec spec) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(spec.subject())
                .audience(spec.audience())
                .issuedAt(spec.issuedAt())
                .expiresAt(spec.expiresAt())
                .id(UUID.randomUUID().toString())
                .claim("tenant", spec.tenant())
                .claim("roles", spec.roles());
        if (spec.employeeId() != null) {
            claims.claim("employeeId", spec.employeeId());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwk.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /** A 15-minute user token valid for both services. */
    public String issueForUser(String subject, String tenant, List<String> roles, String employeeId) {
        Instant now = clock.instant();
        return issue(new TokenSpec(subject, tenant, roles, employeeId, List.of(AUD_ROSTER, AUD_BOOKING), now, now.plus(USER_TTL)));
    }

    @Override
    public synchronized String serviceToken() {
        Instant now = clock.instant();
        if (serviceToken == null || !now.isBefore(serviceRefreshAt)) {
            serviceToken = issue(new TokenSpec("roster-relay", "*", List.of("SERVICE"), null, List.of(AUD_BOOKING),
                    now, now.plus(SERVICE_TTL)));
            serviceRefreshAt = now.plus(SERVICE_TTL.multipliedBy(8).dividedBy(10));
        }
        return serviceToken;
    }

    /** Public JWK set (no private parts). */
    public Map<String, Object> jwks() {
        return new JWKSet(jwk.toPublicJWK()).toJSONObject();
    }
}
