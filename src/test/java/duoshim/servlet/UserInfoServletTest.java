package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import duoshim.testsupport.TestShim;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class UserInfoServletTest {

    private final Flow flow = new Flow();

    private void assertInvalidToken(Resp r) throws Exception {
        assertEquals(401, r.status, r.body());
        assertEquals("invalid_token", Flow.JSON.readTree(r.body()).get("error").asText());
        assertTrue(r.header("WWW-Authenticate").startsWith("Bearer"));
    }

    @Test
    void postWithBearerReturnsVerifiedUsername() throws Exception {
        Resp r = flow.userinfoPost(new Req().bearer(flow.accessTokenFor("bob")).param("attributes", "username"));
        assertEquals(200, r.status);
        assertEquals("bob", Flow.JSON.readTree(r.body()).get("username").asText());
        assertTrue(flow.shim.audited("userinfo", "ok"));
    }

    @Test
    void postWithFormTokenWorks() throws Exception {
        Resp r = flow.userinfoPost(new Req().param("access_token", flow.accessTokenFor("bob")));
        assertEquals(200, r.status);
    }

    @Test
    void getWithBearerWorksForSspr48MethodTypeGet() throws Exception {
        Resp r = flow.userinfoGet(new Req().bearer(flow.accessTokenFor("bob")));
        assertEquals(200, r.status);
        assertEquals("bob", Flow.JSON.readTree(r.body()).get("username").asText());
    }

    @Test
    void getDoesNotAcceptTokenInQueryString() throws Exception {
        assertInvalidToken(flow.userinfoGet(new Req().param("access_token", flow.accessTokenFor("bob"))));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        assertInvalidToken(flow.userinfoPost(new Req()));
        assertInvalidToken(flow.userinfoPost(new Req().header("Authorization", "Basic abc")));
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        assertInvalidToken(flow.userinfoPost(new Req().bearer("forged")));
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String token = flow.accessTokenFor("bob");
        assertEquals(200, flow.userinfoPost(new Req().bearer(token)).status);
        assertInvalidToken(flow.userinfoPost(new Req().bearer(token)));
    }

    @Test
    void tokenExpiresAfterSixtySeconds() throws Exception {
        String token = flow.accessTokenFor("bob");
        flow.shim.clock.advance(Duration.ofSeconds(60));
        assertInvalidToken(flow.userinfoPost(new Req().bearer(token)));
    }

    @Test
    void attributeNameComesFromConfig() throws Exception {
        Properties p = TestShim.baseProperties();
        p.setProperty("userinfo.attribute", "cn");
        Flow f = new Flow(new TestShim(p));
        Resp r = f.userinfoPost(new Req().bearer(f.accessTokenFor("bob")));
        assertEquals("bob", Flow.JSON.readTree(r.body()).get("cn").asText());
        assertNull(Flow.JSON.readTree(r.body()).get("username"));
    }
}
