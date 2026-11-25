package duoshim.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import duoshim.ShimContext;
import duoshim.model.SignedUser;
import duoshim.store.StoreFullException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

import static duoshim.web.Responses.json;
import static duoshim.web.Responses.oauthError;

/**
 * POST /oauth/sign: server-to-server call from SSPR before it redirects the browser.
 * Form field "data" is JSON [{"username":"..."}]. Returns {"data":"&lt;handle&gt;"}; the handle
 * reaches /oauth/grant in the "parameters" query field and can be redeemed once within 2 minutes.
 */
public final class SignServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT = "sign";

    public SignServlet() {
    }

    SignServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);

        if (!ctx.signPerIp().tryAcquire(ip)) {
            ctx.audit().record(EVENT, null, "rate_limited_ip", ip);
            oauthError(resp, 429, "slow_down", "too many requests");
            return;
        }
        if (!ctx.clientAuthenticator().authenticate(req.getHeader("Authorization"),
                req.getParameter("client_id"), req.getParameter("client_secret"))) {
            ctx.audit().record(EVENT, null, "invalid_client", ip);
            oauthError(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_client", null);
            return;
        }
        String username = parseUsername(req.getParameter("data"));
        if (username == null) {
            ctx.audit().record(EVENT, null, "invalid_request", ip);
            oauthError(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid_request", "data must be [{\"username\":\"...\"}]");
            return;
        }
        if (!ctx.signPerUser().tryAcquire(userKey(username))) {
            ctx.audit().record(EVENT, username, "rate_limited_user", ip);
            oauthError(resp, 429, "slow_down", "too many requests");
            return;
        }
        String handle;
        try {
            handle = ctx.handles().put(new SignedUser(username));
        } catch (StoreFullException e) {
            ctx.audit().record(EVENT, username, "store_full", ip);
            oauthError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "temporarily_unavailable", null);
            return;
        }
        ctx.audit().record(EVENT, username, "ok", ip);
        json(resp, HttpServletResponse.SC_OK, Map.of("data", handle));
    }

    /** Accepts exactly one object with a non-empty textual "username"; anything else is null. */
    static String parseUsername(String data) {
        if (data == null || data.length() > 4096) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(data);
            if (root == null || !root.isArray() || root.size() != 1) {
                return null;
            }
            JsonNode u = root.get(0).get("username");
            if (u == null || !u.isTextual()) {
                return null;
            }
            String username = u.asText().trim();
            return validUsername(username) ? username : null;
        } catch (IOException e) {
            return null;
        }
    }
}
