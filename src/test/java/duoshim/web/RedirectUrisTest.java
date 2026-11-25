package duoshim.web;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedirectUrisTest {

    private static final String CONFIGURED = "https://sspr.example.test/sspr/public/oauth";

    @Test
    void onlyExactMatchIsAccepted() {
        assertTrue(RedirectUris.exactMatch(CONFIGURED, CONFIGURED));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, CONFIGURED + "/"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, CONFIGURED + "?x=1"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, CONFIGURED + "#frag"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, "https://SSPR.example.test/sspr/public/oauth"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, "http://sspr.example.test/sspr/public/oauth"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, "https://sspr.example.test.evil.test/sspr/public/oauth"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, "https://sspr.example.test/sspr/public/oauth/../../evil"));
        assertFalse(RedirectUris.exactMatch(CONFIGURED, null));
        assertFalse(RedirectUris.exactMatch(null, CONFIGURED));
    }

    @Test
    void paramsAreEncodedAndAppended() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("code", "a b&c");
        p.put("state", "x=y");
        assertEquals(CONFIGURED + "?code=a+b%26c&state=x%3Dy", RedirectUris.withParams(CONFIGURED, p));
        assertEquals(CONFIGURED + "?q=1&code=a+b%26c&state=x%3Dy", RedirectUris.withParams(CONFIGURED + "?q=1", p));
    }
}
