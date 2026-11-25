package duoshim.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, single-use, time-limited store. {@link #put} returns a fresh 256-bit random key;
 * {@link #consume} removes the entry atomically, so a key can be redeemed at most once.
 * Entries are keyed by the SHA-256 digest of the key, never by the key itself.
 */
public final class ExpiringStore<V> {

    /** Longest key accepted by {@link #consume}; anything longer cannot be one of ours. */
    static final int MAX_KEY_LENGTH = 256;

    private record Entry<V>(V value, Instant expires) {
    }

    private final String name;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();

    public ExpiringStore(String name, Duration ttl, int maxEntries, Clock clock) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.name = Objects.requireNonNull(name);
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock);
    }

    /** Stores the value and returns the key that redeems it. */
    public String put(V value) throws StoreFullException {
        Objects.requireNonNull(value);
        if (entries.size() >= maxEntries) {
            sweep();
            if (entries.size() >= maxEntries) {
                throw new StoreFullException(name);
            }
        }
        String key = Secrets.newToken();
        entries.put(Secrets.digest(key), new Entry<>(value, clock.instant().plus(ttl)));
        return key;
    }

    /** Removes and returns the value if the key exists and has not expired. */
    public Optional<V> consume(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return Optional.empty();
        }
        Entry<V> entry = entries.remove(Secrets.digest(key));
        if (entry == null || !clock.instant().isBefore(entry.expires())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** Drops expired entries; returns how many were removed. */
    public int sweep() {
        Instant now = clock.instant();
        int before = entries.size();
        entries.values().removeIf(e -> !now.isBefore(e.expires()));
        return Math.max(0, before - entries.size());
    }

    public int size() {
        return entries.size();
    }

    public Duration ttl() {
        return ttl;
    }

    public String name() {
        return name;
    }
}
