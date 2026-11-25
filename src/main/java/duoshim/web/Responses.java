package duoshim.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON bodies, OAuth-style errors and the plain HTML error page. */
public final class Responses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Responses() {
    }

    public static void json(HttpServletResponse resp, int status, Map<String, ?> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(JSON.writeValueAsString(body));
    }

    /** RFC 6749 style error: {"error": code, "error_description": text}. */
    public static void oauthError(HttpServletResponse resp, int status, String error, String description) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        if (description != null) {
            body.put("error_description", description);
        }
        if (status == HttpServletResponse.SC_UNAUTHORIZED && "invalid_client".equals(error)) {
            resp.setHeader("WWW-Authenticate", "Basic realm=\"duoshim\"");
        }
        if (status == HttpServletResponse.SC_UNAUTHORIZED && "invalid_token".equals(error)) {
            resp.setHeader("WWW-Authenticate", "Bearer realm=\"duoshim\", error=\"invalid_token\"");
        }
        json(resp, status, body);
    }

    /**
     * Plain HTML error page with one link back to SSPR. Never carries an authorization code.
     *
     * @param linkUrl  where the link goes; may be null for no link
     */
    public static void errorPage(HttpServletResponse resp, int status, String message, String linkUrl) throws IOException {
        resp.setStatus(status);
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter w = resp.getWriter();
        w.write("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Verification failed</title></head><body>\n"
                + "<h1>Verification could not be completed</h1>\n<p>" + html(message) + "</p>\n");
        if (linkUrl != null) {
            w.write("<p><a href=\"" + html(linkUrl) + "\">Return to password reset</a></p>\n");
        }
        w.write("</body></html>\n");
    }

    /** Plain HTML page offering two links. Never carries an authorization code. */
    public static void choicePage(HttpServletResponse resp, String heading, String message,
                                  String primaryLabel, String primaryUrl,
                                  String secondaryLabel, String secondaryUrl) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter w = resp.getWriter();
        w.write("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + html(heading) + "</title></head><body>\n"
                + "<h1>" + html(heading) + "</h1>\n<p>" + html(message) + "</p>\n"
                + "<p><a href=\"" + html(primaryUrl) + "\">" + html(primaryLabel) + "</a></p>\n"
                + "<p><a href=\"" + html(secondaryUrl) + "\">" + html(secondaryLabel) + "</a></p>\n"
                + "</body></html>\n");
    }

    static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
