package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.duo.DuoGateway;
import duoshim.duo.DuoGatewayException;
import duoshim.model.PendingTransaction;
import duoshim.model.SignedUser;
import duoshim.store.Secrets;
import duoshim.store.StoreFullException;
import duoshim.web.RedirectUris;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

import static duoshim.web.Responses.errorPage;

/**
 * GET /oauth/grant and /oauth/auth: the browser arrives here from SSPR. Both paths are served
 * because SSPR 4.5 derives its /sign URL by replacing "/grant" in the login URL, while SSPR 4.8
 * takes everything before the last "/auth" and appends "sign".
 *
 * <p>The username comes only from the single-use handle SSPR registered at /oauth/sign; nothing
 * the browser sends can choose it.
 */
public final class GrantServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final String EVENT = "grant";

    /** Cookie binding the Duo round trip to this browser. */
    public static final String TXN_COOKIE = "DUOSHIM_TXN";

    public GrantServlet() {
    }

    GrantServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);
        String returnUrl = ctx.config().ssprReturnUrl();

        if (!ctx.grantPerIp().tryAcquire(ip)) {
            ctx.audit().record(EVENT, null, "rate_limited_ip", ip);
            errorPage(resp, 429, "Too many attempts. Wait a few minutes and try again.", returnUrl);
            return;
        }
        if (!ctx.clientAuthenticator().isClientId(req.getParameter("client_id"))) {
            ctx.audit().record(EVENT, null, "invalid_client_id", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The request did not come from a recognised application.", returnUrl);
            return;
        }
        String redirectUri = req.getParameter("redirect_uri");
        if (!RedirectUris.exactMatch(ctx.config().ssprRedirectUri(), redirectUri)) {
            ctx.audit().record(EVENT, null, "invalid_redirect_uri", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The request had an unexpected return address.", returnUrl);
            return;
        }
        String state = req.getParameter("state");
        if (state == null || state.isEmpty() || state.length() > MAX_STATE_LENGTH) {
            ctx.audit().record(EVENT, null, "invalid_state", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The request was incomplete.", returnUrl);
            return;
        }
        // From here SSPR's own cancel path is safe to offer: redirect_uri and state are known.
        String cancelUrl = ssprCancelUrl(redirectUri, state);
        if (!"code".equals(req.getParameter("response_type"))) {
            ctx.audit().record(EVENT, null, "unsupported_response_type", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The request was not supported.", cancelUrl);
            return;
        }
        Optional<SignedUser> signed = ctx.handles().consume(req.getParameter("parameters"));
        if (signed.isEmpty()) {
            ctx.audit().record(EVENT, null, "invalid_or_expired_handle", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "This verification link has expired or was already used. Start the password reset again.", cancelUrl);
            return;
        }
        String username = signed.get().username();
        if (!ctx.grantPerUser().tryAcquire(userKey(username))) {
            ctx.audit().record(EVENT, username, "rate_limited_user", ip);
            errorPage(resp, 429, "Too many attempts. Wait a few minutes and try again.", cancelUrl);
            return;
        }

        String duoState;
        String nonce = Secrets.newToken();
        String authUrl;
        try {
            DuoGateway duo = ctx.duo().create();
            duo.healthCheck();
            duoState = duo.generateState();
            authUrl = duo.createAuthUrl(username, duoState, nonce);
        } catch (DuoGatewayException | RuntimeException e) {
            ctx.audit().record(EVENT, username, "duo_unavailable", ip);
            LOG.warning("duoshim: Duo unavailable during grant: " + e.getMessage());
            errorPage(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Duo verification is unavailable right now. Try again later.", cancelUrl);
            return;
        }

        String txnId;
        try {
            txnId = ctx.transactions().put(new PendingTransaction(username, state, redirectUri, duoState, nonce));
        } catch (StoreFullException e) {
            ctx.audit().record(EVENT, username, "store_full", ip);
            errorPage(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The service is busy. Try again shortly.", cancelUrl);
            return;
        }
        setTxnCookie(resp, req.getContextPath(), txnId, (int) ctx.config().transactionTtl().toSeconds(), ctx.config().cookieSecure());
        ctx.audit().record(EVENT, username, "redirect_to_duo", ip);
        resp.sendRedirect(authUrl);
    }

    /** Set-Cookie written by hand: javax.servlet.http.Cookie has no SameSite attribute. */
    static void setTxnCookie(HttpServletResponse resp, String contextPath, String value, int maxAgeSeconds, boolean secure) {
        resp.addHeader("Set-Cookie", TXN_COOKIE + "=" + value + "; Path=" + cookiePath(contextPath)
                + "; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
    }

    static void clearTxnCookie(HttpServletResponse resp, String contextPath, boolean secure) {
        resp.addHeader("Set-Cookie", TXN_COOKIE + "=; Path=" + cookiePath(contextPath)
                + "; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
    }

    /** The cookie is only sent to the Duo callback, nowhere else. */
    static String cookiePath(String contextPath) {
        return (contextPath == null ? "" : contextPath) + "/duo";
    }
}
