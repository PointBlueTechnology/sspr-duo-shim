package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.model.IssuedToken;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static duoshim.web.Responses.json;
import static duoshim.web.Responses.oauthError;

/**
 * /oauth/userinfo: returns the Duo-verified username under the configured attribute name.
 * SSPR posts by default; SSPR 4.8 sends GET when "OAuth Profile / UserInfo Service Method Type"
 * is GET. The token comes from a Bearer header or, on POST only, an access_token form field.
 */
public final class UserInfoServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final String EVENT = "userinfo";

    public UserInfoServlet() {
    }

    UserInfoServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, false);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, true);
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp, boolean allowFormToken) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);
        String token = bearer(req.getHeader("Authorization"));
        if (token == null && allowFormToken) {
            token = req.getParameter("access_token");
        }
        if (token == null || token.isEmpty()) {
            ctx.audit().record(EVENT, null, "missing_token", ip);
            oauthError(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", null);
            return;
        }
        Optional<IssuedToken> issued = ctx.tokens().consume(token);
        if (issued.isEmpty()) {
            ctx.audit().record(EVENT, null, "invalid_token", ip);
            oauthError(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", null);
            return;
        }
        String username = issued.get().username();
        ctx.audit().record(EVENT, username, "ok", ip);
        json(resp, HttpServletResponse.SC_OK, Map.of(ctx.config().userinfoAttribute(), username));
    }

    static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        if (h.length() > 7 && h.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
            return h.substring(7).trim();
        }
        return null;
    }
}
