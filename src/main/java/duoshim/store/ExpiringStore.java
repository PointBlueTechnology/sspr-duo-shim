package duoshim.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe, single-use, time-limited store. {@link #put} returns a fresh 256-bit random key;
 * {@link #consume} removes the entry atomically, so a key can be redeemed at most once.
 * Entries are keyed by the SHA-256 digest of the key, never by the key itself.
 */
public final class ExpiringStore<V> {

    /** Longest key accepted by {@link #consume}; anything longer cannot be one of ours. */
    static final int MAX_KEY_LENGTH = 256;

    private static final class Entry<V> {
        private final V value;
        private final Instant expires;

        Entry(V value, Instant expires) {
            this.value = value;
            this.expires = expires;
        }

        V value() {
            return value;
        }

        Instant expires() {
            return expires;
        }
    }

    private final String name;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Object putLock = new Object();

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

    /**
     * Stores the value and returns the key that redeems it. The capacity check and the insert
     * happen under one lock, so concurrent puts can't push the store past {@code maxEntries}.
     */
    public String put(V value) throws StoreFullException {
        Objects.requireNonNull(value);
        String key = Secrets.newToken();
        synchronized (putLock) {
            if (entries.size() >= maxEntries) {
                sweep();
                if (entries.size() >= maxEntries) {
                    throw new StoreFullException(name);
                }
            }
            entries.put(Secrets.digest(key), new Entry<>(value, clock.instant().plus(ttl)));
        }
        return key;
    }

    /** Returns the value without removing it, if the key exists and has not expired. */
    public Optional<V> peek(String key) {
        if (!plausibleKey(key)) {
            return Optional.empty();
        }
        Entry<V> entry = entries.get(Secrets.digest(key));
        if (entry == null || !clock.instant().isBefore(entry.expires())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** What {@link #consumeIf} did. */
    public enum Outcome {
        /** No such key, or it had expired (an expired entry is removed). */
        NOT_FOUND,
        /** The entry exists but the check refused it; it is left in place. */
        REJECTED,
        /** The check accepted the entry and it was removed. */
        CONSUMED
    }

    /** Result of {@link #consumeIf}: the outcome, and the value when consumed. */
    public static final class Result<V> {
        private final Outcome outcome;
        private final V value;

        Result(Outcome outcome, V value) {
            this.outcome = outcome;
            this.value = value;
        }

        public Outcome outcome() {
            return outcome;
        }

        /** The removed value; only set when {@link #outcome()} is {@link Outcome#CONSUMED}. */
        public V value() {
            return value;
        }
    }

    /**
     * Atomically removes and returns the value only if {@code accept} approves it. A refused entry
     * stays in the store, so a forged request can't destroy someone else's pending entry.
     * {@code accept} runs while the entry is locked: keep it fast and free of side effects.
     */
    public Result<V> consumeIf(String key, Predicate<? super V> accept) {
        if (!plausibleKey(key)) {
            return new Result<>(Outcome.NOT_FOUND, null);
        }
        Instant now = clock.instant();
        Object[] out = {Outcome.NOT_FOUND, null};
        entries.computeIfPresent(Secrets.digest(key), (k, e) -> {
            if (!now.isBefore(e.expires())) {
                return null;
            }
            if (!accept.test(e.value())) {
                out[0] = Outcome.REJECTED;
                return e;
            }
            out[0] = Outcome.CONSUMED;
            out[1] = e.value();
            return null;
        });
        @SuppressWarnings("unchecked")
        V value = (V) out[1];
        return new Result<>((Outcome) out[0], value);
    }

    private static boolean plausibleKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_LENGTH;
    }

    /** Removes and returns the value if the key exists and has not expired. */
    public Optional<V> consume(String key) {
        if (!plausibleKey(key)) {
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
