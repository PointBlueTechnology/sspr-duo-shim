package duoshim.duo;

/** Creates a new {@link DuoGateway} for each request. */
@FunctionalInterface
public interface DuoGatewayFactory {

    DuoGateway create() throws DuoGatewayException;
}
