package io.github.isagroup.spaceclient.exceptions;

/** {@code 400 IDEMPOTENCY_KEY_INVALID}: SPACE rejected the key (it must have 1 to 255 characters). */
public class IdempotencyKeyInvalidException extends IdempotencyException {
    public static final String CODE = "IDEMPOTENCY_KEY_INVALID";

    public IdempotencyKeyInvalidException(SpaceApiException cause, String idempotencyKey) {
        super(cause, CODE, idempotencyKey, "Idempotency-Key must have between 1 and 255 characters");
    }
}
