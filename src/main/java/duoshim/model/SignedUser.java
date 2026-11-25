package duoshim.model;

import java.util.Objects;

/** A username SSPR registered through /oauth/sign, redeemable once at /oauth/grant. */
public final class SignedUser {

    private final String username;

    public SignedUser(String username) {
        this.username = Objects.requireNonNull(username);
    }

    public String username() {
        return username;
    }

    @Override
    public String toString() {
        return "SignedUser{username=" + username + "}";
    }
}
