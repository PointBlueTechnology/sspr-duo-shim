package duoshim.testsupport;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal servlet request/response fakes built on dynamic proxies (no mocking library). */
public final class FakeHttp {

    private FakeHttp() {
    }

    public static final String CONTEXT_PATH = "/duoshim";

    /** Builder for a fake request. */
    public static final class Req {
        private final Map<String, String> params = new LinkedHashMap<>();
        private final Map<String, String> headers = new HashMap<>();
        private final List<Cookie> cookies = new ArrayList<>();
        private String ip = "192.0.2.10";

        public Req param(String name, String value) {
            params.put(name, value);
            return this;
        }

        public Req header(String name, String value) {
            headers.put(name.toLowerCase(Locale.ROOT), value);
            return this;
        }

        public Req basic(String id, String secret) {
            return header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        }

        public Req bearer(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Req cookie(String name, String value) {
            cookies.add(new Cookie(name, value));
            return this;
        }

        public Req ip(String ip) {
            this.ip = ip;
            return this;
        }

        public HttpServletRequest build() {
            return (HttpServletRequest) Proxy.newProxyInstance(FakeHttp.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                        case "getParameter" -> params.get((String) args[0]);
                        case "getHeader" -> headers.get(((String) args[0]).toLowerCase(Locale.ROOT));
                        case "getCookies" -> cookies.isEmpty() ? null : cookies.toArray(new Cookie[0]);
                        case "getRemoteAddr" -> ip;
                        case "getContextPath" -> CONTEXT_PATH;
                        case "getCharacterEncoding" -> "UTF-8";
                        case "isSecure" -> true;
                        case "toString" -> "FakeRequest" + params;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(m.getReturnType());
                    });
        }
    }

    /** Captures what a servlet wrote. */
    public static final class Resp {
        public int status = 200;
        public String redirect;
        public String contentType;
        public final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body);
        public final HttpServletResponse response;

        public Resp() {
            response = (HttpServletResponse) Proxy.newProxyInstance(FakeHttp.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, m, args) -> {
                        switch (m.getName()) {
                            case "setStatus" -> status = (Integer) args[0];
                            case "sendRedirect" -> {
                                status = 302;
                                redirect = (String) args[0];
                            }
                            case "setContentType" -> contentType = (String) args[0];
                            case "setHeader" -> headers.put(((String) args[0]).toLowerCase(Locale.ROOT),
                                    new ArrayList<>(List.of((String) args[1])));
                            case "addHeader" -> headers.computeIfAbsent(((String) args[0]).toLowerCase(Locale.ROOT),
                                    k -> new ArrayList<>()).add((String) args[1]);
                            case "setDateHeader" -> headers.put(((String) args[0]).toLowerCase(Locale.ROOT),
                                    new ArrayList<>(List.of(String.valueOf(args[1]))));
                            case "getWriter" -> {
                                return writer;
                            }
                            case "toString" -> {
                                return "FakeResponse";
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            default -> {
                                return defaultValue(m.getReturnType());
                            }
                        }
                        return null;
                    });
        }

        public String body() {
            writer.flush();
            return body.toString();
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public List<String> headerValues(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
