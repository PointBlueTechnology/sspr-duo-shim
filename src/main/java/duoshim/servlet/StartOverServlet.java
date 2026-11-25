package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.model.PendingTransaction;
import duoshim.store.Secrets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

import static duoshim.web.Responses.errorPage;

/**
 * GET /oauth/startover: the "Start over" link on the "did not finish" page. Voids this browser's
 * pending Duo transaction, clears the transaction cookie, then sends the browser to SSPR's own
 * cancel path, which clears the forgotten-password state and shows the username page.
 *
 * <p>The redirect target is built from the stored transaction (validated redirect_uri and SSPR
 * state), never from request parameters. The {@code t} parameter, derived from the transaction
 * cookie, stops another site from cancelling a user's attempt by linking here.
 */
public final class StartOverServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final String EVENT = "startover";

    public StartOverServlet() {
    }

    StartOverServlet(ShimContext ctx) {
        super(ctx);
    }

    /** Value for the link's {@code t} parameter; only someone holding the cookie can produce it. */
    static String token(String txnId) {
        return Secrets.digest("startover:" + txnId);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);
        String returnUrl = ctx.config().ssprReturnUrl();

        String txnId = GrantServlet.readTxnCookie(req);
        if (txnId == null) {
            ctx.audit().record(EVENT, null, "no_transaction_cookie", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "This verification has already ended. Start the password reset again.", returnUrl);
            return;
        }
        if (!Secrets.constantTimeEquals(req.getParameter("t"), token(txnId))) {
            ctx.audit().record(EVENT, null, "invalid_token", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST, "The request did not match this verification.", returnUrl);
            return;
        }
        GrantServlet.clearTxnCookie(resp, req.getContextPath(), ctx.config().cookieSecure());
        Optional<PendingTransaction> found = ctx.transactions().consume(txnId);
        if (found.isEmpty()) {
            ctx.audit().record(EVENT, null, "unknown_or_expired_transaction", ip);
            errorPage(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "This verification has expired. Start the password reset again.", returnUrl);
            return;
        }
        PendingTransaction txn = found.get();
        ctx.audit().record(EVENT, txn.username(), "ok", ip);
        resp.sendRedirect(ssprCancelUrl(txn.redirectUri(), txn.ssprState()));
    }
}
