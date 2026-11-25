package duoshim.store;

import duoshim.testsupport.TestShim.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter r = new RateLimiter(3, Duration.ofMinutes(5), clock);
        assertTrue(r.tryAcquire("k"));
        assertTrue(r.tryAcquire("k"));
        assertTrue(r.tryAcquire("k"));
        assertFalse(r.tryAcquire("k"));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter r = new RateLimiter(1, Duration.ofMinutes(5), clock);
        assertTrue(r.tryAcquire("a"));
        assertFalse(r.tryAcquire("a"));
        assertTrue(r.tryAcquire("b"));
    }

    @Test
    void windowResets() {
        RateLimiter r = new RateLimiter(1, Duration.ofMinutes(5), clock);
        assertTrue(r.tryAcquire("k"));
        assertFalse(r.tryAcquire("k"));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(r.tryAcquire("k"));
    }

    @Test
    void isLimitedDoesNotCount() {
        RateLimiter r = new RateLimiter(2, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 10; i++) {
            assertFalse(r.isLimited("k"));
        }
        assertTrue(r.tryAcquire("k"));
        assertTrue(r.tryAcquire("k"));
        assertTrue(r.isLimited("k"));
        clock.advance(Duration.ofMinutes(5));
        assertFalse(r.isLimited("k"));
    }

    @Test
    void zeroDisables() {
        RateLimiter r = new RateLimiter(0, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 100; i++) {
            assertTrue(r.tryAcquire("k"));
        }
    }

    @Test
    void sweepDropsFinishedWindows() {
        RateLimiter r = new RateLimiter(2, Duration.ofMinutes(5), clock);
        r.tryAcquire("a");
        r.tryAcquire("b");
        clock.advance(Duration.ofMinutes(6));
        r.sweep();
        assertEquals(0, r.trackedKeys());
    }
}
