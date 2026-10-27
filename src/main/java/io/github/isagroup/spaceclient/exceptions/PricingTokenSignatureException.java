package io.github.isagroup.spaceclient.exceptions;

/** The signature does not match the published key the token names (forged or tampered token). */
public class PricingTokenSignatureException extends PricingTokenVerificationException {
    public PricingTokenSignatureException() {
        super(SIGNATURE_INVALID, "The pricing token signature is invalid");
    }
}
