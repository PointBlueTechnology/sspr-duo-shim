package duoshim.duo;

/**
 * One Duo Universal Prompt client, used for a single request. Implementations are created per
 * request by a {@link DuoGatewayFactory}; nothing here is shared between users.
 */
public interface DuoGateway {

    /** Fails if Duo is unreachable or unhealthy. The shim fails closed on any exception. */
    void healthCheck() throws DuoGatewayException;

    /** A fresh Duo state value for one authorization. */
    String generateState();

    /** The Universal Prompt URL for this user, state and nonce. */
    String createAuthUrl(String username, String state, String nonce) throws DuoGatewayException;

    /**
     * Exchanges Duo's authorization code for the 2FA result. Implementations must validate the
     * Duo token for this username and nonce; the caller checks the result again.
     */
    DuoResult exchange(String duoCode, String username, String nonce) throws DuoGatewayException;
}
