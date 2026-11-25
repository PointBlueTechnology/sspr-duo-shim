package duoshim.duo;

/** The parts of Duo's 2FA result the shim acts on. */
public final class DuoResult {

    private final String status;
    private final String verifiedUsername;

    /**
     * @param status           auth_result.status from Duo, "allow" on success
     * @param verifiedUsername preferred_username from Duo's signed ID token
     */
    public DuoResult(String status, String verifiedUsername) {
        this.status = status;
        this.verifiedUsername = verifiedUsername;
    }

    public String status() {
        return status;
    }

    public String verifiedUsername() {
        return verifiedUsername;
    }

    public boolean allowed() {
        return "allow".equalsIgnoreCase(status);
    }

    @Override
    public String toString() {
        return "DuoResult{status=" + status + ", verifiedUsername=" + verifiedUsername + "}";
    }
}
