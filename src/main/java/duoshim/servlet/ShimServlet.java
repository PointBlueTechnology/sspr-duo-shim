package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.web.RedirectUris;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Base servlet: gets the {@link ShimContext} from the container, or from a test. */
abstract class ShimServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Operational log (not the audit log). Never log secrets, codes, tokens or handles. */
    static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("duoshim");

    /** Longest username accepted from SSPR's /sign call. */
    static final int MAX_USERNAME_LENGTH = 256;
    /** Longest SSPR state accepted (SSPR's encrypted state is a few hundred characters). */
    static final int MAX_STATE_LENGTH = 4096;

    private transient ShimContext ctx;

    ShimServlet() {
    }

    ShimServlet(ShimContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void init() throws ServletException {
        if (ctx == null) {
            ctx = ShimContext.from(getServletContext());
        }
    }

    ShimContext ctx() {
        return ctx;
    }

    static String ip(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    /** Lower-cased key for per-username rate limiting. */
    static String userKey(String username) {
        return username.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * SSPR's own cancel path: an OAuth error with sub_error=usrcan makes SSPR drop the OAuth
     * method and return the user to forgotten password. Carries no code.
     */
    static String ssprCancelUrl(String redirectUri, String ssprState) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("error", "access_denied");
        p.put("sub_error", "usrcan");
        p.put("state", ssprState);
        return RedirectUris.withParams(redirectUri, p);
    }

    static boolean validUsername(String u) {
        if (u == null || u.isBlank() || u.length() > MAX_USERNAME_LENGTH) {
            return false;
        }
        return u.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}
