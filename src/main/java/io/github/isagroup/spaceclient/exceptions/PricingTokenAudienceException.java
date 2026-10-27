package io.github.isagroup.spaceclient.exceptions;

/** The token was issued for another organization ({@code aud} or {@code organizationId}). */
public class PricingTokenAudienceException extends PricingTokenVerificationException {
    private final String expected;
    private final transient Object actual;

    public PricingTokenAudienceException(String expected, Object actual) {
        super(WRONG_AUDIENCE, "The pricing token was not issued for organization " + expected);
        this.expected = expected;
        this.actual = actual;
    }

    /** The organization the token had to be issued for. */
    public String getExpected() {
        return expected;
    }

    /** The {@code aud} or {@code organizationId} the token carries (may be {@code null}). */
    public Object getActual() {
        return actual;
    }
}
