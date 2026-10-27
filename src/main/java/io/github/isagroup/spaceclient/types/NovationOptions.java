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

    public String getExpectedContractVersion() {
        return expectedContractVersion;
    }

    public boolean isKeepBillingPeriod() {
        return keepBillingPeriod;
    }
}
