package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static duoshim.testsupport.TestShim.SSPR_REDIRECT;
import static duoshim.testsupport.TestShim.SSPR_RETURN;
import static org.junit.jupiter.api.Assertions.*;

/** "Start over" on the did-not-finish page: void the attempt, clear the cookie, hand back to SSPR. */
class StartOverServletTest {

    private final Flow flow = new Flow();
    private final StartOverServlet startOver = new StartOverServlet(flow.shim.ctx);

    private Resp startOver(Req req) throws Exception {
        Resp r = new Resp();
        startOver.doGet(req.build(), r.response);
        return r;
    }

    private static String cookieOf(Resp r) {
        String c = r.headerValues("Set-Cookie").get(0);
        return c.substring((GrantServlet.TXN_COOKIE + "=").length(), c.indexOf(';'));
    }

    private static String startOverToken(String page) {
        Matcher m = Pattern.compile("/duoshim/oauth/startover\\?t=([^\"]+)\"").matcher(page);
        assertTrue(m.find(), "page has a Start over link");
        return m.group(1);
    }

    /** Unfinished attempt, then a second arrival that shows the choice page. */
    private Resp choicePage(String user, String ssprState) throws Exception {
        Flow.Started first = flow.start(user, "abandoned-state");
        Resp page = flow.grant(Flow.grantRequest(flow.signHandle(user), ssprState)
                .cookie(GrantServlet.TXN_COOKIE, first.txnCookie()));
        assertEquals(200, page.status);
        return page;
    }

    @Test
    void clearsCookieVoidsAttemptAndUsesSsprCancelPath() throws Exception {
        Resp page = choicePage("u80007", "current-state");
        Resp r = startOver(new Req().param("t", startOverToken(page.body()))
                .cookie(GrantServlet.TXN_COOKIE, cookieOf(page)));
        assertEquals(302, r.status);
        assertEquals(SSPR_REDIRECT + "?error=access_denied&sub_error=usrcan&state=current-state", r.redirect);
        assertTrue(r.headerValues("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0") && c.contains("Path=/duoshim;")));
        assertEquals(0, flow.shim.ctx.transactions().size());
        assertTrue(flow.shim.audited("startover", "ok"));
    }

    @Test
    void nextResetInThatBrowserGoesStraightToDuo() throws Exception {
        Resp page = choicePage("u80007", "current-state");
        startOver(new Req().param("t", startOverToken(page.body())).cookie(GrantServlet.TXN_COOKIE, cookieOf(page)));
        // The browser has applied the cleared cookie, so a different user's reset starts normally.
        Resp next = flow.grant(Flow.grantRequest(flow.signHandle("jcombs"), "fresh"));
        assertEquals(302, next.status);
        assertFalse(flow.shim.audit.stream().anyMatch(l -> l.contains("user=\"jcombs\" result=repeat_choice")));
    }

    @Test
    void redirectTargetComesFromTheTransactionNotTheRequest() throws Exception {
        Resp page = choicePage("u80007", "current-state");
        Resp r = startOver(new Req().param("t", startOverToken(page.body()))
                .param("redirect_uri", "https://evil.example/").param("state", "forged")
                .cookie(GrantServlet.TXN_COOKIE, cookieOf(page)));
        assertTrue(r.redirect.startsWith(SSPR_REDIRECT + "?"));
        assertTrue(r.redirect.endsWith("state=current-state"));
    }

    @Test
    void wrongOrMissingTokenIsRejectedAndKeepsTheAttempt() throws Exception {
        Resp page = choicePage("u80007", "current-state");
        String cookie = cookieOf(page);
        for (String t : new String[]{null, "", "forged", StartOverServlet.token("other-transaction")}) {
            Resp r = startOver(new Req().param("t", t).cookie(GrantServlet.TXN_COOKIE, cookie));
            assertEquals(400, r.status, "token " + t);
            assertNull(r.redirect);
        }
        assertEquals(1, flow.shim.ctx.transactions().size(), "another site linking here can't cancel the attempt");
        assertTrue(flow.shim.audited("startover", "invalid_token"));
    }

    @Test
    void missingCookieShowsPageWithLinkBackToSspr() throws Exception {
        Resp r = startOver(new Req().param("t", "anything"));
        assertEquals(400, r.status);
        assertNull(r.redirect);
        assertTrue(r.body().contains(SSPR_RETURN));
    }

    @Test
    void expiredOrReusedTransactionIsRejected() throws Exception {
        Resp page = choicePage("u80007", "current-state");
        String cookie = cookieOf(page);
        String t = startOverToken(page.body());
        flow.shim.clock.advance(Duration.ofSeconds(300));
        Resp r = startOver(new Req().param("t", t).cookie(GrantServlet.TXN_COOKIE, cookie));
        assertEquals(400, r.status);
        assertNull(r.redirect);
        assertTrue(r.headerValues("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0")), "stale cookie cleared");
    }

    @Test
    void repeatWithDuoDownClearsTheStaleCookie() throws Exception {
        Flow.Started first = flow.start("bob", "s1");
        flow.shim.duo.failHealthCheck = true;
        Resp r = flow.grant(Flow.grantRequest(flow.signHandle("bob"), "s2").cookie(GrantServlet.TXN_COOKIE, first.txnCookie()));
        assertEquals(503, r.status);
        assertTrue(r.headerValues("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0")));
    }
}
