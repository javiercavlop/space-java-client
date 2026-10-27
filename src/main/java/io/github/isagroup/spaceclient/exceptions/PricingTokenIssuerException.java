package io.github.isagroup.spaceclient.exceptions;

/** The token was issued by another server ({@code iss}). */
public class PricingTokenIssuerException extends PricingTokenVerificationException {
    private final String expected;
    private final String actual;

    public PricingTokenIssuerException(String expected, String actual) {
        super(WRONG_ISSUER, "The pricing token was not issued by " + expected);
        this.expected = expected;
        this.actual = actual;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }
}
