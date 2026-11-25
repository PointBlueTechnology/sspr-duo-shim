package duoshim.model;

/**
 * A Duo prompt in progress, bound to one browser by the transaction cookie.
 *
 * @param username    the user SSPR asked to verify; the only name that can come back to SSPR
 * @param ssprState   SSPR's opaque state, echoed back unchanged
 * @param redirectUri the validated SSPR redirect_uri
 * @param duoState    state sent to Duo; the callback must present it again
 * @param duoNonce    nonce sent to Duo; Duo's ID token must carry it
 */
public record PendingTransaction(String username, String ssprState, String redirectUri,
                                 String duoState, String duoNonce) {

    @Override
    public String toString() {
        return "PendingTransaction{username=" + username + ", redirectUri=" + redirectUri + "}";
    }
}
