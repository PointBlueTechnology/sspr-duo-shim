package duoshim.testsupport;

import duoshim.ShimContext;
import duoshim.config.ConfigException;
import duoshim.config.ShimConfig;
import duoshim.web.Audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/** A ShimContext wired with test config, a fake Duo, a movable clock and captured audit lines. */
public final class TestShim {

    public static final String SSPR_CLIENT_ID = "sspr-client";
    public static final String SSPR_SECRET = "s3cret-value-for-tests-only";
    public static final String SSPR_REDIRECT = "https://sspr.example.test/sspr/public/oauth";
    public static final String SSPR_RETURN = "https://sspr.example.test/sspr/public/forgottenpassword";

    public final MutableClock clock = new MutableClock();
    public final FakeDuo duo = new FakeDuo();
    public final List<String> audit = new CopyOnWriteArrayList<>();
    public final ShimContext ctx;

    public TestShim() {
        this(baseProperties());
    }

    public TestShim(Properties props) {
        try {
            ctx = new ShimContext(ShimConfig.fromProperties(props), duo, new Audit(audit::add), clock);
        } catch (ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Properties baseProperties() {
        Properties p = new Properties();
        p.setProperty("duo.clientId", "DIABCDEFGHIJKLMNOPQR");
        p.setProperty("duo.clientSecret", "abcdefghijklmnopqrstuvwxyz01234567890ABC");
        p.setProperty("duo.apiHost", "api-test.duosecurity.com");
        p.setProperty("duo.redirectUri", "https://sspr.example.test/duoshim/duo/callback");
        p.setProperty("sspr.clientId", SSPR_CLIENT_ID);
        p.setProperty("sspr.clientSecret", SSPR_SECRET);
        p.setProperty("sspr.redirectUri", SSPR_REDIRECT);
        p.setProperty("ratelimit.windowSeconds", "300");
        p.setProperty("ratelimit.perUsername", "5");
        p.setProperty("ratelimit.sign.perIp", "100");
        p.setProperty("ratelimit.grant.perIp", "20");
        return p;
    }

    public boolean audited(String event, String result) {
        return audit.stream().anyMatch(l -> l.startsWith("event=" + event + " ") && l.contains(" result=" + result + " "));
    }

    /** A clock tests can move forward. */
    public static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
