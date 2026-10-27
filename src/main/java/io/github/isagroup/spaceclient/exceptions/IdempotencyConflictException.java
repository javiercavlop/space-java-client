package io.github.isagroup.spaceclient.exceptions;

/**
 * {@code 409 IDEMPOTENCY_CONFLICT}: the key was already used for a different request (another
 * body, query, route or {@code If-Match}). Nothing was applied. A new operation needs a new key;
 * reusing one for another operation is a bug of the caller.
 */
public class IdempotencyConflictException extends IdempotencyException {
    public static final String CODE = "IDEMPOTENCY_CONFLICT";

    public IdempotencyConflictException(SpaceApiException cause, String idempotencyKey) {
        super(cause, CODE, idempotencyKey,
                "Idempotency-Key " + idempotencyKey + " was already used for a different request");
    }
}
