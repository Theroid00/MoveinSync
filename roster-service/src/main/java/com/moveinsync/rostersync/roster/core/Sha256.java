package com.moveinsync.rostersync.roster.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. MessageDigest is not thread-safe, so a fresh instance is used per call. */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] of(byte[] bytes) {
        return digest().digest(bytes);
    }

    public static byte[] of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
