package com.moveinsync.rostersync.roster.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The RSA-2048 signing key (spec §12.1): loaded from a PKCS#8 PEM file, or generated and written there
 * on first start so restarts (and booking's cached JWKS) keep working.
 */
@Component
public class JwtKeyStore {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyStore.class);
    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;

    public JwtKeyStore(@Value("${app.jwt.key-path}") String keyPath) throws IOException, GeneralSecurityException {
        Path path = Path.of(keyPath);
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (Files.exists(path)) {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            String base64 = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
            privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            log.info("event=jwt_key_loaded");
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            write(path, privateKey.getEncoded());
            log.info("event=jwt_key_generated");
        }
        publicKey = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    }

    private static void write(Path path, byte[] pkcs8) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String pem = BEGIN + "\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs8)
                + "\n" + END + "\n";
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system: permissions are the platform default
        }
    }

    public RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }
}
