package duoshim.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** redirect_uri checks and building redirects back to SSPR. */
public final class RedirectUris {

    private RedirectUris() {
    }

    /** Exact, case-sensitive string match: no normalisation, prefix or pattern matching. */
    public static boolean exactMatch(String configured, String presented) {
        return configured != null && configured.equals(presented);
    }

    /** Appends query parameters, URL-encoded, to a redirect URI that has no fragment. */
    public static String withParams(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base);
        char sep = base.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep)
              .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }
}
