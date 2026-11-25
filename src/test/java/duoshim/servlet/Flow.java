package duoshim.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import duoshim.testsupport.TestShim;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static duoshim.testsupport.TestShim.SSPR_CLIENT_ID;
import static duoshim.testsupport.TestShim.SSPR_REDIRECT;
import static duoshim.testsupport.TestShim.SSPR_SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Drives the servlets through each step the way SSPR, the browser and Duo would. */
final class Flow {

    static final ObjectMapper JSON = new ObjectMapper();

    final TestShim shim;
    final SignServlet sign;
    final GrantServlet grant;
    final DuoCallbackServlet callback;
    final TokenServlet token;
    final UserInfoServlet userinfo;

    Flow(TestShim shim) {
        this.shim = shim;
        this.sign = new SignServlet(shim.ctx);
        this.grant = new GrantServlet(shim.ctx);
        this.callback = new DuoCallbackServlet(shim.ctx);
        this.token = new TokenServlet(shim.ctx);
        this.userinfo = new UserInfoServlet(shim.ctx);
    }

    Flow() {
        this(new TestShim());
    }

    static String data(String username) {
        return "[{\"username\":\"" + username + "\"}]";
    }

    Resp sign(Req req) throws Exception {
        Resp r = new Resp();
        sign.doPost(req.build(), r.response);
        return r;
    }

    /** SSPR's /sign call; returns the handle. */
    String signHandle(String username) throws Exception {
        Resp r = sign(new Req().ip("10.0.0.5").basic(SSPR_CLIENT_ID, SSPR_SECRET).param("data", data(username)));
        assertEquals(200, r.status, r.body());
        return JSON.readTree(r.body()).get("data").asText();
    }

    static Req grantRequest(String handle, String ssprState) {
        return new Req().param("client_id", SSPR_CLIENT_ID).param("redirect_uri", SSPR_REDIRECT)
                .param("response_type", "code").param("state", ssprState).param("parameters", handle)
                .param("scope", "openid");
    }

    Resp grant(Req req) throws Exception {
        Resp r = new Resp();
        grant.doGet(req.build(), r.response);
        return r;
    }

    /** Browser hits /oauth/grant; returns the started transaction. */
    Started start(String username, String ssprState) throws Exception {
        Resp r = grant(grantRequest(signHandle(username), ssprState));
        assertEquals(302, r.status, r.body());
        String cookie = r.headerValues("Set-Cookie").get(0);
        String txn = cookie.substring((GrantServlet.TXN_COOKIE + "=").length(), cookie.indexOf(';'));
        String duoState = r.redirect.substring(r.redirect.indexOf("request=fake-") + "request=fake-".length());
        return new Started(txn, duoState, r);
    }

    record Started(String txnCookie, String duoState, Resp grantResponse) {
    }

    Resp callback(Req req) throws Exception {
        Resp r = new Resp();
        callback.doGet(req.build(), r.response);
        return r;
    }

    static Req callbackRequest(String txnCookie, String duoState, String duoCode) {
        Req req = new Req().param("state", duoState).param("duo_code", duoCode);
        if (txnCookie != null) {
            req.cookie(GrantServlet.TXN_COOKIE, txnCookie);
        }
        return req;
    }

    Resp token(Req req) throws Exception {
        Resp r = new Resp();
        token.doPost(req.build(), r.response);
        return r;
    }

    static Req tokenRequest(String code) {
        return new Req().ip("10.0.0.5").basic(SSPR_CLIENT_ID, SSPR_SECRET).param("code", code)
                .param("grant_type", "authorization_code").param("redirect_uri", SSPR_REDIRECT)
                .param("client_id", SSPR_CLIENT_ID).param("client_secret", SSPR_SECRET);
    }

    Resp userinfoPost(Req req) throws Exception {
        Resp r = new Resp();
        userinfo.doPost(req.build(), r.response);
        return r;
    }

    Resp userinfoGet(Req req) throws Exception {
        Resp r = new Resp();
        userinfo.doGet(req.build(), r.response);
        return r;
    }

    /** Full happy path up to and including the redirect back to SSPR; returns the code. */
    String codeFor(String username) throws Exception {
        Started s = start(username, "sspr-state-1");
        Resp cb = callback(callbackRequest(s.txnCookie(), s.duoState(), shim.duo.completePrompt(username)));
        assertEquals(302, cb.status, cb.body());
        String code = query(cb.redirect).get("code");
        assertNotNull(code);
        return code;
    }

    String accessTokenFor(String username) throws Exception {
        Resp r = token(tokenRequest(codeFor(username)));
        assertEquals(200, r.status, r.body());
        return JSON.readTree(r.body()).get("access_token").asText();
    }

    static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
