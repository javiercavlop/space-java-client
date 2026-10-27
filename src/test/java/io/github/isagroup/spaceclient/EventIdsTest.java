package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.types.CacheOptions;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.ContractEventDetails;
import io.github.isagroup.spaceclient.types.PricingEventDetails;
import io.github.isagroup.spaceclient.types.ResyncInfo;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import io.github.isagroup.spaceclient.types.SpaceEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Event ids ({@code eventId}, {@code occurredAt}; SPACE capability {@code contract-event-ids}),
 * deduplication by {@code eventId} and the {@code resync} event after a reconnection.
 *
 * Like {@link ContractEventsAndTokenTest}, the socket.io transport is not exercised: the tests
 * hand messages and connections to the client's dispatch methods.
 */
@DisplayName("Event ids, deduplication and resync")
class EventIdsTest {

    private static final String EVENT_ID = "019a2a6b-7c1e-7d3f-9b2a-5c4e8f1a2b3c";

    private SpaceClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private SpaceClient connect(SpaceConnectionOptions options) {
        client = new SpaceClient(options);
        return client;
    }

    private static SpaceConnectionOptions options() {
        return new SpaceConnectionOptions("http://localhost:1", "org-key");
    }

    private static JSONObject event(String eventId) throws Exception {
        JSONObject message = new JSONObject()
                .put("code", "CONTRACT_NOVATED")
                .put("details", new JSONObject()
                        .put("userId", "u1")
                        .put("organizationId", "org-1")
                        .put("contractVersion", "5:x")
                        .put("services", new JSONArray().put("acme")));
        if (eventId != null) {
            message.put("eventId", eventId).put("occurredAt", "2026-10-27T10:00:00.123Z");
        }
        return message;
    }

    private List<ContractEventDetails> novations() {
        List<ContractEventDetails> received = new ArrayList<>();
        client.on("contract_novated", data -> received.add((ContractEventDetails) data));
        return received;
    }

    private static List<String> ids(List<ContractEventDetails> events) {
        List<String> ids = new ArrayList<>();
        for (ContractEventDetails details : events) {
            ids.add(details.getEventId());
        }
        return ids;
    }

    // -- types ------------------------------------------------------------

    @Test
    @DisplayName("Contract listeners get eventId, occurredAt (parsed, raw kept) and code")
    void contractDetailsCarryEventFields() throws Exception {
        connect(options());
        List<ContractEventDetails> received = novations();

        client.dispatchContractMessage(event(EVENT_ID));

        ContractEventDetails details = received.get(0);
        assertThat(details.getUserId()).isEqualTo("u1");
        assertThat(details.getCode()).isEqualTo("CONTRACT_NOVATED");
        assertThat(details.getEventId()).isEqualTo(EVENT_ID);
        assertThat(details.getRawOccurredAt()).isEqualTo("2026-10-27T10:00:00.123Z");
        assertThat(details.getOccurredAt()).isEqualTo(Instant.parse("2026-10-27T10:00:00.123Z"));
    }

    @Test
    @DisplayName("occurredAt is null when missing or not a date; an offset is accepted")
    void occurredAtParsing() throws Exception {
        connect(options());
        List<ContractEventDetails> received = novations();

        client.dispatchContractMessage(event(null));
        client.dispatchContractMessage(event("e-2").put("occurredAt", "not a date"));
        client.dispatchContractMessage(event("e-3").put("occurredAt", "2026-10-27T12:00:00.123+02:00"));

        assertThat(received.get(0).getEventId()).isNull();
        assertThat(received.get(0).getOccurredAt()).isNull();
        assertThat(received.get(0).getRawOccurredAt()).isNull();
        assertThat(received.get(1).getOccurredAt()).isNull();
        assertThat(received.get(1).getRawOccurredAt()).isEqualTo("not a date");
        assertThat(received.get(2).getOccurredAt()).isEqualTo(Instant.parse("2026-10-27T10:00:00.123Z"));
    }

