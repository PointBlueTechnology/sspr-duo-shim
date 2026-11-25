package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static duoshim.testsupport.TestShim.SSPR_REDIRECT;
import static org.junit.jupiter.api.Assertions.*;

class DuoCallbackServletTest {

    private final Flow flow = new Flow();

    /** Every failure: error page, no redirect, no code minted, cookie cleared. */
    private void assertRejected(Resp r, int status) {
        assertEquals(status, r.status, r.body());
        assertNull(r.redirect, "never redirects on failure");
        assertEquals(0, flow.shim.ctx.codes().size(), "no authorization code minted");
        assertFalse(r.body().contains("code="), "error page never carries a code");
        assertTrue(r.headerValues("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0")), "cookie cleared");
    }

    @Test
    void allowRedirectsToSsprWithCodeAndOriginalState() throws Exception {
        Flow.Started s = flow.start("bob", "sspr-state-xyz");
        Resp r = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertEquals(302, r.status);
        assertTrue(r.redirect.startsWith(SSPR_REDIRECT + "?"));
        Map<String, String> q = Flow.query(r.redirect);
        assertEquals("sspr-state-xyz", q.get("state"));
        assertEquals(43, q.get("code").length());
        assertEquals(1, flow.shim.ctx.codes().size());
        assertEquals(0, flow.shim.ctx.transactions().size(), "transaction consumed");
        assertTrue(flow.shim.audited("duo_callback", "allow"));
        assertFalse(String.join("\n", flow.shim.audit).contains(q.get("code")), "codes are never logged");
    }

    @Test
    void missingCookieIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        Resp r = flow.callback(Flow.callbackRequest(null, s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertRejected(r, 400);
        assertTrue(flow.shim.audited("duo_callback", "no_transaction_cookie"));
    }

    @Test
    void unknownCookieIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        Resp r = flow.callback(Flow.callbackRequest("forged", s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertRejected(r, 400);
    }

    @Test
    void transactionCannotBeReplayed() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        assertEquals(302, flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(),
                flow.shim.duo.completePrompt("bob"))).status);
        flow.shim.ctx.codes().sweep();
        Resp again = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertEquals(400, again.status);
        assertNull(again.redirect);
    }

    @Test
    void expiredTransactionIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        flow.shim.clock.advance(Duration.ofSeconds(300));
        assertRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(),
                flow.shim.duo.completePrompt("bob"))), 400);
    }

    @Test
    void duoStateMismatchIsRejectedButKeepsTheTransaction() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        String duoCode = flow.shim.duo.completePrompt("bob");
        Resp forged = flow.callback(Flow.callbackRequest(s.txnCookie(), "duo-state-wrong-0000000000", duoCode));
        assertMismatchRejected(forged);
        assertEquals(1, flow.shim.ctx.transactions().size(), "a wrong state does not use up the attempt");
        Resp real = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), duoCode));
        assertEquals(302, real.status, "the genuine Duo return still completes");
    }

    /** Another site navigating the browser to the callback mid-prompt must not cancel the attempt (M1). */
    @Test
    void forgedCallbackWithErrorDoesNotCancelThePendingAttempt() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        assertMismatchRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), "x", null).param("error", "access_denied")));
        assertMismatchRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), null, null)));
        Resp real = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertEquals(302, real.status);
    }

    /** Wrong state: error page, nothing minted, and the cookie is left alone. */
    private void assertMismatchRejected(Resp r) {
        assertEquals(400, r.status, r.body());
        assertNull(r.redirect);
        assertEquals(0, flow.shim.ctx.codes().size());
        assertTrue(r.headerValues("Set-Cookie").isEmpty(), "cookie kept so the real return still works");
        assertTrue(flow.shim.audited("duo_callback", "state_mismatch"));
    }

    @Test
    void missingDuoCodeIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        assertRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), null)), 400);
    }

    @Test
    void duoErrorParameterIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        Resp r = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob"))
                .param("error", "access_denied"));
        assertRejected(r, 403);
    }

    @Test
    void exchangeFailureIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        flow.shim.duo.failExchange = true;
        assertRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(),
                flow.shim.duo.completePrompt("bob"))), 502);
        assertTrue(flow.shim.audited("duo_callback", "duo_exchange_failed"));
    }

    @Test
    void denyIsRejected() throws Exception {
        Flow.Started s = flow.start("bob", "s");
        flow.shim.duo.status = "deny";
        assertRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(),
                flow.shim.duo.completePrompt("bob"))), 403);
        assertTrue(flow.shim.audited("duo_callback", "denied"));
    }

    @Test
    void failurePageOffersSsprCancelPathWithoutCode() throws Exception {
        Flow.Started s = flow.start("bob", "sspr-state");
        flow.shim.duo.status = "deny";
        Resp r = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertTrue(r.body().contains(SSPR_REDIRECT + "?error=access_denied&amp;sub_error=usrcan&amp;state=sspr-state"));
    }

    /** A Duo approval by user A must never complete a transaction started for user B. */
    @Test
    void duoResultForUserACannotCompleteTransactionForUserB() throws Exception {
        Flow.Started bob = flow.start("bob", "bob-state");
        // Alice completes a Duo prompt; her duo_code is replayed into Bob's transaction.
        String aliceDuoCode = flow.shim.duo.completePrompt("alice");
        Resp r = flow.callback(Flow.callbackRequest(bob.txnCookie(), bob.duoState(), aliceDuoCode));
        assertRejected(r, 403);
        assertTrue(flow.shim.audited("duo_callback", "user_mismatch"));
        assertEquals(0, flow.shim.ctx.tokens().size());
    }

    @Test
    void anotherUsersTransactionStateCannotBeUsed() throws Exception {
        Flow.Started alice = flow.start("alice", "alice-state");
        Flow.Started bob = flow.start("bob", "bob-state");
        // Bob's browser cookie with Alice's Duo state and Alice's approval.
        Resp r = flow.callback(Flow.callbackRequest(bob.txnCookie(), alice.duoState(), flow.shim.duo.completePrompt("alice")));
        assertMismatchRejected(r);
    }

    /** Same rule as the Duo SDK: preferred_username must equal the username exactly, case included. */
    @Test
    void usernameMustMatchExactly() throws Exception {
        Flow.Started s = flow.start("Bob", "s");
        assertRejected(flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(),
                flow.shim.duo.completePrompt("bob"))), 403);
        assertTrue(flow.shim.audited("duo_callback", "user_mismatch"));
    }

    @Test
    void codeCarriesTheTransactionUsername() throws Exception {
        Flow.Started s = flow.start("Bob", "s");
        Resp cb = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("Bob")));
        String code = Flow.query(cb.redirect).get("code");
        assertEquals("Bob", flow.shim.ctx.codes().consume(code).orElseThrow().username());
    }

}
