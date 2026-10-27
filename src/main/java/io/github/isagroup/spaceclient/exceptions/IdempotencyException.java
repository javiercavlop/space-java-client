package io.github.isagroup.spaceclient.exceptions;

/**
 * SPACE refused a write because of its {@code Idempotency-Key} (capability
 * {@code idempotency-key}); this request applied nothing. {@link #getCode()} is SPACE's code
 * ({@code IDEMPOTENCY_CONFLICT}, {@code IDEMPOTENCY_IN_PROGRESS}, {@code IDEMPOTENCY_KEY_INVALID})
 * and {@link #getIdempotencyKey()} the key the request carried. A {@link SpaceApiException}, so
 * code that catches refusals still does.
 */
public class IdempotencyException extends SpaceApiException {
    private final String code;
    private final String idempotencyKey;

    protected IdempotencyException(SpaceApiException cause, String code, String idempotencyKey, String message) {
        super(cause.getStatusCode(), cause.getMethod(), cause.getPath(), cause.getBody(), message, cause.getHeaders());
        initCause(cause);
        this.code = code;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * The typed exception for a refusal of the key, or {@code null} if {@code refusal} is something
     * else.
     *
     * @param refusal           what SPACE answered
     * @param idempotencyKey    the key the request carried
     * @param retryAfterSeconds the parsed {@code Retry-After}, or {@code null}
     */
    public static IdempotencyException of(SpaceApiException refusal, String idempotencyKey, Integer retryAfterSeconds) {
        com.fasterxml.jackson.databind.JsonNode body = refusal.bodyAsJson();
        String code = body != null && body.hasNonNull("code") ? body.get("code").asText() : null;
        if (code == null) {
            return null;
        }
        int status = refusal.getStatusCode();
        if (status == 409 && IdempotencyConflictException.CODE.equals(code)) {
            return new IdempotencyConflictException(refusal, idempotencyKey);
        }
        if (status == 409 && IdempotencyInProgressException.CODE.equals(code)) {
            return new IdempotencyInProgressException(refusal, idempotencyKey, retryAfterSeconds);
        }
        if (status == 400 && IdempotencyKeyInvalidException.CODE.equals(code)) {
            return new IdempotencyKeyInvalidException(refusal, idempotencyKey);
        }
        return null;
    }

    public String getCode() {
        return code;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
