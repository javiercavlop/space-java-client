package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.ContractChangedException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.types.CacheOptions;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.NovationError;
import io.github.isagroup.spaceclient.types.NovationErrorCode;
import io.github.isagroup.spaceclient.types.NovationOptions;
import io.github.isagroup.spaceclient.types.NovationPreview;
import io.github.isagroup.spaceclient.types.NovationUsageWarning;
import io.github.isagroup.spaceclient.types.NovationVersionChange;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import io.github.isagroup.spaceclient.types.Subscription;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Changing a subscription safely (API design S4, S5, S8), against a MockWebServer.
 */
@DisplayName("Novation preview, optimistic concurrency and keepBillingPeriod")
class NovationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CONTRACT = "{\"userContact\":{\"userId\":\"u1\",\"username\":\"u1\"},"
            + "\"billingPeriod\":{\"startDate\":\"2026-10-01T00:00:00.000Z\",\"endDate\":\"2026-11-01T00:00:00.000Z\","
            + "\"autoRenew\":true,\"renewalDays\":30},"
            + "\"usageLevels\":{},\"contractedServices\":{\"acme\":\"2025-01-10\"},"
            + "\"subscriptionPlans\":{\"acme\":\"basic\"},\"subscriptionAddOns\":{\"acme\":{\"extraStorage\":2}},"
            + "\"history\":[]";

    private static final String PREVIEW = "{\"valid\":false,"
            + "\"errors\":[{\"code\":\"ADDON_NOT_AVAILABLE\",\"service\":\"acme\","
            + "\"path\":\"subscriptionAddOns.acme.extraStorage\",\"message\":\"not available for basic\"},"
            + "{\"code\":\"SOMETHING_NEW\",\"service\":\"acme\",\"message\":\"a code this client does not know\"}],"
            + "\"effective\":{\"contractedServices\":{\"acme\":\"2025-01-10\"},\"subscriptionPlans\":{\"acme\":\"basic\"},"
            + "\"subscriptionAddOns\":{\"acme\":{\"extraStorage\":2}}},"
            + "\"versionChanges\":{\"acme\":{\"from\":\"2024-06-08\",\"to\":\"2025-01-10\",\"reason\":\"policy:all_last\"}},"
            + "\"usageWarnings\":[{\"service\":\"acme\",\"usageLimit\":\"storage\",\"consumed\":42,\"newLimit\":10}],"
            + "\"billingPeriod\":{\"startDate\":\"2026-10-27\",\"endDate\":\"2026-11-26\",\"restarts\":true},"
            + "\"contractVersion\":\"3:2026-10-27T10:00:00.000Z\"}";

    private MockWebServer server;
    private SpaceClient client;
    private volatile MockResponse next;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                return next;
            }
        });
        server.start();
        client = connect(false);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    private SpaceClient connect(boolean cache) {
        SpaceConnectionOptions options = new SpaceConnectionOptions(server.url("/").toString(), "org-key");
        if (cache) {
            options.setCache(new CacheOptions(true, CacheOptions.CacheType.BUILTIN, 300));
        }
        return new SpaceClient(options);
    }

    private RecordedRequest nextApiRequest() throws InterruptedException {
        while (true) {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).as("an API request").isNotNull();
            if (request.getPath().startsWith("/api/v1")) {
                return request;
            }
        }
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static Subscription change() {
        Map<String, Map<String, Integer>> addOns = new HashMap<>();
        addOns.put("acme", Map.of("extraStorage", 2));
        return new Subscription(Map.of("acme", "2025-01-10"), Map.of("acme", "basic"), addOns);
    }

    // -- S4 ---------------------------------------------------------------

    @Test
    @DisplayName("previewNovation parses validity, errors, effective subscription, changes, warnings and period")
    void previewParsesEverything() throws Exception {
        next = json(200, PREVIEW);

        NovationPreview preview = client.contracts.previewNovation("u1", change());

        RecordedRequest request = nextApiRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1/novation/preview");
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("subscriptionPlans").get("acme").asText()).isEqualTo("basic");
        assertThat(body.get("contractedServices").get("acme").asText()).isEqualTo("2025-01-10");

        // An invalid change is an answer, not an exception.
        assertThat(preview.isValid()).isFalse();
        NovationError first = preview.getErrors().get(0);
        assertThat(first.getCode()).isEqualTo("ADDON_NOT_AVAILABLE");
        assertThat(first.is(NovationErrorCode.ADDON_NOT_AVAILABLE)).isTrue();
        assertThat(first.getKnownCode()).isEqualTo(NovationErrorCode.ADDON_NOT_AVAILABLE);
        assertThat(first.getService()).isEqualTo("acme");
        assertThat(first.getPath()).isEqualTo("subscriptionAddOns.acme.extraStorage");
        // Unknown codes are kept as strings rather than failing deserialisation.
        NovationError second = preview.getErrors().get(1);
        assertThat(second.getCode()).isEqualTo("SOMETHING_NEW");
        assertThat(second.getKnownCode()).isNull();
        assertThat(second.getPath()).isNull();

        assertThat(preview.getEffective().getSubscriptionPlans()).containsEntry("acme", "basic");
        assertThat(preview.getEffective().getSubscriptionAddOns().get("acme")).containsEntry("extraStorage", 2);
        NovationVersionChange versionChange = preview.getVersionChanges().get("acme");
        assertThat(versionChange.getFrom()).isEqualTo("2024-06-08");
        assertThat(versionChange.getTo()).isEqualTo("2025-01-10");
        assertThat(versionChange.getReason()).isEqualTo("policy:all_last");
        NovationUsageWarning warning = preview.getUsageWarnings().get(0);
        assertThat(warning.getService()).isEqualTo("acme");
        assertThat(warning.getUsageLimit()).isEqualTo("storage");
        assertThat(warning.getConsumed()).isEqualTo(42.0);
        assertThat(warning.getNewLimit()).isEqualTo(10.0);
        assertThat(preview.getBillingPeriod().isRestarts()).isTrue();
        assertThat(preview.getBillingPeriod().getStartDate()).isEqualTo("2026-10-27");
        assertThat(preview.getContractVersion()).isEqualTo("3:2026-10-27T10:00:00.000Z");
    }

    @Test
    @DisplayName("previewNovation omits empty contractedServices and can keep the billing period")
    void previewOmitsContractedServicesAndKeepsPeriod() throws Exception {
        next = json(200, PREVIEW.replace("\"valid\":false", "\"valid\":true"));

        Subscription onlyPlans = new Subscription();
        onlyPlans.setSubscriptionPlans(Map.of("acme", "basic"));
        NovationPreview preview = client.contracts.previewNovation("u1", onlyPlans, true);

        RecordedRequest request = nextApiRequest();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1/novation/preview?keepBillingPeriod=true");
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        // "Keep the versions or let the policy decide" is said by leaving them out.
        assertThat(body.has("contractedServices")).isFalse();
        assertThat(preview.isValid()).isTrue();
    }

    @Test
    @DisplayName("previewNovation writes nothing, so it leaves the cache alone")
    void previewLeavesCache() {
        SpaceClient cached = connect(true);
        Contract known = new Contract();
        cached.cache.set(cached.cache.getContractKey("u1"), known);
        next = json(200, PREVIEW);

        cached.contracts.previewNovation("u1", change());

        assertThat(cached.cache.get(cached.cache.getContractKey("u1"), Contract.class)).isNotNull();
        cached.close();
    }

    // -- S5 ---------------------------------------------------------------

    @Test
    @DisplayName("updateContractSubscription with an expected version sends If-Match and reads the new version")
    void updateSendsIfMatch() throws Exception {
        next = json(200, CONTRACT + "}").setHeader("ETag", "\"4:2026-10-27T10:05:00.000Z\"");

        Contract contract = client.contracts.updateContractSubscription("u1", change(),
                NovationOptions.expecting("3:2026-10-27T10:00:00.000Z"));

        RecordedRequest request = nextApiRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1");
        assertThat(request.getHeader("If-Match")).isEqualTo("\"3:2026-10-27T10:00:00.000Z\"");
        // Not in the body: the header is the one way of saying it.
        assertThat(JSON.readTree(request.getBody().readUtf8()).has("expectedContractVersion")).isFalse();
        assertThat(contract.getSubscriptionPlans()).containsEntry("acme", "basic");
        // The body has no contractVersion here, so it comes from the ETag.
        assertThat(contract.getContractVersion()).isEqualTo("4:2026-10-27T10:05:00.000Z");
    }

    @Test
    @DisplayName("contractVersion in the body wins over the ETag")
    void bodyVersionWins() {
        next = json(200, CONTRACT + ",\"contractVersion\":\"from-body\"}").setHeader("ETag", "\"from-header\"");

        Contract contract = client.contracts.updateContractSubscription("u1", change(), NovationOptions.expecting("3"));

        assertThat(contract.getContractVersion()).isEqualTo("from-body");
    }

    @Test
    @DisplayName("A concurrent change throws ContractChangedException with the current version")
    void concurrentChange() {
        next = json(412, "{\"error\":\"CONTRACT_CHANGED\",\"contractVersion\":\"5:2026-10-27T10:06:00.000Z\"}");

        assertThatThrownBy(() -> client.contracts.updateContractSubscription("u1", change(),
                NovationOptions.expecting("3:old")))
                .isInstanceOf(ContractChangedException.class)
                .isInstanceOf(SpaceApiException.class)
                .hasMessageContaining("changed")
                .satisfies(error -> {
                    ContractChangedException e = (ContractChangedException) error;
                    assertThat(e.getStatusCode()).isEqualTo(412);
                    assertThat(e.getUserId()).isEqualTo("u1");
                    assertThat(e.getExpectedContractVersion()).isEqualTo("3:old");
                    assertThat(e.getContractVersion()).isEqualTo("5:2026-10-27T10:06:00.000Z");
                });
    }

    @Test
    @DisplayName("A 412 invalidates the cached contract of the user")
    void conflictInvalidatesCache() {
        SpaceClient cached = connect(true);
        cached.cache.set(cached.cache.getContractKey("u1"), new Contract());
        next = json(412, "{\"error\":\"CONTRACT_CHANGED\",\"contractVersion\":\"9\"}");

        assertThatThrownBy(() -> cached.contracts.updateContractSubscription("u1", change(),
                NovationOptions.expecting("3"))).isInstanceOf(ContractChangedException.class);

        assertThat(cached.cache.get(cached.cache.getContractKey("u1"), Contract.class)).isNull();
        cached.close();
    }

    @Test
    @DisplayName("Other refusals with options stay generic SpaceApiExceptions")
    void otherRefusals() {
        next = json(400, "{\"error\":\"PRIVATE_PLAN\"}");

        assertThatThrownBy(() -> client.contracts.updateContractSubscription("u1", change(), NovationOptions.expecting("3")))
                .isExactlyInstanceOf(SpaceApiException.class)
                .satisfies(error -> assertThat(((SpaceApiException) error).getErrorCode()).isEqualTo("PRIVATE_PLAN"));
    }

    @Test
    @DisplayName("The existing two-argument overload is unchanged: no If-Match, null on refusal")
    void legacyOverloadUnchanged() throws Exception {
        next = json(200, CONTRACT + "}");
        Contract contract = client.contracts.updateContractSubscription("u1", change());
        RecordedRequest request = nextApiRequest();
        assertThat(request.getHeader("If-Match")).isNull();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1");
        assertThat(contract).isNotNull();

        next = json(412, "{\"error\":\"CONTRACT_CHANGED\"}");
        assertThat(client.contracts.updateContractSubscription("u1", change())).isNull();
    }

    // -- S8 ---------------------------------------------------------------

    @Test
    @DisplayName("updateContractSubscription can keep the billing period")
    void keepBillingPeriod() throws Exception {
        next = json(200, CONTRACT + "}");

        Contract contract = client.contracts.updateContractSubscription("u1", change(),
                NovationOptions.expecting("3").keepBillingPeriod(true));

        RecordedRequest request = nextApiRequest();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1?keepBillingPeriod=true");
        assertThat(request.getHeader("If-Match")).isEqualTo("\"3\"");
        assertThat(contract.getBillingPeriod()).isNotNull();
    }

    @Test
    @DisplayName("Options without an expected version make an unconditional novation that still throws")
    void optionsWithoutExpectedVersion() throws Exception {
        next = json(200, CONTRACT + "}");

        client.contracts.updateContractSubscription("u1", change(), new NovationOptions().keepBillingPeriod(true));

        RecordedRequest request = nextApiRequest();
        assertThat(request.getHeader("If-Match")).isNull();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1?keepBillingPeriod=true");
    }
}
