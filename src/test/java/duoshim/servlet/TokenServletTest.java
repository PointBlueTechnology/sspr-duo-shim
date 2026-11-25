package duoshim.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static duoshim.testsupport.TestShim.SSPR_CLIENT_ID;
import static duoshim.testsupport.TestShim.SSPR_REDIRECT;
import static duoshim.testsupport.TestShim.SSPR_SECRET;
import static org.junit.jupiter.api.Assertions.*;

class TokenServletTest {

    private final Flow flow = new Flow();

    private void assertOAuthError(Resp r, int status, String error) throws Exception {
        assertEquals(status, r.status, r.body());
        assertEquals(error, Flow.JSON.readTree(r.body()).get("error").asText());
        assertEquals(0, flow.shim.ctx.tokens().size(), "no access token issued");
    }

    @Test
    void issuesBearerTokenWithoutIdToken() throws Exception {
        Resp r = flow.token(Flow.tokenRequest(flow.codeFor("bob")));
        assertEquals(200, r.status);
        assertEquals("application/json;charset=UTF-8", r.contentType);
        JsonNode body = Flow.JSON.readTree(r.body());
        assertEquals(43, body.get("access_token").asText().length());
        assertEquals("Bearer", body.get("token_type").asText());
        assertEquals(60, body.get("expires_in").asInt());
        assertNull(body.get("id_token"), "SSPR 4.8 must fall through to /oauth/userinfo");
        assertNull(body.get("refresh_token"));
    }

    @Test
    void basicOnlyAndFormOnlyClientAuthBothWork() throws Exception {
        Req basicOnly = new Req().basic(SSPR_CLIENT_ID, SSPR_SECRET).param("code", flow.codeFor("bob"))
                .param("grant_type", "authorization_code").param("redirect_uri", SSPR_REDIRECT);
        assertEquals(200, flow.token(basicOnly).status);
        Req formOnly = new Req().param("client_id", SSPR_CLIENT_ID).param("client_secret", SSPR_SECRET)
                .param("code", flow.codeFor("alice")).param("grant_type", "authorization_code")
                .param("redirect_uri", SSPR_REDIRECT);
        assertEquals(200, flow.token(formOnly).status);
    }

    @Test
    void badClientIsRejectedWithoutBurningTheCode() throws Exception {
        String code = flow.codeFor("bob");
        Resp r = flow.token(Flow.tokenRequest(code).basic(SSPR_CLIENT_ID, "wrong-secret-0000"));
        assertOAuthError(r, 401, "invalid_client");
        assertNotNull(r.header("WWW-Authenticate"));
        assertEquals(200, flow.token(Flow.tokenRequest(code)).status);
    }

    @Test
    void formClientIdMustMatchAuthenticatedClient() throws Exception {
        Resp r = flow.token(Flow.tokenRequest(flow.codeFor("bob")).param("client_id", "other"));
        assertOAuthError(r, 401, "invalid_client");
    }

    @Test
    void grantTypeMustBeAuthorizationCode() throws Exception {
        Resp r = flow.token(Flow.tokenRequest(flow.codeFor("bob")).param("grant_type", "refresh_token"));
        assertOAuthError(r, 400, "unsupported_grant_type");
    }

    @Test
    void unknownCodeIsRejected() throws Exception {
        assertOAuthError(flow.token(Flow.tokenRequest("forged-code")), 400, "invalid_grant");
        assertOAuthError(flow.token(Flow.tokenRequest(null)), 400, "invalid_grant");
    }

    @Test
    void codeIsSingleUse() throws Exception {
        String code = flow.codeFor("bob");
        assertEquals(200, flow.token(Flow.tokenRequest(code)).status);
        Resp again = flow.token(Flow.tokenRequest(code));
        assertEquals(400, again.status);
        assertEquals("invalid_grant", Flow.JSON.readTree(again.body()).get("error").asText());
    }

    @Test
    void codeExpiresAfterSixtySeconds() throws Exception {
        String code = flow.codeFor("bob");
        flow.shim.clock.advance(Duration.ofSeconds(60));
        assertOAuthError(flow.token(Flow.tokenRequest(code)), 400, "invalid_grant");
    }

    @Test
    void redirectUriMismatchIsRejectedAndBurnsTheCode() throws Exception {
        String code = flow.codeFor("bob");
        assertOAuthError(flow.token(Flow.tokenRequest(code).param("redirect_uri", SSPR_REDIRECT + "/x")), 400, "invalid_grant");
        assertOAuthError(flow.token(Flow.tokenRequest(code)), 400, "invalid_grant");
    }
}
