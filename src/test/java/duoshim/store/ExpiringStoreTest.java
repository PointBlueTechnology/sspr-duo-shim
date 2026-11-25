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
