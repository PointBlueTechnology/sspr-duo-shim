package duoshim.model;

import java.util.Objects;

/** An authorization code minted after Duo returned ALLOW for this username. */
public final class IssuedCode {

    private final String username;
    private final String redirectUri;

    public IssuedCode(String username, String redirectUri) {
        this.username = Objects.requireNonNull(username);
        this.redirectUri = Objects.requireNonNull(redirectUri);
    }

    public String username() {
        return username;
    }

    public String redirectUri() {
        return redirectUri;
    }

    @Override
    public String toString() {
        return "IssuedCode{username=" + username + ", redirectUri=" + redirectUri + "}";
    }
}
