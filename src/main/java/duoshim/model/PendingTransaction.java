package duoshim.model;

import java.util.Objects;

/** A Duo prompt in progress, bound to one browser by the transaction cookie. */
public final class PendingTransaction {

    private final String username;
    private final String ssprState;
    private final String redirectUri;
    private final String duoState;
    private final String duoNonce;

    /**
     * @param username    the user SSPR asked to verify; the only name that can come back to SSPR
     * @param ssprState   SSPR's opaque state, echoed back unchanged
     * @param redirectUri the validated SSPR redirect_uri
     * @param duoState    state sent to Duo; the callback must present it again
     * @param duoNonce    nonce sent to Duo; Duo's ID token must carry it
     */
    public PendingTransaction(String username, String ssprState, String redirectUri, String duoState, String duoNonce) {
        this.username = Objects.requireNonNull(username);
        this.ssprState = Objects.requireNonNull(ssprState);
        this.redirectUri = Objects.requireNonNull(redirectUri);
        this.duoState = Objects.requireNonNull(duoState);
        this.duoNonce = Objects.requireNonNull(duoNonce);
    }

    public String username() {
        return username;
    }

    public String ssprState() {
        return ssprState;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String duoState() {
        return duoState;
    }

    public String duoNonce() {
        return duoNonce;
    }

    @Override
    public String toString() {
        return "PendingTransaction{username=" + username + ", redirectUri=" + redirectUri + "}";
    }
}
