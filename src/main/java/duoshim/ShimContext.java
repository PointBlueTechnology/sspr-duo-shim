package duoshim;

import duoshim.config.ShimConfig;
import duoshim.duo.DuoGatewayFactory;
import duoshim.model.IssuedCode;
import duoshim.model.IssuedToken;
import duoshim.model.PendingTransaction;
import duoshim.model.SignedUser;
import duoshim.store.ExpiringStore;
import duoshim.store.RateLimiter;
import duoshim.web.Audit;
import duoshim.web.ClientAuthenticator;

import javax.servlet.ServletContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Everything the servlets share for one deployment: config, stores, limiters, Duo factory. */
public final class ShimContext {

    public static final String ATTRIBUTE = ShimContext.class.getName();

    /** Handle returned by /oauth/sign. */
    public static final Duration HANDLE_TTL = Duration.ofMinutes(2);
    /** Authorization code returned to SSPR after Duo ALLOW. */
    public static final Duration CODE_TTL = Duration.ofSeconds(60);
    /** Access token returned by /oauth/token. */
    public static final Duration TOKEN_TTL = Duration.ofSeconds(60);

    private final ShimConfig config;
    private final Clock clock;
    private final DuoGatewayFactory duo;
    private final Audit audit;
    private final ClientAuthenticator clientAuthenticator;
    private final ExpiringStore<SignedUser> handles;
    private final ExpiringStore<PendingTransaction> transactions;
    private final ExpiringStore<IssuedCode> codes;
    private final ExpiringStore<IssuedToken> tokens;
    private final RateLimiter signPerIp;
    private final RateLimiter signPerUser;
    private final RateLimiter grantPerIp;
    private final RateLimiter grantPerUser;

    public ShimContext(ShimConfig config, DuoGatewayFactory duo, Audit audit, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.duo = duo;
        this.audit = audit;
        this.clientAuthenticator = new ClientAuthenticator(config.ssprClientId(), config.ssprClientSecret());
        int max = config.storeMaxEntries();
        this.handles = new ExpiringStore<>("handles", HANDLE_TTL, max, clock);
        this.transactions = new ExpiringStore<>("transactions", config.transactionTtl(), max, clock);
        this.codes = new ExpiringStore<>("codes", CODE_TTL, max, clock);
        this.tokens = new ExpiringStore<>("tokens", TOKEN_TTL, max, clock);
        Duration window = config.rateLimitWindow();
        this.signPerIp = new RateLimiter(config.rateLimitSignPerIp(), window, clock);
        this.signPerUser = new RateLimiter(config.rateLimitPerUsername(), window, clock);
        this.grantPerIp = new RateLimiter(config.rateLimitGrantPerIp(), window, clock);
        this.grantPerUser = new RateLimiter(config.rateLimitPerUsername(), window, clock);
    }

    public static ShimContext from(ServletContext servletContext) {
        Object o = servletContext.getAttribute(ATTRIBUTE);
        if (!(o instanceof ShimContext)) {
            throw new IllegalStateException("duoshim is not initialised; see the startup log");
        }
        return (ShimContext) o;
    }

    /** Removes expired entries from every store and limiter. */
    public void sweep() {
        for (ExpiringStore<?> s : List.of(handles, transactions, codes, tokens)) {
            s.sweep();
        }
        for (RateLimiter r : List.of(signPerIp, signPerUser, grantPerIp, grantPerUser)) {
            r.sweep();
        }
    }

    public ShimConfig config() { return config; }
    public Clock clock() { return clock; }
    public DuoGatewayFactory duo() { return duo; }
    public Audit audit() { return audit; }
    public ClientAuthenticator clientAuthenticator() { return clientAuthenticator; }
    public ExpiringStore<SignedUser> handles() { return handles; }
    public ExpiringStore<PendingTransaction> transactions() { return transactions; }
    public ExpiringStore<IssuedCode> codes() { return codes; }
    public ExpiringStore<IssuedToken> tokens() { return tokens; }
    public RateLimiter signPerIp() { return signPerIp; }
    public RateLimiter signPerUser() { return signPerUser; }
    public RateLimiter grantPerIp() { return grantPerIp; }
    public RateLimiter grantPerUser() { return grantPerUser; }
}
