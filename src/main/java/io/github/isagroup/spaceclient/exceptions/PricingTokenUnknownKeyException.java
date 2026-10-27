package io.github.isagroup.spaceclient.exceptions;

/**
 * The token names a {@code kid} SPACE does not publish, even after fetching the keys again: a key
 * that was removed (rotation, leaked key) or a token SPACE did not sign.
 */
public class PricingTokenUnknownKeyException extends PricingTokenVerificationException {
    private final String kid;

    public PricingTokenUnknownKeyException(String kid) {
        super(UNKNOWN_KID, "SPACE does not publish the key \"" + kid + "\" of this pricing token");
        this.kid = kid;
    }

    public String getKid() {
        return kid;
    }
}
