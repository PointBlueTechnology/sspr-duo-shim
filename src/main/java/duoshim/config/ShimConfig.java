package duoshim.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Shim configuration, read from a properties file outside the WAR. The path comes from the
 * system property {@code duoshim.config}, falling back to the environment variable
 * {@code DUOSHIM_CONFIG}.
 */
public final class ShimConfig {

    public static final String SYSTEM_PROPERTY = "duoshim.config";
    public static final String ENV_VARIABLE = "DUOSHIM_CONFIG";

    /** Path of the Duo callback servlet inside the context. duo.redirectUri must end with it. */
    public static final String DUO_CALLBACK_PATH = "/duo/callback";

    static final String[] REQUIRED = {
        "duo.clientId", "duo.clientSecret", "duo.apiHost", "duo.redirectUri",
        "sspr.clientId", "sspr.clientSecret", "sspr.redirectUri",
        "ratelimit.windowSeconds", "ratelimit.perUsername", "ratelimit.sign.perIp", "ratelimit.grant.perIp",
    };

    static final int MIN_SSPR_SECRET_LENGTH = 16;

    private final String duoClientId;
    private final String duoClientSecret;
    private final String duoApiHost;
    private final String duoRedirectUri;
    private final String ssprClientId;
    private final String ssprClientSecret;
    private final String ssprRedirectUri;
    private final String ssprReturnUrl;
    private final String userinfoAttribute;
    private final Duration rateLimitWindow;
    private final int rateLimitPerUsername;
    private final int rateLimitSignPerIp;
    private final int rateLimitGrantPerIp;
    private final Duration transactionTtl;
    private final boolean cookieSecure;
    private final int storeMaxEntries;

    private ShimConfig(Properties p, List<String> problems) {
        duoClientId = trimmed(p, "duo.clientId");
        duoClientSecret = trimmed(p, "duo.clientSecret");
        duoApiHost = trimmed(p, "duo.apiHost");
        duoRedirectUri = trimmed(p, "duo.redirectUri");
        ssprClientId = trimmed(p, "sspr.clientId");
        ssprClientSecret = trimmed(p, "sspr.clientSecret");
        ssprRedirectUri = trimmed(p, "sspr.redirectUri");
        userinfoAttribute = orDefault(trimmed(p, "userinfo.attribute"), "username");
        rateLimitWindow = Duration.ofSeconds(integer(p, "ratelimit.windowSeconds", 1, problems));
        rateLimitPerUsername = integer(p, "ratelimit.perUsername", 0, problems);
        rateLimitSignPerIp = integer(p, "ratelimit.sign.perIp", 0, problems);
        rateLimitGrantPerIp = integer(p, "ratelimit.grant.perIp", 0, problems);
        transactionTtl = Duration.ofSeconds(integerOrDefault(p, "transaction.ttlSeconds", 300, 30, problems));
        storeMaxEntries = integerOrDefault(p, "store.maxEntries", 10_000, 100, problems);
        cookieSecure = booleanOrDefault(p, "cookie.secure", true, problems);

        String returnUrl = trimmed(p, "sspr.returnUrl");
        if (returnUrl == null && ssprRedirectUri != null && ssprRedirectUri.endsWith("/public/oauth")) {
            returnUrl = ssprRedirectUri.substring(0, ssprRedirectUri.length() - "/oauth".length()) + "/forgottenpassword";
        }
        ssprReturnUrl = returnUrl;
    }

    /** Loads the file named by -Dduoshim.config or DUOSHIM_CONFIG. */
    public static ShimConfig load() throws ConfigException {
        String location = System.getProperty(SYSTEM_PROPERTY);
        String source = "system property " + SYSTEM_PROPERTY;
        if (location == null || location.isBlank()) {
            location = System.getenv(ENV_VARIABLE);
            source = "environment variable " + ENV_VARIABLE;
        }
        if (location == null || location.isBlank()) {
            throw new ConfigException("no configuration file: set -D" + SYSTEM_PROPERTY
                    + "=/path/to/duoshim.properties (or the " + ENV_VARIABLE + " environment variable)");
        }
        return load(Path.of(location.trim()), source);
    }

