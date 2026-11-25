package duoshim.servlet;

import duoshim.testsupport.FakeHttp.Req;
import duoshim.testsupport.FakeHttp.Resp;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HealthServletTest {

    private final Flow flow = new Flow();
    private final HealthServlet health = new HealthServlet(flow.shim.ctx);

    private Resp get(Req req) throws Exception {
        Resp r = new Resp();
        health.doGet(req.build(), r.response);
        return r;
    }

    @Test
    void plainProbeIsOkWithoutCallingDuo() throws Exception {
        flow.shim.duo.failHealthCheck = true;
        Resp r = get(new Req());
        assertEquals(200, r.status);
        assertEquals("ok", Flow.JSON.readTree(r.body()).get("status").asText());
        assertEquals(0, flow.shim.duo.clientsCreated.get(), "plain probe never contacts Duo");
        assertTrue(flow.shim.audit.isEmpty(), "health probes are not audited");
    }

    @Test
    void duoProbeReportsDuoUp() throws Exception {
        Resp r = get(new Req().param("duo", "1"));
        assertEquals(200, r.status);
        assertEquals("ok", Flow.JSON.readTree(r.body()).get("duo").asText());
    }

    @Test
    void duoProbeReports503WhenDuoIsDown() throws Exception {
        flow.shim.duo.failHealthCheck = true;
        Resp r = get(new Req().param("duo", "1"));
        assertEquals(503, r.status);
        assertEquals("degraded", Flow.JSON.readTree(r.body()).get("status").asText());
        assertEquals("unavailable", Flow.JSON.readTree(r.body()).get("duo").asText());
    }

    @Test
    void duoResultIsCachedSoProbesCannotFloodDuo() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, get(new Req().param("duo", "1")).status);
        }
        assertEquals(1, flow.shim.duo.clientsCreated.get());
        flow.shim.duo.failHealthCheck = true;
        assertEquals(200, get(new Req().param("duo", "1")).status, "still cached");
        flow.shim.clock.advance(HealthServlet.DUO_CACHE);
        assertEquals(503, get(new Req().param("duo", "1")).status, "re-checked after the cache expires");
        assertEquals(2, flow.shim.duo.clientsCreated.get());
    }

    @Test
    void failedClientCreationCountsAsDown() throws Exception {
        flow.shim.duo.failCreate = true;
        assertEquals(503, get(new Req().param("duo", "1")).status);
        flow.shim.clock.advance(Duration.ofSeconds(1));
        assertEquals(503, get(new Req().param("duo", "1")).status);
    }
}
