package duoshim.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Random handles/codes/tokens and constant-time comparison. */
public final class Secrets {

    /** 32 bytes = 256 bits of entropy per handle, code and token. */
    public static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private Secrets() {
    }

    /** A new 256-bit random value, base64url without padding (43 characters). */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }

    /**
     * SHA-256 of the value, base64url. Stores are keyed by this digest, so a map lookup compares
     * digests rather than the secret itself.
     */
    public static String digest(String value) {
        return B64URL.encodeToString(sha256(value));
    }

    /**
     * Constant-time comparison. Both sides are hashed first so neither the content nor the length
     * of the expected value leaks through timing. Null on either side never matches.
     */
    public static boolean constantTimeEquals(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
