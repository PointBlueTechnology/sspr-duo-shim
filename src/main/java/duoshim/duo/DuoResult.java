package duoshim.duo;

/**
 * The parts of Duo's 2FA result the shim acts on.
 *
 * @param status           auth_result.status from Duo, "allow" on success
 * @param verifiedUsername preferred_username from Duo's signed ID token
 */
public record DuoResult(String status, String verifiedUsername) {

    public boolean allowed() {
        return "allow".equalsIgnoreCase(status);
    }
}