    static ShimConfig load(Path path, String source) throws ConfigException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ConfigException("configuration file " + path + " (from " + source + ") does not exist or is not readable");
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("cannot read configuration file " + path + ": " + e.getMessage(), e);
        }
        return fromProperties(props);
    }

    /** Builds and validates the configuration; reports every problem at once. */
    public static ShimConfig fromProperties(Properties props) throws ConfigException {
        List<String> problems = new ArrayList<>();
        for (String key : REQUIRED) {
            if (trimmed(props, key) == null) {
                problems.add("missing required key '" + key + "'");
            }
        }
        ShimConfig c = new ShimConfig(props, problems);
        c.validate(problems);
        if (!problems.isEmpty()) {
            throw new ConfigException("invalid duoshim configuration: " + String.join("; ", problems));
        }
        return c;
    }

    private void validate(List<String> problems) {
        if (duoRedirectUri != null) {
            URI uri = httpsUri("duo.redirectUri", duoRedirectUri, problems);
            if (uri != null && (uri.getRawQuery() != null || !uri.getPath().endsWith(DUO_CALLBACK_PATH))) {
                problems.add("duo.redirectUri must be the shim's callback, https://<host>[:port]<context>" + DUO_CALLBACK_PATH + ", without a query");
            }
        }
        if (duoApiHost != null && (duoApiHost.contains("/") || duoApiHost.contains(":"))) {
            problems.add("duo.apiHost must be a host name only, for example api-xxxxxxxx.duosecurity.com");
        }
        if (ssprRedirectUri != null) {
            httpsUri("sspr.redirectUri", ssprRedirectUri, problems);
        }
        if (ssprReturnUrl != null) {
            httpsUri("sspr.returnUrl", ssprReturnUrl, problems);
        } else if (ssprRedirectUri != null) {
            problems.add("sspr.returnUrl is required when sspr.redirectUri does not end with /public/oauth");
        }
        if (ssprClientSecret != null && ssprClientSecret.length() < MIN_SSPR_SECRET_LENGTH) {
            problems.add("sspr.clientSecret must be at least " + MIN_SSPR_SECRET_LENGTH + " characters (use a long random value)");
        }
        if (userinfoAttribute.contains(",") || userinfoAttribute.chars().anyMatch(Character::isWhitespace)) {
            problems.add("userinfo.attribute must be a single attribute name");
        }
    }

    private static URI httpsUri(String key, String value, List<String> problems) {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getRawFragment() != null) {
                problems.add(key + " must be an absolute https URL without a fragment");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            problems.add(key + " is not a valid URL: " + e.getMessage());
            return null;
        }
    }

    private static String trimmed(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static int integer(Properties p, String key, int min, List<String> problems) {
        String v = trimmed(p, key);
        if (v == null) {
            return min; // reported as missing by the REQUIRED check
        }
        return parse(key, v, min, problems);
    }

    private static int integerOrDefault(Properties p, String key, int fallback, int min, List<String> problems) {
        String v = trimmed(p, key);
        return v == null ? fallback : parse(key, v, min, problems);
    }

    private static int parse(String key, String v, int min, List<String> problems) {
        try {
            int n = Integer.parseInt(v);
            if (n < min) {
                problems.add(key + " must be at least " + min);
                return min;
            }
            return n;
        } catch (NumberFormatException e) {
            problems.add(key + " must be a whole number");
            return min;
        }
    }

    private static boolean booleanOrDefault(Properties p, String key, boolean fallback, List<String> problems) {
        String v = trimmed(p, key);
        if (v == null) {
            return fallback;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        problems.add(key + " must be true or false");
        return fallback;
    }

    public String duoClientId() { return duoClientId; }
    public String duoClientSecret() { return duoClientSecret; }
    public String duoApiHost() { return duoApiHost; }
    public String duoRedirectUri() { return duoRedirectUri; }
    public String ssprClientId() { return ssprClientId; }
    public String ssprClientSecret() { return ssprClientSecret; }
    public String ssprRedirectUri() { return ssprRedirectUri; }
    public String ssprReturnUrl() { return ssprReturnUrl; }
    public String userinfoAttribute() { return userinfoAttribute; }
    public Duration rateLimitWindow() { return rateLimitWindow; }
    public int rateLimitPerUsername() { return rateLimitPerUsername; }
    public int rateLimitSignPerIp() { return rateLimitSignPerIp; }
    public int rateLimitGrantPerIp() { return rateLimitGrantPerIp; }
    public Duration transactionTtl() { return transactionTtl; }
    public boolean cookieSecure() { return cookieSecure; }
    public int storeMaxEntries() { return storeMaxEntries; }

    /** Safe summary for the startup log: no secrets. */
    @Override
    public String toString() {
        return "ShimConfig{duo.apiHost=" + duoApiHost + ", duo.clientId=" + duoClientId
                + ", duo.redirectUri=" + duoRedirectUri + ", sspr.clientId=" + ssprClientId
                + ", sspr.redirectUri=" + ssprRedirectUri + ", sspr.returnUrl=" + ssprReturnUrl
                + ", userinfo.attribute=" + userinfoAttribute + ", ratelimit.windowSeconds=" + rateLimitWindow.toSeconds()
                + ", ratelimit.perUsername=" + rateLimitPerUsername + ", ratelimit.sign.perIp=" + rateLimitSignPerIp
                + ", ratelimit.grant.perIp=" + rateLimitGrantPerIp + ", transaction.ttlSeconds=" + transactionTtl.toSeconds()
                + ", cookie.secure=" + cookieSecure + ", store.maxEntries=" + storeMaxEntries + "}";
    }
}
