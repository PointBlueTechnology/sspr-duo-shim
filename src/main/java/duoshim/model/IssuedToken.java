package duoshim.model;

import java.util.Objects;

/** An access token redeemable once at /oauth/userinfo for this Duo-verified username. */
public final class IssuedToken {

    private final String username;

    public IssuedToken(String username) {
        this.username = Objects.requireNonNull(username);
    }

    public String username() {
        return username;
    }

    @Override
    public String toString() {
        return "IssuedToken{username=" + username + "}";
    }
}