    @Test
    @DisplayName("Pricing listeners get the details JSONObject with the event fields")
    void pricingDetailsCarryEventFields() throws Exception {
        connect(options());
        List<Object> received = new ArrayList<>();
        client.on("pricing_created", received::add);
        JSONObject message = new JSONObject()
                .put("code", "PRICING_CREATED")
                .put("eventId", "p-1")
                .put("occurredAt", "2026-10-27T10:00:00.000Z")
                .put("details", new JSONObject().put("serviceName", "acme").put("pricingVersion", "2025"));

        client.dispatchPricingMessage(message);
        client.dispatchPricingMessage(message);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(JSONObject.class).isInstanceOf(PricingEventDetails.class);
        PricingEventDetails details = (PricingEventDetails) received.get(0);
        assertThat(details.getString("serviceName")).isEqualTo("acme");
        assertThat(details.getString("pricingVersion")).isEqualTo("2025");
        assertThat(details.getEventId()).isEqualTo("p-1");
        assertThat(details.getCode()).isEqualTo("PRICING_CREATED");
        assertThat(details.getOccurredAt()).isEqualTo(Instant.parse("2026-10-27T10:00:00Z"));
    }

    // -- deduplication -----------------------------------------------------

    @Test
    @DisplayName("A repeated eventId is delivered once")
    void repeatedEventIdDeliveredOnce() throws Exception {
        connect(options());
        List<ContractEventDetails> received = novations();

        client.dispatchContractMessage(event("a"));
        client.dispatchContractMessage(event("a"));
        client.dispatchContractMessage(event("b"));

        assertThat(ids(received)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("A repeat does not invalidate the cache again")
    void repeatDoesNotInvalidateCache() throws Exception {
        SpaceConnectionOptions options = options();
        options.setCache(new CacheOptions(true, CacheOptions.CacheType.BUILTIN, 300));
        connect(options);
        List<ContractEventDetails> received = novations();
        String key = client.cache.getContractKey("u1");

        client.dispatchContractMessage(event("a"));
        client.cache.set(key, new Contract()); // re-read after the first copy
        client.dispatchContractMessage(event("a"));

        assertThat(received).hasSize(1);
        assertThat(client.cache.get(key, Contract.class)).isNotNull();
    }

    @Test
    @DisplayName("Events without eventId (older SPACE) are passed through")
    void eventsWithoutIdPassThrough() throws Exception {
        connect(options());
        List<ContractEventDetails> received = novations();

        client.dispatchContractMessage(event(null));
        client.dispatchContractMessage(event(null));

        assertThat(received).hasSize(2);
    }

    @Test
    @DisplayName("Deduplication can be turned off")
    void deduplicationCanBeTurnedOff() throws Exception {
        SpaceConnectionOptions options = options();
        options.setEventDeduplication(false);
        connect(options);
        List<ContractEventDetails> received = novations();

        client.dispatchContractMessage(event("a"));
        client.dispatchContractMessage(event("a"));

        assertThat(received).hasSize(2);
    }

    @Test
    @DisplayName("The window forgets the oldest ids beyond its maximum size")
    void windowEvictsOldestIds() throws Exception {
        SpaceConnectionOptions options = options();
        options.setEventDeduplicationMaxEntries(2);
        connect(options);
        List<ContractEventDetails> received = novations();

        for (String id : new String[]{"a", "b", "c", "a", "c"}) {
            client.dispatchContractMessage(event(id));
        }

        // 'a' was evicted by 'c' (window of two: b, c); 'c' is still known.
        assertThat(ids(received)).containsExactly("a", "b", "c", "a");
    }

    @Test
    @DisplayName("EventIdWindow accepts an id once")
    void windowAcceptsOnce() throws Exception {
        EventIdWindow window = new EventIdWindow();
        assertThat(window.accept("x")).isTrue();
        assertThat(window.accept("x")).isFalse();
        assertThat(window.accept("y")).isTrue();
        assertThat(window.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("EventIdWindow forgets ids older than its window")
    void windowForgetsOldIds() throws Exception {
        AtomicLong now = new AtomicLong(0);
        EventIdWindow window = new EventIdWindow(100, Duration.ofSeconds(1), now::get);
        assertThat(window.accept("x")).isTrue();
        now.set(999);
        assertThat(window.accept("x")).isFalse();
        now.set(1_001);
        assertThat(window.accept("x")).isTrue();
    }

    @Test
    @DisplayName("EventIdWindow keeps at most maxEntries ids")
    void windowIsBounded() throws Exception {
        EventIdWindow window = new EventIdWindow(3, Duration.ofMinutes(1));
        for (String id : new String[]{"1", "2", "3", "4"}) {
            assertThat(window.accept(id)).isTrue();
        }
        assertThat(window.size()).isEqualTo(3);
        assertThat(window.accept("1")).isTrue(); // evicted
        assertThat(window.accept("4")).isFalse();
    }

    @Test
    @DisplayName("EventIdWindow defaults and invalid options")
    void windowDefaultsAndValidation() throws Exception {
        assertThat(EventIdWindow.DEFAULT_MAX_ENTRIES).isEqualTo(10_000);
        assertThat(EventIdWindow.DEFAULT_WINDOW).isEqualTo(Duration.ofMinutes(10));
        SpaceConnectionOptions defaults = options();
        assertThat(defaults.isEventDeduplication()).isTrue();
        assertThat(defaults.getEventDeduplicationMaxEntries()).isEqualTo(10_000);
        assertThat(defaults.getEventDeduplicationWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> new EventIdWindow(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventIdWindow(10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventIdWindow(10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- resync -------------------------------------------------------------

    @Test
    @DisplayName("resync is a known event")
    void resyncIsKnown() throws Exception {
        assertThat(SpaceEvent.fromString("resync")).isEqualTo(SpaceEvent.RESYNC);
        assertThat(SpaceEvent.RESYNC.isContractEvent()).isFalse();
    }

    @Test
    @DisplayName("resync fires on the first connection and on every reconnection")
    void resyncOnReconnect() throws Exception {
        connect(options());
        List<Object> resyncs = new ArrayList<>();
        client.on("resync", resyncs::add);

        client.onNamespaceConnected("pricings");
        client.onNamespaceConnected("contracts");
        client.onNamespaceConnected("contracts");
        client.onNamespaceConnected("pricings");

        assertThat(resyncs).containsExactly(
                new ResyncInfo("pricings", false),
                new ResyncInfo("contracts", false),
                new ResyncInfo("contracts", true),
                new ResyncInfo("pricings", true));
        ResyncInfo info = (ResyncInfo) resyncs.get(2);
        assertThat(info.getNamespace()).isEqualTo("contracts");
        assertThat(info.isReconnect()).isTrue();
    }

    @Test
    @DisplayName("A /contracts reconnection invalidates every user's cache before resync")
    void contractsReconnectInvalidatesCache() throws Exception {
        SpaceConnectionOptions options = options();
        options.setCache(new CacheOptions(true, CacheOptions.CacheType.BUILTIN, 300));
        connect(options);
        String contractKey = client.cache.getContractKey("u7");
        String tokenKey = client.cache.getPricingTokenKey("u8");
        List<String> seenAtResync = new ArrayList<>();
        client.on("resync", info -> {
            if (((ResyncInfo) info).isReconnect()) {
                seenAtResync.add(String.valueOf(client.cache.get(contractKey, Contract.class)));
            }
        });
        client.onNamespaceConnected("contracts");
        client.cache.set(contractKey, new Contract());
        client.cache.set(tokenKey, "stale-token");

        client.onNamespaceConnected("contracts");

        assertThat(seenAtResync).containsExactly("null");
        assertThat(client.cache.get(tokenKey, String.class)).isNull();
    }
}
