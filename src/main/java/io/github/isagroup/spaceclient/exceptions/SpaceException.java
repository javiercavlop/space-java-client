package io.github.isagroup.spaceclient.exceptions;

/**
 * Anything that went wrong talking to SPACE in the calls of the subscription-change API.
 *
 * Unchecked. Catch this to handle every failure of those calls at once, or one of its subclasses:
 * {@link SpaceApiException} (SPACE refused), {@link SpaceConnectionException} (SPACE was not
 * reached) and {@link SpaceFeatureUnsupportedException} (the connected SPACE lacks a feature the
 * call depends on). Thrown as-is when SPACE answered with something the client cannot read.
 */
public class SpaceException extends RuntimeException {
    public SpaceException(String message) {
        super(message);
    }

    public SpaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
