package duoshim.store;

import duoshim.testsupport.TestShim.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExpiringStoreTest {

    private final MutableClock clock = new MutableClock();
    private final ExpiringStore<String> store = new ExpiringStore<>("test", Duration.ofSeconds(60), 3, clock);

    @Test
    void valueCanBeConsumedExactlyOnce() throws Exception {
        String key = store.put("alice");
        assertEquals("alice", store.consume(key).orElseThrow());
        assertTrue(store.consume(key).isEmpty());
    }

    @Test
    void expiredValueIsRejected() throws Exception {
        String key = store.put("alice");
        clock.advance(Duration.ofSeconds(60));
        assertTrue(store.consume(key).isEmpty());
    }

    @Test
    void valueJustBeforeExpiryIsAccepted() throws Exception {
        String key = store.put("alice");
        clock.advance(Duration.ofSeconds(59));
        assertTrue(store.consume(key).isPresent());
    }

    @Test
    void unknownNullEmptyAndOversizedKeysAreRejected() throws Exception {
        store.put("alice");
        assertTrue(store.consume("nope").isEmpty());
        assertTrue(store.consume(null).isEmpty());
        assertTrue(store.consume("").isEmpty());
        assertTrue(store.consume("x".repeat(ExpiringStore.MAX_KEY_LENGTH + 1)).isEmpty());
    }

    @Test
    void keysAreRandomAndNotStoredInClear() throws Exception {
        String a = store.put("v");
        String b = store.put("v");
        assertNotEquals(a, b);
        assertEquals(43, a.length());
    }

    @Test
    void fullStoreSweepsThenRefuses() throws Exception {
        store.put("1");
        store.put("2");
        store.put("3");
        assertThrows(StoreFullException.class, () -> store.put("4"));
        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> store.put("5"), "expired entries are swept to make room");
        assertEquals(1, store.size());
    }

    @Test
    void sweepRemovesOnlyExpired() throws Exception {
        store.put("old");
        clock.advance(Duration.ofSeconds(30));
        String fresh = store.put("fresh");
        clock.advance(Duration.ofSeconds(31));
        assertEquals(1, store.sweep());
        assertEquals("fresh", store.consume(fresh).orElseThrow());
    }

    /** L1: the cap holds under a burst of parallel puts. */
    @Test
    void capacityHoldsUnderConcurrentPuts() throws Exception {
        ExpiringStore<String> capped = new ExpiringStore<>("capped", Duration.ofMinutes(1), 100, clock);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    capped.put("v");
                    return true;
                } catch (StoreFullException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int stored = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                stored++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(100, stored);
        assertEquals(100, capped.size());
    }

    @Test
    void peekDoesNotConsume() throws Exception {
        String key = store.put("alice");
        assertEquals("alice", store.peek(key).orElseThrow());
        assertEquals("alice", store.consume(key).orElseThrow());
        assertTrue(store.peek(key).isEmpty());
        String old = store.put("bob");
        clock.advance(Duration.ofSeconds(60));
        assertTrue(store.peek(old).isEmpty(), "expired");
    }

    @Test
    void consumeIfOutcomes() throws Exception {
        String key = store.put("alice");
        ExpiringStore.Result<String> refused = store.consumeIf(key, v -> false);
        assertEquals(ExpiringStore.Outcome.REJECTED, refused.outcome());
        assertNull(refused.value());
        assertEquals(1, store.size(), "refused entry stays");
        ExpiringStore.Result<String> taken = store.consumeIf(key, "alice"::equals);
        assertEquals(ExpiringStore.Outcome.CONSUMED, taken.outcome());
        assertEquals("alice", taken.value());
        assertEquals(ExpiringStore.Outcome.NOT_FOUND, store.consumeIf(key, v -> true).outcome());
        String expiring = store.put("bob");
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ExpiringStore.Outcome.NOT_FOUND, store.consumeIf(expiring, v -> true).outcome());
        assertEquals(0, store.size(), "expired entry removed");
        assertEquals(ExpiringStore.Outcome.NOT_FOUND, store.consumeIf(null, v -> true).outcome());
    }

    @Test
    void concurrentConsumersGetTheValueOnlyOnce() throws Exception {
        ExpiringStore<String> big = new ExpiringStore<>("big", Duration.ofMinutes(1), 10, clock);
        String key = big.put("once");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return big.consume(key).isPresent();
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                wins++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, wins);
    }
}
