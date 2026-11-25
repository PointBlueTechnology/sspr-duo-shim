package duoshim.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditAndResponsesTest {

    @Test
    void auditNeutralisesInjectedLineBreaksAndQuotes() {
        List<String> lines = new ArrayList<>();
        new Audit(lines::add).record("grant", "bob\nevent=forged \"x\"", "ok", "10.0.0.1");
        assertEquals(1, lines.size());
        assertEquals("event=grant user=\"bob_event=forged _x_\" result=ok ip=10.0.0.1", lines.get(0));
    }

    @Test
    void auditNeutralisesUnicodeLineSeparators() {
        List<String> lines = new ArrayList<>();
        new Audit(lines::add).record("grant", "bob\u2028event=forged\u2029x\u0085y", "ok", "10.0.0.1");
        assertEquals("event=grant user=\"bob_event=forged_x_y\" result=ok ip=10.0.0.1", lines.get(0));
    }

    @Test
    void auditPrintsDashForMissingValues() {
        List<String> lines = new ArrayList<>();
        new Audit(lines::add).record("sign", null, "invalid_client", null);
        assertEquals("event=sign user=\"-\" result=invalid_client ip=-", lines.get(0));
    }

    @Test
    void htmlEscaping() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", Responses.html("<a href=\"x\">&'"));
    }
}
