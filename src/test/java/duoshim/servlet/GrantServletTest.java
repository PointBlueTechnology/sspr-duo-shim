package duoshim.servlet;

import duoshim.model.SignedUser;
import duoshim.testsupport.FakeHttp.Resp;
import duoshim.testsupport.TestShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static duoshim.testsupport.TestShim.SSPR_REDIRECT;
import static duoshim.testsupport.TestShim.SSPR_RETURN;
import static org.junit.jupiter.api.Assertions.*;

class GrantServletTest {

    private final Flow flow = new Flow();

    private void assertNoDuoAndNoTransaction(Resp r) {
        assertNull(r.redirect, "must not redirect");
        assertTrue(r.headerValues("Set-Cookie").isEmpty(), "must not set the transaction cookie");
        assertEquals(0, flow.shim.ctx.transactions().size());
        assertFalse(r.body().contains("code="), "error page never carries a code");
        assertEquals("text/html;charset=UTF-8", r.contentType);
    }

    @Test
    void redirectsToDuoForTheSignedUser() throws Exception {
        Flow.Started s = flow.start("bob", "sspr-state");
        assertTrue(s.grantResponse().redirect.startsWith("https://api-test.duosecurity.com/"));
        assertEquals(List.of("bob"), flow.shim.duo.authUrlUsernames);
        assertNotNull(flow.shim.duo.nonceFor(s.duoState()), "a nonce is sent to Duo");
        String cookie = s.grantResponse().headerValues("Set-Cookie").get(0);
        assertTrue(cookie.contains("; Path=/duoshim;"), "scoped to the shim context only");
        assertTrue(cookie.contains("; HttpOnly"));
        assertTrue(cookie.contains("; SameSite=Lax"));
        assertTrue(cookie.contains("; Secure"));
        assertTrue(cookie.contains("; Max-Age=300"));
        assertTrue(flow.shim.audited("grant", "redirect_to_duo"));
        assertEquals(1, flow.shim.ctx.transactions().size());
    }

    @Test
    void wrongClientIdIsRejectedBeforeTheHandleIsUsed() throws Exception {
        String handle = flow.signHandle("bob");
        Resp r = flow.grant(Flow.grantRequest(handle, "s").param("client_id", "someone-else"));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
        assertTrue(r.body().contains(SSPR_RETURN), "links back to SSPR's configured return URL");
        assertEquals(0, flow.shim.duo.clientsCreated.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        SSPR_REDIRECT + "/", SSPR_REDIRECT + "?x=1", "https://evil.example/sspr/public/oauth",
        "https://SSPR.example.test/sspr/public/oauth", "",
    })
    void redirectUriMustMatchExactly(String redirect) throws Exception {
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s").param("redirect_uri", redirect));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
        assertFalse(r.body().contains("evil"), "never links to an unvalidated redirect_uri");
        assertTrue(flow.shim.audited("grant", "invalid_redirect_uri"));
    }

    @Test
    void missingRedirectUriIsRejected() throws Exception {
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s").param("redirect_uri", null));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
    }

    @Test
    void missingStateIsRejected() throws Exception {
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), null));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
    }

    @Test
    void responseTypeMustBeCode() throws Exception {
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s").param("response_type", "token"));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
        assertTrue(r.body().contains("sub_error=usrcan"), "offers SSPR's own cancel path");
    }

    @Test
    void missingOrUnknownHandleIsRejected() throws Exception {
        assertEquals(400, flow.grant(Flow.grantRequest(null, "s")).status);
        Resp r = flow.grant(Flow.grantRequest("forged-handle", "s"));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
        assertTrue(flow.shim.audited("grant", "invalid_or_expired_handle"));
    }

    @Test
    void handleCannotBeReused() throws Exception {
        String handle = flow.signHandle("bob");
        assertEquals(302, flow.grant(Flow.grantRequest(handle, "s")).status);
        Resp again = flow.grant(Flow.grantRequest(handle, "s"));
        assertEquals(400, again.status);
        assertNull(again.redirect);
    }

    @Test
    void handleExpiresAfterTwoMinutes() throws Exception {
        String handle = flow.signHandle("bob");
        flow.shim.clock.advance(Duration.ofMinutes(2));
        Resp r = flow.grant(Flow.grantRequest(handle, "s"));
        assertEquals(400, r.status);
        assertNoDuoAndNoTransaction(r);
    }

    @Test
    void failsClosedWhenDuoHealthCheckFails() throws Exception {
        flow.shim.duo.failHealthCheck = true;
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s"));
        assertEquals(503, r.status);
        assertNoDuoAndNoTransaction(r);
        assertTrue(flow.shim.audited("grant", "duo_unavailable"));
    }

    @Test
    void failsClosedWhenDuoClientCannotBeCreated() throws Exception {
        flow.shim.duo.failCreate = true;
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s"));
        assertEquals(503, r.status);
        assertNoDuoAndNoTransaction(r);
    }

    @Test
    void newDuoClientPerRequest() throws Exception {
        flow.start("bob", "s1");
        flow.start("alice", "s2");
        assertEquals(2, flow.shim.duo.clientsCreated.get());
    }

    @Test
    void perUsernameRateLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            String h = flow.shim.ctx.handles().put(new SignedUser("bob"));
            assertEquals(302, flow.grant(Flow.grantRequest(h, "s").ip("203.0.113." + i)).status);
        }
        String h = flow.shim.ctx.handles().put(new SignedUser("bob"));
        Resp r = flow.grant(Flow.grantRequest(h, "s").ip("203.0.113.99"));
        assertEquals(429, r.status);
        assertNull(r.redirect);
        assertTrue(flow.shim.audited("grant", "rate_limited_user"));
        assertTrue(flow.shim.ctx.handles().peek(h).isPresent(), "a refused request does not use up the handle (L6)");
    }

    @Test
    void perIpRateLimit() throws Exception {
        Properties p = TestShim.baseProperties();
        p.setProperty("ratelimit.grant.perIp", "1");
        Flow f = new Flow(new TestShim(p));
        assertEquals(302, f.grant(Flow.grantRequest(f.signHandle("a"), "s")).status);
        Resp r = f.grant(Flow.grantRequest(f.signHandle("b"), "s"));
        assertEquals(429, r.status);
        assertTrue(f.shim.audited("grant", "rate_limited_ip"));
    }
}
