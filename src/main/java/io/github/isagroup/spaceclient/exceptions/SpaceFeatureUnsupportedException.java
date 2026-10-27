package io.github.isagroup.spaceclient.exceptions;

import io.github.isagroup.spaceclient.types.Contract;

/**
 * The connected SPACE does not implement a feature the call depends on.
 *
 * Thrown instead of quietly doing something else: returning the JSON pricing as if it were the
 * YAML file, or writing a novation without the guarantee the caller asked for.
 * {@link #getCapability()} is the {@code X-Space-Capabilities} token of the missing feature
 * ({@code pricing-yaml}, {@code contract-version}, {@code keep-billing-period}...).
 *
 * {@link #isWritten()} says whether SPACE changed anything: {@code false} when the client refused
 * before sending a write (or for a read); {@code true} only for the post-write backstop, when the
 * response shows SPACE applied a novation without the guarantee. The change is then in place and
 * {@link #getContract()} is what SPACE stored.
 *
 * Not a {@link SpaceApiException}: SPACE did not refuse anything.
 */
public class SpaceFeatureUnsupportedException extends SpaceException {
    public static final String CODE = "SPACE_FEATURE_UNSUPPORTED";

    private final String capability;
    private final boolean written;
    private final transient Contract contract;

    public SpaceFeatureUnsupportedException(String capability, String message) {
        this(capability, message, false, null);
    }

    public SpaceFeatureUnsupportedException(String capability, String message, boolean written, Contract contract) {
        super(message != null ? message : "The connected SPACE does not support \"" + capability + "\"");
        this.capability = capability;
        this.written = written;
        this.contract = contract;
    }

    /** Always {@value #CODE}. */
    public String getCode() {
        return CODE;
    }

    /** The {@code X-Space-Capabilities} token of the missing feature. */
    public String getCapability() {
        return capability;
    }

    /** Whether SPACE applied a write despite the missing guarantee. */
    public boolean isWritten() {
        return written;
    }

    /** The contract SPACE stored, when {@link #isWritten()}; otherwise {@code null}. */
    public Contract getContract() {
        return contract;
    }
}
