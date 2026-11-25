package duoshim.web;

import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {

    @Test
    void setsNoStoreAndSecurityHeaders() {
        Resp r = new Resp();
        SecurityHeadersFilter.apply(r.response, true);
        assertTrue(r.header("Cache-Control").contains("no-store"));
        assertEquals("no-cache", r.header("Pragma"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals("DENY", r.header("X-Frame-Options"));
        assertEquals("no-referrer", r.header("Referrer-Policy"));
        assertTrue(r.header("Content-Security-Policy").contains("default-src 'none'"));
        assertTrue(r.header("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertNotNull(r.header("Strict-Transport-Security"));
    }

    @Test
    void noHstsOverPlainHttp() {
        Resp r = new Resp();
        SecurityHeadersFilter.apply(r.response, false);
        assertNull(r.header("Strict-Transport-Security"));
    }
}
