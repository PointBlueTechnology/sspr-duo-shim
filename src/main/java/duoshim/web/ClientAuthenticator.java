package duoshim.web;

import duoshim.store.Secrets;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Authenticates SSPR as the OAuth client. HTTP Basic is preferred; client_id and client_secret
 * form fields are accepted only when no Authorization header is present. SSPR's Basic header is
 * base64("id:secret") with no URL-encoding, so none is applied here.
 */
public final class ClientAuthenticator {

    public record Credentials(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "Credentials{clientId=" + clientId + ", clientSecret=<redacted>}";
        }
    }

    private final String clientId;
    private final String clientSecret;

    public ClientAuthenticator(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Pulls credentials from the request. An Authorization header that is present but not valid
     * Basic yields empty: it never falls back to the form fields.
     */
    public static Optional<Credentials> extract(String authorizationHeader, String formClientId, String formClientSecret) {
        if (authorizationHeader != null) {
            String h = authorizationHeader.trim();
            if (h.length() <= 6 || !h.substring(0, 6).toLowerCase(Locale.ROOT).equals("basic ")) {
                return Optional.empty();
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Credentials(decoded.substring(0, colon), decoded.substring(colon + 1)));
        }
        if (formClientId != null && formClientSecret != null) {
            return Optional.of(new Credentials(formClientId, formClientSecret));
        }
        return Optional.empty();
    }

    /** Both values are always compared, in constant time. */
    public boolean matches(Credentials c) {
        boolean idOk = Secrets.constantTimeEquals(c.clientId(), clientId);
        boolean secretOk = Secrets.constantTimeEquals(c.clientSecret(), clientSecret);
        return idOk & secretOk;
    }

    public boolean authenticate(String authorizationHeader, String formClientId, String formClientSecret) {
        return extract(authorizationHeader, formClientId, formClientSecret).map(this::matches).orElse(false);
    }

    /** The configured client id, compared in constant time. */
    public boolean isClientId(String presented) {
        return Secrets.constantTimeEquals(presented, clientId);
    }
}
