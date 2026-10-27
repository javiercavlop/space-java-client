package io.github.isagroup.spaceclient.types;

import java.util.Objects;

/**
 * Passed to {@code resync} listeners each time a namespace connects.
 *
 * <p>SPACE does not store or replay events, so whatever changed while a namespace was
 * disconnected was not announced: re-read the contracts you hold (comparing their
 * {@code contractVersion}) when {@link #isReconnect()} is true.
 */
public final class ResyncInfo {

    /** The {@code /pricings} namespace. */
    public static final String PRICINGS = "pricings";
    /** The {@code /contracts} namespace. */
    public static final String CONTRACTS = "contracts";

    private final String namespace;
    private final boolean reconnect;

    public ResyncInfo(String namespace, boolean reconnect) {
        this.namespace = namespace;
        this.reconnect = reconnect;
    }

    /** {@code "pricings"} or {@code "contracts"}. */
    public String getNamespace() {
        return namespace;
    }

    /** {@code false} on the namespace's first connection, {@code true} afterwards. */
    public boolean isReconnect() {
        return reconnect;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResyncInfo)) {
            return false;
        }
        ResyncInfo that = (ResyncInfo) other;
        return reconnect == that.reconnect && Objects.equals(namespace, that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, reconnect);
    }

    @Override
    public String toString() {
        return "ResyncInfo{namespace=" + namespace + ", reconnect=" + reconnect + '}';
    }
}
