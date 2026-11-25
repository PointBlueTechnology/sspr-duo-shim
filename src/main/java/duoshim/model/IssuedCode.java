package duoshim.model;

/** An authorization code minted after Duo returned ALLOW for this username. */
public record IssuedCode(String username, String redirectUri) {
}
