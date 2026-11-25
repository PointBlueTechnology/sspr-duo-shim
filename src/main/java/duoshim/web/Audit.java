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

    /**
     * Neutralises control characters, Unicode line separators (U+0085, U+2028, U+2029), quotes
     * and backslashes, so a username cannot forge or split log lines.
     */
    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && i < 256; i++) {
            char c = value.charAt(i);
            boolean lineBreakOrControl = c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029;
            sb.append(lineBreakOrControl || c == '"' || c == '\\' ? '_' : c);
        }
        return sb.toString();
    }
}
