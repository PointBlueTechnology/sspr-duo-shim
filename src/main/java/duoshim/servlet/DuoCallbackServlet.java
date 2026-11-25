package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.duo.DuoGatewayException;
import duoshim.duo.DuoResult;
import duoshim.model.IssuedCode;
import duoshim.model.PendingTransaction;
import duoshim.store.Secrets;
import duoshim.store.StoreFullException;
import duoshim.web.RedirectUris;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static duoshim.web.Responses.errorPage;

/**
 * GET /duo/callback: Duo returns the browser here. Only a Duo ALLOW for exactly the user this
 * transaction was started for produces an authorization code; every other outcome is an error
 * page, never a redirect carrying a code.
 */
public final class DuoCallbackServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final String EVENT = "duo_callback";

    public DuoCallbackServlet() {
    }

    DuoCallbackServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);
        String returnUrl = ctx.config().ssprReturnUrl();

        String txnId = readTxnCookie(req);
        // The transaction is single use whatever happens next.
        GrantServlet.clearTxnCookie(resp, req.getContextPath(), ctx.config().cookieSecure());
        if (txnId == null) {
            ctx.audit().record(EVENT, null, "no_transaction_cookie", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "This verification was not started in this browser, or cookies are blocked.", returnUrl);
            return;
        }
        Optional<PendingTransaction> found = ctx.transactions().consume(txnId);
        if (found.isEmpty()) {
            ctx.audit().record(EVENT, null, "unknown_or_expired_transaction", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "This verification has expired or was already used. Start the password reset again.", returnUrl);
            return;
        }
        PendingTransaction txn = found.get();
        String cancelUrl = ssprCancelUrl(txn.redirectUri(), txn.ssprState());

        if (req.getParameter("error") != null) {
            ctx.audit().record(EVENT, txn.username(), "duo_error", ip);
            errorPage(resp, HttpServletResponse.SC_FORBIDDEN, "Duo did not complete the verification.", cancelUrl);
            return;
        }
        if (!Secrets.constantTimeEquals(req.getParameter("state"), txn.duoState())) {
            ctx.audit().record(EVENT, txn.username(), "state_mismatch", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The verification response did not match this request.", cancelUrl);
            return;
        }
        String duoCode = req.getParameter("duo_code");
        if (duoCode == null || duoCode.isEmpty() || duoCode.length() > 4096) {
            ctx.audit().record(EVENT, txn.username(), "missing_duo_code", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The verification response was incomplete.", cancelUrl);
            return;
        }

        DuoResult result;
        try {
            result = ctx.duo().create().exchange(duoCode, txn.username(), txn.duoNonce());
        } catch (DuoGatewayException | RuntimeException e) {
            ctx.audit().record(EVENT, txn.username(), "duo_exchange_failed", ip);
            LOG.warning("duoshim: Duo code exchange failed: " + e.getMessage());
            errorPage(resp, HttpServletResponse.SC_BAD_GATEWAY, "Duo verification could not be confirmed.", cancelUrl);
            return;
        }
        if (result == null || !result.allowed()) {
            ctx.audit().record(EVENT, txn.username(), "denied", ip);
            errorPage(resp, HttpServletResponse.SC_FORBIDDEN, "Duo did not approve the verification.", cancelUrl);
            return;
        }
        // Defence in depth: the SDK already checks preferred_username; check it again here.
        if (result.verifiedUsername() == null || !result.verifiedUsername().equalsIgnoreCase(txn.username())) {
            ctx.audit().record(EVENT, txn.username(), "user_mismatch", ip);
            errorPage(resp, HttpServletResponse.SC_FORBIDDEN, "Duo verified a different user.", cancelUrl);
            return;
        }

        String code;
        try {
            code = ctx.codes().put(new IssuedCode(txn.username(), txn.redirectUri()));
        } catch (StoreFullException e) {
            ctx.audit().record(EVENT, txn.username(), "store_full", ip);
            errorPage(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The service is busy. Try again shortly.", cancelUrl);
            return;
        }
        ctx.audit().record(EVENT, txn.username(), "allow", ip);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("state", txn.ssprState());
        resp.sendRedirect(RedirectUris.withParams(txn.redirectUri(), params));
    }

    static String readTxnCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (GrantServlet.TXN_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                return c.getValue();
            }
        }
        return null;
    }
}
