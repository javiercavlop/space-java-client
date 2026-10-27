package io.github.isagroup.spaceclient.exceptions;

/**
 * {@code 409 IDEMPOTENCY_IN_PROGRESS}: a request with the same key is still running in SPACE, and
 * the client's retries (if any) are spent. Retry later with the SAME key: SPACE then replays the
 * outcome of the running request. {@link #getRetryAfterSeconds()} is SPACE's {@code Retry-After}.
 */
public class IdempotencyInProgressException extends IdempotencyException {
    public static final String CODE = "IDEMPOTENCY_IN_PROGRESS";

    private final Integer retryAfterSeconds;

    public IdempotencyInProgressException(SpaceApiException cause, String idempotencyKey, Integer retryAfterSeconds) {
        super(cause, CODE, idempotencyKey,
                "A request with Idempotency-Key " + idempotencyKey + " is still being processed");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** SPACE's {@code Retry-After} in seconds, or {@code null} if it sent none. */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
