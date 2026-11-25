package duoshim.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ClientAuthenticatorTest {

    private final ClientAuthenticator auth = new ClientAuthenticator("sspr", "secret:with:colons");

    private static String basic(String raw) {
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validBasicHeader() {
        assertTrue(auth.authenticate(basic("sspr:secret:with:colons"), null, null));
    }

    @Test
    void schemeIsCaseInsensitive() {
        String h = basic("sspr:secret:with:colons").replace("Basic", "bAsIc");
        assertTrue(auth.authenticate(h, null, null));
    }

    @Test
    void wrongSecretOrIdFails() {
        assertFalse(auth.authenticate(basic("sspr:wrong"), null, null));
        assertFalse(auth.authenticate(basic("other:secret:with:colons"), null, null));
        assertFalse(auth.authenticate(basic("sspr:"), null, null));
    }

    @Test
    void malformedHeadersFail() {
        assertFalse(auth.authenticate("Basic !!!notbase64", null, null));
        assertFalse(auth.authenticate(basic("nocolon"), null, null));
        assertFalse(auth.authenticate(basic(":secret:with:colons"), null, null));
        assertFalse(auth.authenticate("Bearer abc", null, null));
        assertFalse(auth.authenticate("Basic", null, null));
    }

    @Test
    void badHeaderNeverFallsBackToFormFields() {
        assertFalse(auth.authenticate("Bearer abc", "sspr", "secret:with:colons"));
        assertFalse(auth.authenticate(basic("sspr:wrong"), "sspr", "secret:with:colons"));
    }

    @Test
    void formFieldsWorkWithoutHeader() {
        assertTrue(auth.authenticate(null, "sspr", "secret:with:colons"));
        assertFalse(auth.authenticate(null, "sspr", "wrong"));
        assertFalse(auth.authenticate(null, "sspr", null));
    }

    @Test
    void noCredentialsFails() {
        assertFalse(auth.authenticate(null, null, null));
    }

    @Test
    void credentialsToStringHidesSecret() {
        assertFalse(ClientAuthenticator.extract(basic("sspr:topsecret"), null, null).orElseThrow()
                .toString().contains("topsecret"));
    }
}
