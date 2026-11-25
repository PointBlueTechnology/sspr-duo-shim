package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duo's deny page (for example an unenrolled user) never returns to the shim, and SSPR then keeps
 * sending the user back to /oauth/auth. The second arrival must offer a way out, not loop into Duo.
 */
class RepeatAttemptTest {

    private final Flow flow = new Flow();

    private static String newCookie(Resp r) {
        String c = r.headerValues("Set-Cookie").get(0);
        return c.substring((GrantServlet.TXN_COOKIE + "=").length(), c.indexOf(';'));
    }

    private static String duoStateFromPage(String body) {
        Matcher m = Pattern.compile("request=fake-([^\"&]+)\"").matcher(body);
        assertTrue(m.find(), "page links to Duo");
        return m.group(1);
    }

    /** First attempt goes to Duo and never comes back; the browser still holds the cookie. */
    private Resp secondArrival(String user, String firstCookie, String ssprState) throws Exception {
        return flow.grant(Flow.grantRequest(flow.signHandle(user), ssprState).cookie(GrantServlet.TXN_COOKIE, firstCookie));
    }

    @Test
    void secondArrivalShowsChoiceInsteadOfRedirecting() throws Exception {
        Flow.Started first = flow.start("u80007", "state-1");
        Resp r = secondArrival("u80007", first.txnCookie(), "state-2");

        assertEquals(200, r.status);
        assertNull(r.redirect, "no automatic redirect back into Duo");
        assertEquals("text/html;charset=UTF-8", r.contentType);
        assertTrue(r.body().contains(">Try Duo again</a>"));
        assertTrue(r.body().contains("href=\"https://api-test.duosecurity.com/"));
        assertTrue(r.body().contains(">Start over</a>"));
        assertTrue(r.body().contains("href=\"/duoshim/oauth/startover?t="),
                "Start over goes through the shim so it can clear its cookie");
        assertFalse(r.body().contains("code="), "never carries an authorization code");
        assertTrue(flow.shim.audited("grant", "repeat_choice"));
    }

    @Test
    void previousTransactionIsVoidedAndReplacedByOne() throws Exception {
        Flow.Started first = flow.start("u80007", "state-1");
        Resp r = secondArrival("u80007", first.txnCookie(), "state-2");
        String second = newCookie(r);
        assertNotEquals(first.txnCookie(), second);
        assertEquals(1, flow.shim.ctx.transactions().size(), "old transaction consumed, new one stored");
        assertTrue(flow.shim.ctx.transactions().consume(first.txnCookie()).isEmpty());
    }

    @Test
    void oldDuoTabCannotCompleteAfterTheChoice() throws Exception {
        Flow.Started first = flow.start("bob", "state-1");
        Resp r = secondArrival("bob", first.txnCookie(), "state-2");
        // The browser now holds the new cookie; the old Duo tab returns with the old Duo state.
        Resp cb = flow.callback(Flow.callbackRequest(newCookie(r), first.duoState(), flow.shim.duo.completePrompt("bob")));
        assertEquals(400, cb.status);
        assertNull(cb.redirect);
        assertTrue(flow.shim.audited("duo_callback", "state_mismatch"));
    }

    @Test
    void tryDuoAgainCompletesNormally() throws Exception {
        Flow.Started first = flow.start("bob", "state-1");
        Resp r = secondArrival("bob", first.txnCookie(), "state-2");
        Resp cb = flow.callback(Flow.callbackRequest(newCookie(r), duoStateFromPage(r.body()),
                flow.shim.duo.completePrompt("bob")));
        assertEquals(302, cb.status);
        Map<String, String> q = Flow.query(cb.redirect);
        assertEquals("state-2", q.get("state"), "returns the current SSPR state, not the abandoned one");
        assertNotNull(q.get("code"));
    }

    @Test
    void completedAttemptClearsCookieSoNextAttemptGoesStraightToDuo() throws Exception {
        Flow.Started s = flow.start("bob", "state-1");
        Resp cb = flow.callback(Flow.callbackRequest(s.txnCookie(), s.duoState(), flow.shim.duo.completePrompt("bob")));
        assertTrue(cb.headerValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith(GrantServlet.TXN_COOKIE + "=;") && c.contains("Path=/duoshim;") && c.contains("Max-Age=0")));
        // The browser has dropped the cookie, so the next start is a plain redirect.
        assertEquals(302, flow.start("bob", "state-2").grantResponse().status);
    }

    @Test
    void repeatStillFailsClosedWhenDuoIsDown() throws Exception {
        Flow.Started first = flow.start("bob", "state-1");
        flow.shim.duo.failHealthCheck = true;
        Resp r = secondArrival("bob", first.txnCookie(), "state-2");
        assertEquals(503, r.status);
        assertFalse(r.body().contains("Try Duo again"));
        assertTrue(r.body().contains("sub_error=usrcan"), "still offers the way back to SSPR");
    }
}
