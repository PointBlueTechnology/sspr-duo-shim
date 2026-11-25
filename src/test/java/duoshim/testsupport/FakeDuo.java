package duoshim.testsupport;

import duoshim.duo.DuoGateway;
import duoshim.duo.DuoGatewayException;
import duoshim.duo.DuoGatewayFactory;
import duoshim.duo.DuoResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake Duo. Models what the real service does: a Duo code is bound to the user who completed the
 * prompt, and exchanging it reports that user, not the one the caller expects.
 */
public final class FakeDuo implements DuoGatewayFactory {

    public boolean failCreate;
    public boolean failHealthCheck;
    public boolean failExchange;
    /** Status Duo reports on exchange. */
    public String status = "allow";

    public final List<String> authUrlUsernames = new ArrayList<>();
    public final AtomicInteger clientsCreated = new AtomicInteger();

    private final AtomicInteger counter = new AtomicInteger();
    /** duo_code -> user who actually completed the Duo prompt. */
    private final Map<String, String> codeOwners = new ConcurrentHashMap<>();
    /** duo state -> nonce passed with it. */
    private final Map<String, String> nonces = new ConcurrentHashMap<>();

    /** Simulates a user completing the Duo prompt; returns the duo_code Duo would send back. */
    public String completePrompt(String actualUser) {
        String code = "duo-code-" + counter.incrementAndGet();
        codeOwners.put(code, actualUser);
        return code;
    }

    public String nonceFor(String duoState) {
        return nonces.get(duoState);
    }

    @Override
    public DuoGateway create() throws DuoGatewayException {
        if (failCreate) {
            throw new DuoGatewayException("fake: cannot create client");
        }
        clientsCreated.incrementAndGet();
        return new DuoGateway() {
            @Override
            public void healthCheck() throws DuoGatewayException {
                if (failHealthCheck) {
                    throw new DuoGatewayException("fake: Duo is down");
                }
            }

            @Override
            public String generateState() {
                return "duo-state-0000000000000000-" + counter.incrementAndGet();
            }

            @Override
            public String createAuthUrl(String username, String state, String nonce) {
                authUrlUsernames.add(username);
                nonces.put(state, nonce);
                return "https://api-test.duosecurity.com/oauth/v1/authorize?request=fake-" + state;
            }

            @Override
            public DuoResult exchange(String duoCode, String username, String nonce) throws DuoGatewayException {
                if (failExchange) {
                    throw new DuoGatewayException("fake: exchange failed");
                }
                String owner = codeOwners.remove(duoCode);
                if (owner == null) {
                    throw new DuoGatewayException("fake: unknown duo code");
                }
                return new DuoResult(status, owner);
            }
        };
    }
}
