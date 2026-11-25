package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import duoshim.testsupport.TestShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

import static duoshim.testsupport.TestShim.SSPR_CLIENT_ID;
import static duoshim.testsupport.TestShim.SSPR_SECRET;
import static org.junit.jupiter.api.Assertions.*;

class SignServletTest {

    private final Flow flow = new Flow();

    private static Req signed(String data) {
        return new Req().basic(SSPR_CLIENT_ID, SSPR_SECRET).param("data", data);
    }

    @Test
    void returnsSingleUseHandle() throws Exception {
        String handle = flow.signHandle("bob");
        assertEquals(43, handle.length());
        assertEquals("bob", flow.shim.ctx.handles().consume(handle).orElseThrow().username());
        assertTrue(flow.shim.audited("sign", "ok"));
        assertFalse(String.join("\n", flow.shim.audit).contains(handle), "handles are never logged");
    }

    @Test
    void formClientCredentialsAreAccepted() throws Exception {
        Resp r = flow.sign(new Req().param("client_id", SSPR_CLIENT_ID).param("client_secret", SSPR_SECRET)
                .param("data", Flow.data("bob")));
        assertEquals(200, r.status);
    }

    @Test
    void missingClientAuthIsRejected() throws Exception {
        Resp r = flow.sign(new Req().param("data", Flow.data("bob")));
        assertEquals(401, r.status);
        assertNotNull(r.header("WWW-Authenticate"));
        assertTrue(r.body().contains("invalid_client"));
        assertEquals(0, flow.shim.ctx.handles().size());
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        Resp r = flow.sign(new Req().basic(SSPR_CLIENT_ID, "wrong-secret-value").param("data", Flow.data("bob")));
        assertEquals(401, r.status);
        assertTrue(flow.shim.audited("sign", "invalid_client"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "not json", "{\"username\":\"bob\"}", "[]", "[{\"username\":\"a\"},{\"username\":\"b\"}]",
        "[{\"user\":\"bob\"}]", "[{\"username\":42}]", "[{\"username\":\"   \"}]", "[{\"username\":\"bob\\u0000x\"}]",
        "[\"bob\"]",
    })
    void malformedDataIsRejected(String data) throws Exception {
        Resp r = flow.sign(signed(data));
        assertEquals(400, r.status, data);
        assertTrue(r.body().contains("invalid_request"));
    }

    @Test
    void missingDataIsRejected() throws Exception {
        Resp r = flow.sign(new Req().basic(SSPR_CLIENT_ID, SSPR_SECRET));
        assertEquals(400, r.status);
    }

    @Test
    void overlongUsernameIsRejected() throws Exception {
        Resp r = flow.sign(signed(Flow.data("x".repeat(ShimServlet.MAX_USERNAME_LENGTH + 1))));
        assertEquals(400, r.status);
    }

    @Test
    void perUsernameRateLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, flow.sign(signed(Flow.data("bob"))).status);
        }
        Resp r = flow.sign(signed(Flow.data("BOB")));
        assertEquals(429, r.status, "limit is per username, case-insensitive");
        assertTrue(flow.shim.audited("sign", "rate_limited_user"));
        assertEquals(200, flow.sign(signed(Flow.data("alice"))).status);
    }

    /** H1: anonymous failures from a shared address (proxy, tunnel) must not block SSPR's own calls. */
    @Test
    void anonymousFailuresCannotUseUpSsprAllowance() throws Exception {
        Properties p = TestShim.baseProperties();
        p.setProperty("ratelimit.sign.perIp", "3");
        p.setProperty("ratelimit.sign.failuresPerIp", "2");
        Flow f = new Flow(new TestShim(p));
        String shared = "127.0.0.1";
        assertEquals(401, f.sign(new Req().ip(shared).param("data", Flow.data("x"))).status);
        assertEquals(401, f.sign(new Req().ip(shared).basic(SSPR_CLIENT_ID, "wrong-secret-xxxxxx").param("data", Flow.data("x"))).status);
        for (int i = 0; i < 50; i++) {
            assertEquals(429, f.sign(new Req().ip(shared).param("data", Flow.data("x"))).status);
        }
        assertTrue(f.shim.audited("sign", "rate_limited_failures_ip"));
        // SSPR, behind the same address, still gets its full allowance.
        for (int i = 0; i < 3; i++) {
            assertEquals(200, f.sign(signed(Flow.data("user" + i)).ip(shared)).status);
        }
        assertEquals(429, f.sign(signed(Flow.data("user9")).ip(shared)).status, "SSPR's own per-IP limit still applies");
    }

    @Test
    void perIpRateLimit() throws Exception {
        Properties p = TestShim.baseProperties();
        p.setProperty("ratelimit.sign.perIp", "2");
        Flow f = new Flow(new TestShim(p));
        assertEquals(200, f.sign(signed(Flow.data("a"))).status);
        assertEquals(200, f.sign(signed(Flow.data("b"))).status);
        assertEquals(429, f.sign(signed(Flow.data("c"))).status);
        assertEquals(200, f.sign(signed(Flow.data("c")).ip("198.51.100.7")).status);
    }
}
