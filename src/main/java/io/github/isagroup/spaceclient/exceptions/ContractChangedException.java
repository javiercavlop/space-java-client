package io.github.isagroup.spaceclient.exceptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The contract changed since the version the caller computed its change on.
 *
 * Thrown by {@code contracts.updateContractSubscription(userId, subscription, options)} when
 * {@code options.expectedContractVersion} was given and SPACE answered
 * {@code 412 {"error": "CONTRACT_CHANGED", "contractVersion": "<current>"}}: someone else novated,
 * renewed or edited the contract in the meantime. Re-read it (or preview again), show the customer
 * the new situation and ask them to confirm; {@link #getContractVersion()} is the version to send
 * next time.
 */
public class ContractChangedException extends SpaceApiException {
    public static final String CODE = "CONTRACT_CHANGED";

    private final String userId;
    private final String expectedContractVersion;
    private final String contractVersion;

    public ContractChangedException(SpaceApiException cause, String userId, String expectedContractVersion) {
        this(cause, userId, expectedContractVersion, readContractVersion(cause));
    }

    private ContractChangedException(SpaceApiException cause, String userId, String expectedContractVersion,
                                     String contractVersion) {
        super(cause.getStatusCode(), cause.getMethod(), cause.getPath(), cause.getBody(),
                "The contract of user " + userId + " changed (expected "
                        + (expectedContractVersion != null ? expectedContractVersion : "unknown") + ", current "
                        + (contractVersion != null ? contractVersion : "unknown") + ")");
        initCause(cause);
        this.userId = userId;
        this.expectedContractVersion = expectedContractVersion;
        this.contractVersion = contractVersion;
    }

    public String getUserId() {
        return userId;
    }

    /** The version the caller expected. */
    public String getExpectedContractVersion() {
        return expectedContractVersion;
    }

    /** The current version in SPACE, or {@code null} if SPACE did not report it. */
    public String getContractVersion() {
        return contractVersion;
    }

    private static String readContractVersion(SpaceApiException cause) {
        JsonNode body = cause.bodyAsJson();
        return body != null && body.hasNonNull("contractVersion") ? body.get("contractVersion").asText() : null;
    }
}
