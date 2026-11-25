package duoshim.servlet;

import duoshim.ShimContext;
import duoshim.duo.DuoGatewayException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static duoshim.web.Responses.json;

/**
 * GET /health: for load-balancer health checks.
 *
 * <ul>
 *   <li>{@code /health}: 200 {"status":"ok"} whenever the shim started. If its configuration is
 *       invalid the web application never starts, and Tomcat answers 404.</li>
 *   <li>{@code /health?duo=1}: also checks that Duo is reachable with the configured credentials.
 *       200 when it is, 503 when it isn't. The Duo result is cached for {@link #DUO_CACHE} so
 *       frequent probes can't turn into a flood of calls to Duo.</li>
 * </ul>
 * Reveals nothing beyond up/down. Not audited (load balancers poll it constantly).
 */
public final class HealthServlet extends ShimServlet {

    private static final long serialVersionUID = 1L;

    /** How long a Duo health result is reused. */
    static final Duration DUO_CACHE = Duration.ofSeconds(15);

    private static final class DuoStatus {
        final Instant checkedAt;
        final boolean up;

        DuoStatus(Instant checkedAt, boolean up) {
            this.checkedAt = checkedAt;
            this.up = up;
        }
    }

    private final transient AtomicReference<DuoStatus> lastDuo = new AtomicReference<>();

    public HealthServlet() {
    }

    HealthServlet(ShimContext ctx) {
        super(ctx);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        if (!"1".equals(req.getParameter("duo"))) {
            body.put("status", "ok");
            json(resp, HttpServletResponse.SC_OK, body);
            return;
        }
        boolean duoUp = duoUp();
        body.put("status", duoUp ? "ok" : "degraded");
        body.put("duo", duoUp ? "ok" : "unavailable");
        json(resp, duoUp ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE, body);
    }

    private boolean duoUp() {
        ShimContext ctx = ctx();
        Instant now = ctx.clock().instant();
        DuoStatus cached = lastDuo.get();
        if (cached != null && now.isBefore(cached.checkedAt.plus(DUO_CACHE))) {
            return cached.up;
        }
        boolean up;
        try {
            ctx.duo().create().healthCheck();
            up = true;
        } catch (DuoGatewayException | RuntimeException e) {
            LOG.warning("duoshim: health probe: Duo unavailable: " + e.getMessage());
            up = false;
        }
        lastDuo.set(new DuoStatus(now, up));
        return up;
    }
}
