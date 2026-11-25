package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The whole SSPR -> shim -> Duo -> shim -> SSPR round trip, as SSPR 4.8 drives it. */
class EndToEndFlowTest {

    @Test
    void fullFlowReturnsTheDuoVerifiedUser() throws Exception {
        Flow flow = new Flow();
        String token = flow.accessTokenFor("jdoe");
        Resp info = flow.userinfoPost(new Req().bearer(token).param("access_token", token).param("attributes", "username"));
        assertEquals(200, info.status);
        assertEquals("jdoe", Flow.JSON.readTree(info.body()).get("username").asText());

        // Every store is empty again: each handle, transaction, code and token was single use.
        assertEquals(0, flow.shim.ctx.handles().size());
        assertEquals(0, flow.shim.ctx.transactions().size());
        assertEquals(0, flow.shim.ctx.codes().size());
        assertEquals(0, flow.shim.ctx.tokens().size());

        // One audit line per step, none containing a secret.
        assertTrue(flow.shim.audited("sign", "ok"));
        assertTrue(flow.shim.audited("grant", "redirect_to_duo"));
        assertTrue(flow.shim.audited("duo_callback", "allow"));
        assertTrue(flow.shim.audited("token", "ok"));
        assertTrue(flow.shim.audited("userinfo", "ok"));
        assertFalse(String.join("\n", flow.shim.audit).contains(token));
    }

    @Test
    void twoUsersInParallelEachGetOnlyTheirOwnIdentity() throws Exception {
        Flow flow = new Flow();
        Flow.Started alice = flow.start("alice", "a-state");
        Flow.Started bob = flow.start("bob", "b-state");
        Resp bobCb = flow.callback(Flow.callbackRequest(bob.txnCookie(), bob.duoState(), flow.shim.duo.completePrompt("bob")));
        Resp aliceCb = flow.callback(Flow.callbackRequest(alice.txnCookie(), alice.duoState(), flow.shim.duo.completePrompt("alice")));
        String bobToken = Flow.JSON.readTree(flow.token(Flow.tokenRequest(Flow.query(bobCb.redirect).get("code"))).body())
                .get("access_token").asText();
        String aliceToken = Flow.JSON.readTree(flow.token(Flow.tokenRequest(Flow.query(aliceCb.redirect).get("code"))).body())
                .get("access_token").asText();
        assertEquals("bob", Flow.JSON.readTree(flow.userinfoPost(new Req().bearer(bobToken)).body()).get("username").asText());
        assertEquals("alice", Flow.JSON.readTree(flow.userinfoPost(new Req().bearer(aliceToken)).body()).get("username").asText());
        assertEquals("b-state", Flow.query(bobCb.redirect).get("state"));
        assertEquals("a-state", Flow.query(aliceCb.redirect).get("state"));
    }
}
