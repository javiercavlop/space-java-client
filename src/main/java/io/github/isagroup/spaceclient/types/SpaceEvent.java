package io.github.isagroup.spaceclient.types;

/**
 * Space events that can be listened to
 */
public enum SpaceEvent {
    SYNCHRONIZED("synchronized"),
    PRICING_CREATED("pricing_created"),
    PRICING_ARCHIVED("pricing_archived"),
    PRICING_ACTIVED("pricing_actived"),
    SERVICE_DISABLED("service_disabled"),
    CONTRACT_CREATED("contract_created"),
    CONTRACT_NOVATED("contract_novated"),
    CONTRACT_DELETED("contract_deleted"),
    CONTRACT_RENEWED("contract_renewed"),
    ERROR("error");

    private final String eventName;

    SpaceEvent(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }

    /** True for the events received on the authenticated {@code /contracts} namespace. */
    public boolean isContractEvent() {
        return this == CONTRACT_CREATED || this == CONTRACT_NOVATED
                || this == CONTRACT_DELETED || this == CONTRACT_RENEWED;
    }

    public static SpaceEvent fromString(String text) {
        for (SpaceEvent event : SpaceEvent.values()) {
            if (event.eventName.equalsIgnoreCase(text)) {
                return event;
            }
        }
        return null;
    }
}
