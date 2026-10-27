package io.github.isagroup.spaceclient.types;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Details of a contract event ({@code contract_created}, {@code contract_novated},
 * {@code contract_deleted}, {@code contract_renewed}), passed to the listener registered with
 * {@code SpaceClient.on}.
 *
 * <p>SPACE versions with the {@code contract-event-ids} capability also send {@code eventId} and
 * {@code occurredAt}; with older ones {@link #getEventId()} and {@link #getOccurredAt()} are
 * {@code null}.
 */
public class ContractEventDetails {

    private final String userId;
    private final String organizationId;
    private final String contractVersion;
    private final List<String> services;
    private final String code;
    private final String eventId;
    private final String rawOccurredAt;
    private final Instant occurredAt;

    public ContractEventDetails(String userId, String organizationId, String contractVersion, List<String> services) {
        this(userId, organizationId, contractVersion, services, null, null, null);
    }

    public ContractEventDetails(String userId, String organizationId, String contractVersion, List<String> services,
                                String code, String eventId, String rawOccurredAt) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.contractVersion = contractVersion;
        this.services = services != null ? Collections.unmodifiableList(services) : Collections.emptyList();
        this.code = code;
        this.eventId = eventId;
        this.rawOccurredAt = rawOccurredAt;
        this.occurredAt = EventTimes.parse(rawOccurredAt);
    }

    /**
     * Reads a whole socket.io message: its {@code details} plus {@code code}, {@code eventId} and
     * {@code occurredAt}; {@code null} if it has no {@code details} object.
     */
    public static ContractEventDetails fromMessage(JSONObject message) {
        ContractEventDetails details = fromJson(message.optJSONObject("details"));
        if (details == null) {
            return null;
        }
        return new ContractEventDetails(details.userId, details.organizationId, details.contractVersion,
                details.services, message.optString("code", ""), EventTimes.text(message, "eventId"),
                EventTimes.text(message, "occurredAt"));
    }

    /** Reads the {@code details} object of a socket.io message; {@code null} in, {@code null} out. */
    public static ContractEventDetails fromJson(JSONObject details) {
        if (details == null) {
            return null;
        }
        List<String> services = new ArrayList<>();
        JSONArray array = details.optJSONArray("services");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                services.add(array.optString(i));
            }
        }
        return new ContractEventDetails(
                text(details, "userId"),
                text(details, "organizationId"),
                text(details, "contractVersion"),
                services);
    }

    /** The field as a string; {@code null} when absent or JSON {@code null} (not {@code "null"}). */
    private static String text(JSONObject details, String key) {
        return details.isNull(key) ? null : details.optString(key, null);
    }

    public String getUserId() {
        return userId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    /** The contract's version after the change; {@code null} for {@code contract_deleted}. */
    public String getContractVersion() {
        return contractVersion;
    }

    /** The services of the contract affected by the change. */
    public List<String> getServices() {
        return services;
    }

    /** The event code as sent, e.g. {@code CONTRACT_NOVATED}; {@code null} if not read from a message. */
    public String getCode() {
        return code;
    }

    /**
     * Identifies the event: every copy of one event has the same one. The client already drops
     * repeats it has seen. Compare it for equality only, never to order events.
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * When SPACE stored the change (the clock of the SPACE instance; do not order events of
     * different instances by it); {@code null} if absent or not a valid date.
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** {@code occurredAt} exactly as SPACE sent it (ISO 8601, UTC, milliseconds). */
    public String getRawOccurredAt() {
        return rawOccurredAt;
    }

    @Override
    public String toString() {
        return "ContractEventDetails{userId=" + userId + ", organizationId=" + organizationId
                + ", contractVersion=" + contractVersion + ", services=" + services
                + ", eventId=" + eventId + ", occurredAt=" + rawOccurredAt + '}';
    }
}
