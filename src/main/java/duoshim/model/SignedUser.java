package duoshim.model;

/** A username SSPR registered through /oauth/sign, redeemable once at /oauth/grant. */
public record SignedUser(String username) {
}
