package io.github.isagroup.spaceclient.types;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Parsing of the {@code occurredAt} of SPACE events. */
final class EventTimes {

    private EventTimes() {
    }

    /** {@code occurredAt} (ISO 8601) as an {@link Instant}; {@code null} if absent or invalid. */
    static Instant parse(String occurredAt) {
        if (occurredAt == null || occurredAt.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(occurredAt);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(occurredAt).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** A top-level string field of a message; {@code null} when absent, JSON null or empty. */
    static String text(org.json.JSONObject message, String key) {
        if (message == null || message.isNull(key)) {
            return null;
        }
        Object value = message.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            return null;
        }
        return (String) value;
    }
}
