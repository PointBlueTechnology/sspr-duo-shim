package duoshim.web;

import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One audit line per outcome: event, username, result, source IP. Never pass secrets, codes,
 * tokens or handles here.
 */
public final class Audit {

    private static final Logger LOG = Logger.getLogger("duoshim.audit");

    private final Consumer<String> sink;

    public Audit() {
        this(LOG::info);
    }

    public Audit(Consumer<String> sink) {
        this.sink = sink;
    }

    public void record(String event, String username, String result, String sourceIp) {
        sink.accept("event=" + clean(event) + " user=\"" + clean(username) + "\" result=" + clean(result)
                + " ip=" + clean(sourceIp));
    }

    /** Neutralises control characters and quotes so a username cannot forge log lines. */
    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && i < 256; i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 || c == 0x7f || c == '"' || c == '\\' ? '_' : c);
        }
        return sb.toString();
    }
}
