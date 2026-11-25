package duoshim.duo;

import com.duosecurity.Client;
import com.duosecurity.exception.DuoException;
import com.duosecurity.model.AuthResult;
import com.duosecurity.model.Token;
import duoshim.config.ShimConfig;

/** Builds a new Duo Universal SDK {@link Client} for every request. */
public final class SdkDuoGatewayFactory implements DuoGatewayFactory {

    private final ShimConfig config;

    public SdkDuoGatewayFactory(ShimConfig config) {
        this.config = config;
    }

    @Override
    public DuoGateway create() throws DuoGatewayException {
        try {
            Client client = new Client.Builder(config.duoClientId(), config.duoClientSecret(),
                    config.duoApiHost(), config.duoRedirectUri())
                    .appendUserAgentInfo("sspr-duo-shim")
                    .build();
            return new SdkDuoGateway(client);
        } catch (DuoException | RuntimeException e) {
            throw new DuoGatewayException("cannot create Duo client: " + e.getMessage(), e);
        }
    }

    private static final class SdkDuoGateway implements DuoGateway {

        private final Client client;

        SdkDuoGateway(Client client) {
            this.client = client;
        }

        @Override
        public void healthCheck() throws DuoGatewayException {
            try {
                client.healthCheck();
            } catch (DuoException | RuntimeException e) {
                throw new DuoGatewayException("Duo health check failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String generateState() {
            return client.generateState();
        }

        @Override
        public String createAuthUrl(String username, String state, String nonce) throws DuoGatewayException {
            try {
                return client.createAuthUrl(username, state, nonce);
            } catch (DuoException | RuntimeException e) {
                throw new DuoGatewayException("cannot create Duo auth URL: " + e.getMessage(), e);
            }
        }

        @Override
        public DuoResult exchange(String duoCode, String username, String nonce) throws DuoGatewayException {
            try {
                // The SDK verifies the ID token signature, issuer, audience, nonce and that
                // preferred_username equals this username before returning.
                Token token = client.exchangeAuthorizationCodeFor2FAResult(duoCode, username, nonce);
                AuthResult result = token == null ? null : token.getAuth_result();
                return new DuoResult(result == null ? null : result.getStatus(),
                        token == null ? null : token.getPreferred_username());
            } catch (DuoException | RuntimeException e) {
                throw new DuoGatewayException("Duo code exchange failed: " + e.getMessage(), e);
            }
        }
    }
}
