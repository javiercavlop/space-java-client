package io.github.isagroup.spaceclient.types;

/**
 * Options of a novation ({@code contracts.novate}) or of its preview.
 *
 * <pre>
 * client.contracts.novate(userId, subscription,
 *     NovationOptions.expecting(preview.getContractVersion()).keepBillingPeriod(true));
 * </pre>
 */
public class NovationOptions {

    private String expectedContractVersion;
    private boolean keepBillingPeriod;
    private boolean rejectPrivate;
    private String idempotencyKey;

    public NovationOptions() {
    }

    /**
     * Options that make the novation conditional on {@code contractVersion}.
     *
     * @throws IllegalArgumentException if {@code contractVersion} is {@code null} or empty
     */
    public static NovationOptions expecting(String contractVersion) {
        return new NovationOptions().expectedContractVersion(contractVersion);
    }

    /**
     * The {@code contractVersion} the change was computed on (from {@code getContract},
     * {@code getPricings} or {@code previewNovation}). Sent as {@code If-Match}; if the contract
     * changed meanwhile SPACE answers 412 and the client throws {@code ContractChangedException}.
     * Ignored by the preview.
     *
     * @throws IllegalArgumentException if {@code contractVersion} is {@code null} or empty: a
     *         version that was never known (e.g. from an old SPACE) must not quietly turn a
     *         conditional novation into an unconditional one. Do not call this for an
     *         unconditional novation.
     */
    public NovationOptions expectedContractVersion(String contractVersion) {
        if (contractVersion == null || contractVersion.isEmpty()) {
            throw new IllegalArgumentException("expectedContractVersion must be a non-empty contract version; "
                    + "leave it unset for an unconditional novation");
        }
        this.expectedContractVersion = contractVersion;
        return this;
    }

    /**
     * Keep the current billing period instead of restarting it ({@code ?keepBillingPeriod=true}).
     * Default: the period restarts.
     */
    public NovationOptions keepBillingPeriod(boolean keepBillingPeriod) {
        this.keepBillingPeriod = keepBillingPeriod;
        return this;
    }

    /**
     * Ask SPACE to reject private plans and add-ons ({@code ?rejectPrivate=true}). Opt-in: by
     * default SPACE accepts them, as before. Applies to the novation and to its preview.
     */
    public NovationOptions rejectPrivate(boolean rejectPrivate) {
        this.rejectPrivate = rejectPrivate;
        return this;
    }

    /**
     * The {@code Idempotency-Key} of the novation (see {@link WriteOptions#idempotencyKey(String)}).
     * Default: a new UUID per call, reused on the client's retries together with {@code If-Match}.
     * Ignored by the preview.
     *
     * @throws IllegalArgumentException unless the key has 1 to 255 characters
     */
    public NovationOptions idempotencyKey(String idempotencyKey) {
        this.idempotencyKey = WriteOptions.checkKey(idempotencyKey);
        return this;
    }

    /** The caller's key, or {@code null} for a generated one. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isRejectPrivate() {
        return rejectPrivate;
    }

    public String getExpectedContractVersion() {
        return expectedContractVersion;
    }

    public boolean isKeepBillingPeriod() {
        return keepBillingPeriod;
    }
}
