package duoshim.model;

/** An access token redeemable once at /oauth/userinfo for this Duo-verified username. */
public record IssuedToken(String username) {
}
