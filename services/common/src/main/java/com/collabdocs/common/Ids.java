package com.collabdocs.common;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID uuidV7() {
        long time = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
        long msb = (time << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = RANDOM.nextLong();
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String raw) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
