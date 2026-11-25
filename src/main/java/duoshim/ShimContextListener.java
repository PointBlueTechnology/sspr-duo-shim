package duoshim;

import duoshim.config.ConfigException;
import duoshim.config.ShimConfig;
import duoshim.duo.DuoGatewayException;
import duoshim.duo.SdkDuoGatewayFactory;
import duoshim.web.Audit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the configuration and wires the shim at startup. Any configuration problem stops the
 * web application from starting, with the reason in the Tomcat log.
 */
public final class ShimContextListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger("duoshim");
    private static final long SWEEP_SECONDS = 30;

    private ScheduledExecutorService sweeper;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ShimConfig config;
        SdkDuoGatewayFactory duo;
        try {
            config = ShimConfig.load();
            duo = new SdkDuoGatewayFactory(config);
            duo.create(); // validates the Duo client id, secret, API host and redirect URI format (no network call)
        } catch (ConfigException | DuoGatewayException e) {
            LOG.log(Level.SEVERE, "duoshim startup aborted: " + e.getMessage());
            throw new IllegalStateException("duoshim startup aborted: " + e.getMessage());
        }
        if (!config.cookieSecure()) {
            LOG.warning("duoshim: cookie.secure=false; the transaction cookie will be sent over plain HTTP. Use only for testing.");
        }
        ShimContext ctx = new ShimContext(config, duo, new Audit(), Clock.systemUTC());
        event.getServletContext().setAttribute(ShimContext.ATTRIBUTE, ctx);

        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "duoshim-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                ctx.sweep();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "duoshim: sweep failed", e);
            }
        }, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);

        LOG.info("duoshim started: " + config);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        event.getServletContext().removeAttribute(ShimContext.ATTRIBUTE);
    }
}
