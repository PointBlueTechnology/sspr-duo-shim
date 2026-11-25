package duoshim.store;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter: at most {@code limit} attempts per key per window.
 * A limit of 0 or less disables the limiter.
 */
public final class RateLimiter {

    private static final class Window {
        private final long startMillis;
        private final int count;

        Window(long startMillis, int count) {
            this.startMillis = startMillis;
            this.count = count;
        }

        long startMillis() {
            return startMillis;
        }

        int count() {
            return count;
        }
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, Duration window, Clock clock) {
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = Objects.requireNonNull(clock);
    }

    /** Records an attempt for the key; returns false if the key is over its limit. */
    public boolean tryAcquire(String key) {
        if (limit <= 0) {
            return true;
        }
        Objects.requireNonNull(key);
        long now = clock.millis();
        boolean[] allowed = {false};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMillis() >= windowMillis) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (w.count() < limit) {
                allowed[0] = true;
                return new Window(w.startMillis(), w.count() + 1);
            }
            return w;
        });
        return allowed[0];
    }

    /** Drops windows that have ended. */
    public void sweep() {
        long now = clock.millis();
        windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
    }

    int trackedKeys() {
        return windows.size();
    }
}
