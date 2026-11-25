package duoshim.web;

import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {

    @Test
    void setsNoStoreAndSecurityHeaders() {
        Resp r = new Resp();
        SecurityHeadersFilter.apply(r.response);
        assertTrue(r.header("Cache-Control").contains("no-store"));
        assertEquals("no-cache", r.header("Pragma"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals("DENY", r.header("X-Frame-Options"));
        assertEquals("no-referrer", r.header("Referrer-Policy"));
        assertTrue(r.header("Content-Security-Policy").contains("default-src 'none'"));
        assertTrue(r.header("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertNotNull(r.header("Strict-Transport-Security"));
    }

    /** L2: also when TLS ends at a proxy or tunnel and Tomcat itself sees plain HTTP. */
    @Test
    void hstsIsAlwaysSent() {
        Resp r = new Resp();
        SecurityHeadersFilter.apply(r.response);
        assertEquals("max-age=31536000", r.header("Strict-Transport-Security"));
    }
}
