package io.github.isagroup.spaceclient.types;

/**
 * Options of the writes SPACE can apply at most once ({@code Idempotency-Key}, capability
 * {@code idempotency-key}): consumptions, reverts, contract creation, novations and usage level
 * changes.
 *
 * <pre>
 * // job.getIdempotencyKey() was created (UUID) and stored when the job was enqueued.
 * client.features.evaluate(userId, "acme-exports", consumption,
 *     WriteOptions.withIdempotencyKey(job.getIdempotencyKey()));
 * </pre>
 */
public class WriteOptions {
    /** Longest key SPACE accepts. */
    public static final int MAX_KEY_LENGTH = 255;

    private String idempotencyKey;

    public WriteOptions() {
    }

    /** Options with the caller's {@code Idempotency-Key}. */
    public static WriteOptions withIdempotencyKey(String idempotencyKey) {
        return new WriteOptions().idempotencyKey(idempotencyKey);
    }

    /**
     * The {@code Idempotency-Key} of this operation. By default the client generates a random UUID
     * per call and reuses it on its own retries. Pass a key stored with the operation (e.g. in the
     * job or outbox row that issues the call) when it may be retried after the process restarts:
     * SPACE then replays the first outcome instead of applying it again. Never reuse a key for a
     * different operation. With a key of the caller's, the client checks that SPACE advertises
     * {@code idempotency-key} and throws {@code SpaceFeatureUnsupportedException} without writing
     * if it does not.
     *
     * @throws IllegalArgumentException unless the key has 1 to 255 characters
     */
    public WriteOptions idempotencyKey(String idempotencyKey) {
        this.idempotencyKey = checkKey(idempotencyKey);
        return this;
    }

    /** The caller's key, or {@code null} for a generated one. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns {@code key} if it has 1 to 255 characters.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static String checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must have 1 to " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }
}
