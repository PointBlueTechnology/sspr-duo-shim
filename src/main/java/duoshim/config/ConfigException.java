package duoshim.config;

/** The shim's configuration is missing or invalid; startup must stop. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
