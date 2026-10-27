package io.github.isagroup.spaceclient.exceptions;

/**
 * A pricing token cannot be trusted. Thrown by
 * {@link io.github.isagroup.spaceclient.tokens.PricingTokenVerifier#verify(String)} and
 * {@code features.verifyPricingToken}.
 *
 * The subclasses name the usual reasons. This class itself is used, with {@link #getCode()}:
 * <ul>
 *   <li>{@value #INVALID}: a malformed token, an algorithm other than ES256 ({@code HS256},
 *   {@code none}...), a {@code jwk}/{@code jku}/{@code x5u}/{@code x5c} header, or a missing or
 *   invalid claim;</li>
 *   <li>{@value #JWKS_UNAVAILABLE}: the JWKS could not be fetched (the cause says why).</li>
 * </ul>
 */
public class PricingTokenVerificationException extends SpaceException {
    public static final String INVALID = "PRICING_TOKEN_INVALID";
    public static final String SIGNATURE_INVALID = "PRICING_TOKEN_SIGNATURE_INVALID";
    public static final String EXPIRED = "PRICING_TOKEN_EXPIRED";
    public static final String WRONG_AUDIENCE = "PRICING_TOKEN_WRONG_AUDIENCE";
    public static final String WRONG_ISSUER = "PRICING_TOKEN_WRONG_ISSUER";
    public static final String UNKNOWN_KID = "PRICING_TOKEN_UNKNOWN_KID";
    public static final String JWKS_UNAVAILABLE = "PRICING_TOKEN_JWKS_UNAVAILABLE";

    private final String code;

    public PricingTokenVerificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PricingTokenVerificationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Why the token was rejected (one of the constants of this class). */
    public String getCode() {
        return code;
    }
}
