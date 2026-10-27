package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.types.CacheOptions;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.ContractEventDetails;
import io.github.isagroup.spaceclient.types.PricingTokenPayload;
import io.github.isagroup.spaceclient.types.PricingTokenSubscription;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import io.github.isagroup.spaceclient.types.SpaceEvent;
import io.socket.client.Socket;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract events (API design S6) and the subscription carried by pricing tokens (S7).
 *
 * The socket.io transport is not exercised (no SPACE): the tests check how the client opens the
 * /contracts namespace and what it does with the messages it receives.
 */
@DisplayName("Contract events and pricing token subscription")
class ContractEventsAndTokenTest {

    private SpaceClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private SpaceClient connect(boolean cache) {
        return connect(cache, null);
    }

    private SpaceClient connect(boolean cache, String organizationId) {
        SpaceConnectionOptions options = new SpaceConnectionOptions("http://localhost:1", "org-key");
        if (cache) {
            options.setCache(new CacheOptions(true, CacheOptions.CacheType.BUILTIN, 300));
        }
        options.setOrganizationId(organizationId);
        client = new SpaceClient(options);
        return client;
    }

    private static JSONObject event(String code) throws Exception {
        return new JSONObject()
                .put("code", code)
                .put("details", new JSONObject()
                        .put("userId", "u1")
                        .put("organizationId", "org-1")
                        .put("contractVersion", "5:2026-10-27T10:06:00.000Z")
                        .put("services", new JSONArray().put("acme")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> authOf(Socket socket) throws Exception {
        Field field = Socket.class.getDeclaredField("auth");
        field.setAccessible(true);
        return (Map<String, String>) field.get(socket);
    }

    // -- S6 ---------------------------------------------------------------

    @Test
    @DisplayName("The four contract events are known events")
    void contractEventsAreKnown() {
        assertThat(SpaceEvent.fromString("contract_created")).isEqualTo(SpaceEvent.CONTRACT_CREATED);
        assertThat(SpaceEvent.fromString("CONTRACT_NOVATED")).isEqualTo(SpaceEvent.CONTRACT_NOVATED);
        assertThat(SpaceEvent.fromString("contract_deleted")).isEqualTo(SpaceEvent.CONTRACT_DELETED);
        assertThat(SpaceEvent.fromString("contract_renewed")).isEqualTo(SpaceEvent.CONTRACT_RENEWED);
        assertThat(SpaceEvent.CONTRACT_NOVATED.isContractEvent()).isTrue();
        assertThat(SpaceEvent.PRICING_CREATED.isContractEvent()).isFalse();
    }

    @Test
    @DisplayName("Without contract listeners the /contracts namespace is not opened")
    void noContractListenersNoNamespace() {
        connect(false);
        client.on("pricing_created", data -> { });
        assertThat(client.contractSocketNamespace()).isNull();
    }

    @Test
    @DisplayName("The first contract listener opens /contracts with the API key as auth")
    void firstListenerOpensAuthenticatedNamespace() throws Exception {
        connect(false);

        client.on("contract_novated", data -> { });

        Socket contracts = client.contractSocketNamespace();
        assertThat(contracts).isNotNull();
        assertThat(authOf(contracts)).containsExactly(Map.entry("apiKey", "org-key"));

        client.on("contract_created", data -> { });
        assertThat(client.contractSocketNamespace()).isSameAs(contracts);
    }

    @Test
    @DisplayName("A member user key sends its organisation with the /contracts handshake")
    void userKeyHandshakeHasOrganization() throws Exception {
        connect(false, "org-1");

        client.on("contract_novated", data -> { });

        assertThat(authOf(client.contractSocketNamespace()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("apiKey", "org-key", "organizationId", "org-1"));
    }

    @Test
    @DisplayName("A deletion carries a null contractVersion, and still invalidates the cache")
    void deletionHasNullContractVersion() throws Exception {
        connect(true);
        client.cache.set(client.cache.getContractKey("u1"), new Contract());
        List<Object> deleted = new ArrayList<>();
        client.on("contract_deleted", deleted::add);
        JSONObject message = event("CONTRACT_DELETED");
        message.getJSONObject("details").put("contractVersion", JSONObject.NULL);

        client.dispatchContractMessage(message);

        ContractEventDetails details = (ContractEventDetails) deleted.get(0);
        assertThat(details.getContractVersion()).isNull();
        assertThat(details.getUserId()).isEqualTo("u1");
        assertThat(client.cache.get(client.cache.getContractKey("u1"), Contract.class)).isNull();
        // An omitted field reads the same way.
        JSONObject omitted = event("CONTRACT_DELETED");
        omitted.getJSONObject("details").remove("contractVersion");
        assertThat(ContractEventDetails.fromJson(omitted.getJSONObject("details")).getContractVersion()).isNull();
    }

    @Test
    @DisplayName("Listeners can be registered while events are being dispatched")
    void concurrentRegistration() throws Exception {
        connect(false);
        Field field = SpaceClient.class.getDeclaredField("callbackFunctions");
        field.setAccessible(true);
        assertThat(field.get(client)).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);

        List<Object> received = java.util.Collections.synchronizedList(new ArrayList<>());
        client.on("contract_novated", received::add);
        Thread registering = new Thread(() -> {
            for (int i = 0; i < 2_000; i++) {
                client.on(i % 2 == 0 ? "pricing_created" : "pricing_archived", data -> { });
                client.removeListener("pricing_created");
            }
        });
        registering.start();
        for (int i = 0; i < 2_000; i++) {
            client.dispatchContractMessage(event("CONTRACT_NOVATED"));
        }
        registering.join();
        assertThat(received).hasSize(2_000);
    }

    @Test
    @DisplayName("A contract message reaches its listener as ContractEventDetails")
    void messageReachesListener() throws Exception {
        connect(false);
        List<Object> novated = new ArrayList<>();
        List<Object> deleted = new ArrayList<>();
        client.on("contract_novated", novated::add);
        client.on("CONTRACT_DELETED", deleted::add);

        client.dispatchContractMessage(event("CONTRACT_NOVATED"));

        assertThat(deleted).isEmpty();
        assertThat(novated).hasSize(1);
        ContractEventDetails details = (ContractEventDetails) novated.get(0);
        assertThat(details.getUserId()).isEqualTo("u1");
        assertThat(details.getOrganizationId()).isEqualTo("org-1");
        assertThat(details.getContractVersion()).isEqualTo("5:2026-10-27T10:06:00.000Z");
        assertThat(details.getServices()).containsExactly("acme");
    }

    @Test
    @DisplayName("A contract code on /pricings is not a contract event")
    void contractCodeOnPricingsIgnored() throws Exception {
        connect(false);
        List<Object> novated = new ArrayList<>();
        List<Object> created = new ArrayList<>();
        client.on("contract_novated", novated::add);
        client.on("pricing_created", created::add);

        client.dispatchPricingMessage(event("CONTRACT_NOVATED"));
        client.dispatchPricingMessage(new JSONObject().put("code", "PRICING_CREATED").put("details", "x"));

        assertThat(novated).isEmpty();
        assertThat(created).containsExactly("x");
    }

    @Test
    @DisplayName("A contract event invalidates the user's cache before the callback runs")
    void eventInvalidatesCache() throws Exception {
        connect(true);
        String key = client.cache.getContractKey("u1");
        client.cache.set(key, new Contract());
        List<Object> seenInCallback = new ArrayList<>();
        client.on("contract_renewed", data -> seenInCallback.add(String.valueOf(client.cache.get(key, Contract.class))));

        client.dispatchContractMessage(event("CONTRACT_RENEWED"));

        assertThat(seenInCallback).containsExactly("null");
    }

    @Test
    @DisplayName("Removing all listeners closes the /contracts namespace")
    void removingListenersCloses() {
        connect(false);
        client.on("contract_deleted", data -> { });
        client.removeAllListeners();
        assertThat(client.contractSocketNamespace().connected()).isFalse();
        assertThatCode(() -> client.disconnect()).doesNotThrowAnyException();
    }

    // -- S7 ---------------------------------------------------------------

    private static String token(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static final String PAYLOAD = "{\"sub\":\"u1\",\"iat\":1761559200,\"exp\":1761562800,"
            + "\"features\":{\"acme-storage\":{\"eval\":true,\"used\":{\"acme-storage\":4},\"limit\":{\"acme-storage\":10}}},"
            + "\"pricingContext\":{\"features\":{},\"usageLimits\":{}},"
            + "\"subscriptionContext\":{\"acme-storage\":4},"
            + "\"subscription\":{\"contractedServices\":{\"acme\":\"2025-01-10\"},"
            + "\"subscriptionPlans\":{\"acme\":\"growth\"},"
            + "\"subscriptionAddOns\":{\"acme\":{\"extraStorage\":2}},"
            + "\"contractVersion\":\"5:2026-10-27T10:06:00.000Z\"},"
            + "\"somethingNew\":1}";

    @Test
    @DisplayName("PricingTokenPayload.decode reads the subscription claim")
    void decodeReadsSubscription() {
        PricingTokenPayload payload = PricingTokenPayload.decode(token(PAYLOAD));

        assertThat(payload.getSub()).isEqualTo("u1");
        assertThat(payload.getExp()).isEqualTo(1761562800L);
        assertThat(payload.getFeatures().get("acme-storage").getEval()).isTrue();
        PricingTokenSubscription subscription = payload.getSubscription();
        assertThat(subscription).isNotNull();
        assertThat(subscription.getContractedServices()).containsEntry("acme", "2025-01-10");
        assertThat(subscription.getSubscriptionPlans()).containsEntry("acme", "growth");
        assertThat(subscription.getSubscriptionAddOns().get("acme")).containsEntry("extraStorage", 2);
        assertThat(subscription.getContractVersion()).isEqualTo("5:2026-10-27T10:06:00.000Z");
    }

    @Test
    @DisplayName("Tokens from older SPACE versions have no subscription")
    void legacyTokens() {
        String legacy = PAYLOAD.replaceAll(",\"subscription\":\\{.*?\\}\\},", ",");
        assertThat(legacy).doesNotContain("\"subscription\"");
        assertThat(PricingTokenPayload.decode(token(legacy)).getSubscription()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "a.!!!.c", "a.W10.c"})
    @DisplayName("Malformed tokens are rejected")
    void malformed(String bad) {
        assertThatThrownBy(() -> PricingTokenPayload.decode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
