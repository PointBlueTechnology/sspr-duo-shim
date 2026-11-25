package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.model.IssuedCode;
import duoshim.model.IssuedToken;
import duoshim.store.StoreFullException;
import duoshim.web.RedirectUris;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static duoshim.web.Responses.json;
import static duoshim.web.Responses.oauthError;

/**
 * POST /oauth/token: SSPR exchanges the authorization code for an access token. The response
 * deliberately has no id_token: SSPR 4.8 would otherwise read the username from it using the
 * SSO setting instead of calling /oauth/userinfo.
 */
public final class TokenServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;
    private static final String EVENT = "token";

    public TokenServlet() {
    }

    TokenServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShimContext ctx = ctx();
        String ip = ip(req);

        if (!ctx.clientAuthenticator().authenticate(req.getHeader("Authorization"),
                req.getParameter("client_id"), req.getParameter("client_secret"))) {
            ctx.audit().record(EVENT, null, "invalid_client", ip);
            oauthError(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_client", null);
            return;
        }
        String formClientId = req.getParameter("client_id");
        if (formClientId != null && !ctx.clientAuthenticator().isClientId(formClientId)) {
            ctx.audit().record(EVENT, null, "client_id_mismatch", ip);
            oauthError(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_client", null);
            return;
        }
        if (!"authorization_code".equals(req.getParameter("grant_type"))) {
            ctx.audit().record(EVENT, null, "unsupported_grant_type", ip);
            oauthError(resp, HttpServletResponse.SC_BAD_REQUEST, "unsupported_grant_type", null);
            return;
        }
        Optional<IssuedCode> issued = ctx.codes().consume(req.getParameter("code"));
        if (issued.isEmpty()) {
            ctx.audit().record(EVENT, null, "invalid_grant", ip);
            oauthError(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant", null);
            return;
        }
        IssuedCode code = issued.get();
        if (!RedirectUris.exactMatch(code.redirectUri(), req.getParameter("redirect_uri"))) {
            ctx.audit().record(EVENT, code.username(), "redirect_uri_mismatch", ip);
            oauthError(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant", null);
            return;
        }
        String token;
        try {
            token = ctx.tokens().put(new IssuedToken(code.username()));
        } catch (StoreFullException e) {
            ctx.audit().record(EVENT, code.username(), "store_full", ip);
            oauthError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "temporarily_unavailable", null);
            return;
        }
        ctx.audit().record(EVENT, code.username(), "ok", ip);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", ShimContext.TOKEN_TTL.toSeconds());
        json(resp, HttpServletResponse.SC_OK, body);
    }
}
