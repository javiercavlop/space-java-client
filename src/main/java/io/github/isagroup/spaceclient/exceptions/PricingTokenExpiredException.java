package io.github.isagroup.spaceclient.exceptions;

import java.time.Instant;

/** The token expired ({@code exp}, after the configured leeway). */
public class PricingTokenExpiredException extends PricingTokenVerificationException {
    private final Instant expiredAt;

    public PricingTokenExpiredException(Instant expiredAt) {
        super(EXPIRED, "The pricing token has expired");
        this.expiredAt = expiredAt;
    }

    /** When the token expired. */
    public Instant getExpiredAt() {
        return expiredAt;
    }
}
