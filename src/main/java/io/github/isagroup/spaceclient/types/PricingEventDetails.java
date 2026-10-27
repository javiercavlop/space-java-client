package io.github.isagroup.spaceclient.types;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Iterator;

/**
 * The {@code details} of a pricing event ({@code pricing_created}, {@code pricing_archived},
 * {@code pricing_actived}, {@code service_disabled}). It is the {@link JSONObject} SPACE sent, so
 * listeners that read it as one keep working, plus the event's own fields.
 */
public class PricingEventDetails extends JSONObject {

    private final String code;
    private final String eventId;
    private final String rawOccurredAt;
    private final Instant occurredAt;

    public PricingEventDetails(JSONObject details, String code, String eventId, String rawOccurredAt) {
        super();
        // Copied key by key: the copy constructor declares a checked JSONException in older
        // org.json versions, which a constructor cannot catch around super().
        Iterator<?> keys = details.keys();
        while (keys.hasNext()) {
            String key = String.valueOf(keys.next());
            try {
                put(key, details.opt(key));
            } catch (JSONException e) {
                throw new IllegalArgumentException(e);
            }
        }
        this.code = code;
        this.eventId = eventId;
        this.rawOccurredAt = rawOccurredAt;
        this.occurredAt = EventTimes.parse(rawOccurredAt);
    }

    /** Reads a socket.io message; {@code null} if its {@code details} is not an object. */
    public static PricingEventDetails fromMessage(JSONObject message) {
        JSONObject details = message.optJSONObject("details");
        if (details == null) {
            return null;
        }
        return new PricingEventDetails(details, message.optString("code", ""),
                EventTimes.text(message, "eventId"), EventTimes.text(message, "occurredAt"));
    }

    /** The event code as sent, e.g. {@code PRICING_CREATED}. */
    public String getCode() {
        return code;
    }

    /**
     * Identifies the event; every copy of one event has the same one. {@code null} with SPACE
     * versions without the {@code contract-event-ids} capability. Compare it for equality only.
     */
    public String getEventId() {
        return eventId;
    }

    /** When SPACE stored the change; {@code null} if absent or not a valid date. */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** {@code occurredAt} exactly as SPACE sent it. */
    public String getRawOccurredAt() {
        return rawOccurredAt;
    }
}
