package duoshim.duo;

/** Any failure talking to Duo or validating its answer. */
public final class DuoGatewayException extends Exception {

    private static final long serialVersionUID = 1L;

    public DuoGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public DuoGatewayException(String message) {
        super(message);
    }
}
