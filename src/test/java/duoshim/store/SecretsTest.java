package duoshim.store;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecretsTest {

    @Test
    void tokensAre256BitBase64UrlAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = Secrets.newToken();
            assertEquals(43, t.length(), "32 bytes base64url without padding");
            assertTrue(t.matches("[A-Za-z0-9_-]+"));
            assertTrue(seen.add(t));
        }
    }

    @Test
    void constantTimeEquals() {
        assertTrue(Secrets.constantTimeEquals("abc", "abc"));
        assertFalse(Secrets.constantTimeEquals("abc", "abd"));
        assertFalse(Secrets.constantTimeEquals("abc", "abcd"));
        assertFalse(Secrets.constantTimeEquals(null, "abc"));
        assertFalse(Secrets.constantTimeEquals("abc", null));
        assertFalse(Secrets.constantTimeEquals(null, null));
    }

    @Test
    void digestIsStableAndDoesNotContainInput() {
        String d = Secrets.digest("value");
        assertEquals(d, Secrets.digest("value"));
        assertNotEquals(d, Secrets.digest("value2"));
        assertFalse(d.contains("value"));
    }
}
