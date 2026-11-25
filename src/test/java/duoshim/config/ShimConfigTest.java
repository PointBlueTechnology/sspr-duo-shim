package duoshim.config;

import duoshim.testsupport.TestShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ShimConfigTest {

    private static Properties base() {
        return TestShim.baseProperties();
    }

    private static String problems(Properties p) {
        return assertThrows(ConfigException.class, () -> ShimConfig.fromProperties(p)).getMessage();
    }

    @Test
    void validConfigLoadsWithDefaults() throws Exception {
        ShimConfig c = ShimConfig.fromProperties(base());
        assertEquals("username", c.userinfoAttribute());
        assertEquals(TestShim.SSPR_RETURN, c.ssprReturnUrl());
        assertTrue(c.cookieSecure());
        assertEquals(300, c.transactionTtl().toSeconds());
        assertEquals(20, c.rateLimitSignFailuresPerIp());
    }

    @Test
    void everyMissingKeyIsReportedAtOnce() {
        String msg = problems(new Properties());
        for (String key : ShimConfig.REQUIRED) {
            assertTrue(msg.contains("'" + key + "'"), () -> "message should name " + key + ": " + msg);
        }
    }

    @Test
    void blankValueCountsAsMissing() {
        Properties p = base();
        p.setProperty("sspr.clientSecret", "   ");
        assertTrue(problems(p).contains("missing required key 'sspr.clientSecret'"));
    }

    @Test
    void duoRedirectMustBeHttpsCallback() {
        Properties p = base();
        p.setProperty("duo.redirectUri", "http://sspr.example.test/duoshim/duo/callback");
        assertTrue(problems(p).contains("duo.redirectUri must be an absolute https URL"));
        p.setProperty("duo.redirectUri", "https://sspr.example.test/duoshim/other");
        assertTrue(problems(p).contains("duo.redirectUri must be the shim's callback"));
    }

    @Test
    void apiHostMustBeBareHost() {
        Properties p = base();
        p.setProperty("duo.apiHost", "https://api-test.duosecurity.com");
        assertTrue(problems(p).contains("duo.apiHost must be a host name only"));
    }

    @Test
    void shortSsprSecretRejected() {
        Properties p = base();
        p.setProperty("sspr.clientSecret", "short");
        assertTrue(problems(p).contains("sspr.clientSecret must be at least"));
    }

    @Test
    void returnUrlRequiredWhenNotDerivable() {
        Properties p = base();
        p.setProperty("sspr.redirectUri", "https://sspr.example.test/custom/callback");
        assertTrue(problems(p).contains("sspr.returnUrl is required"));
        p.setProperty("sspr.returnUrl", "https://sspr.example.test/sspr/");
        assertDoesNotThrow(() -> ShimConfig.fromProperties(p));
    }

    @Test
    void badNumbersAndBooleansRejected() {
        Properties p = base();
        p.setProperty("ratelimit.perUsername", "five");
        p.setProperty("ratelimit.windowSeconds", "0");
        p.setProperty("cookie.secure", "yes");
        String msg = problems(p);
        assertTrue(msg.contains("ratelimit.perUsername must be a whole number"));
        assertTrue(msg.contains("ratelimit.windowSeconds must be at least 1"));
        assertTrue(msg.contains("cookie.secure must be true or false"));
    }

    @Test
    void userinfoAttributeMustBeSingleName() {
        Properties p = base();
        p.setProperty("userinfo.attribute", "username,email");
        assertTrue(problems(p).contains("userinfo.attribute must be a single attribute name"));
    }

    @Test
    void shippedExampleFileIsRejectedUntilEveryPlaceholderIsReplaced() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ShimConfig.load(Path.of("duoshim.properties.example"), "test"));
        for (String key : new String[]{"duo.clientId", "duo.clientSecret", "duo.apiHost", "duo.redirectUri",
                "sspr.clientSecret", "sspr.redirectUri"}) {
            assertTrue(e.getMessage().contains(key + " still contains the CHANGE_ME placeholder"),
                    () -> key + " should be reported: " + e.getMessage());
        }
    }

    @Test
    void anyLeftoverPlaceholderIsRejected() {
        Properties p = base();
        p.setProperty("sspr.clientSecret", "change_me-but-long-enough-to-pass");
        assertTrue(problems(p).contains("sspr.clientSecret still contains the CHANGE_ME placeholder"));
    }

    @Test
    void toStringHasNoSecrets() throws Exception {
        String s = ShimConfig.fromProperties(base()).toString();
        assertFalse(s.contains(TestShim.SSPR_SECRET));
        assertFalse(s.contains("abcdefghijklmnopqrstuvwxyz01234567890ABC"));
    }

    @Test
    void missingFileGivesClearMessage(@TempDir Path dir) {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ShimConfig.load(dir.resolve("absent.properties"), "test"));
        assertTrue(e.getMessage().contains("does not exist or is not readable"));
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("duoshim.properties");
        try (var out = Files.newOutputStream(f)) {
            base().store(out, null);
        }
        assertEquals(TestShim.SSPR_CLIENT_ID, ShimConfig.load(f, "test").ssprClientId());
    }
}
