package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.ContractChangedException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /** What a SPACE with S0, S5 and S8 says about itself on every response. */
    private static final String CAPABILITIES = "contract-org-scope, contract-version,keep-billing-period";

    private MockWebServer server;
    private SpaceClient client;
    /** Answer to PUT/POST (the write, or the preview). */
    private volatile MockResponse next;
    /** Answer to GET of the contract (the capability probe); 500 when a test does not expect one. */
    private volatile MockResponse nextGet;
    /** Further PUT answers, used in order before {@link #next}. */
    private final ConcurrentLinkedQueue<MockResponse> queuedWrites = new ConcurrentLinkedQueue<>();
    private final List<String> apiCalls = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                apiCalls.add(request.getMethod() + " " + request.getPath());
                if ("GET".equals(request.getMethod())) {
                    return nextGet != null ? nextGet : json(500, "{\"error\":\"unexpected GET\"}");
                }
                MockResponse queued = queuedWrites.poll();
                return queued != null ? queued : next;
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

    /** The next write (PUT/POST), skipping socket.io handshakes and the capability probe. */
    private RecordedRequest nextWrite() throws InterruptedException {
        while (true) {
            RecordedRequest request = nextApiRequest();
            if (!"GET".equals(request.getMethod())) {
                return request;
            }
        }
    }

    private long count(String methodAndPathPrefix) {
        synchronized (apiCalls) {
            return apiCalls.stream().filter(call -> call.startsWith(methodAndPathPrefix)).count();
        }
    }

    /** {@code GET /contracts/u1} of a SPACE that implements the novation options. */
    private void newSpaceContract(String etag) {
        MockResponse response = json(200, CONTRACT + ",\"contractVersion\":\"" + etag + "\"}")
                .setHeader("X-Space-Capabilities", CAPABILITIES);
        nextGet = response.setHeader("ETag", "\"" + etag + "\"");
    }

    /** A PUT answer of a SPACE that implements the options. */
    private static MockResponse written(String body, String etag) {
        MockResponse response = json(200, body).setHeader("X-Space-Capabilities", CAPABILITIES);
        return etag != null ? response.setHeader("ETag", etag) : response;
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
        next = json(200, PREVIEW.replace("\"valid\":false", "\"valid\":true"))
                .setHeader("X-Space-Capabilities", CAPABILITIES);

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
        newSpaceContract("3:2026-10-27T10:00:00.000Z");
        next = written(CONTRACT + "}", "\"4:2026-10-27T10:05:00.000Z\"");

        Contract contract = client.contracts.novate("u1", change(),
                NovationOptions.expecting("3:2026-10-27T10:00:00.000Z"));

        RecordedRequest request = nextWrite();
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
        newSpaceContract("3");
        next = written(CONTRACT + ",\"contractVersion\":\"from-body\"}", "\"from-header\"");

        Contract contract = client.contracts.novate("u1", change(), NovationOptions.expecting("3"));

        assertThat(contract.getContractVersion()).isEqualTo("from-body");
    }

    @Test
    @DisplayName("A concurrent change throws ContractChangedException with the current version")
    void concurrentChange() {
        newSpaceContract("3:old");
        next = json(412, "{\"error\":\"CONTRACT_CHANGED\",\"contractVersion\":\"5:2026-10-27T10:06:00.000Z\"}");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(),
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
        newSpaceContract("3");
        next = json(412, "{\"error\":\"CONTRACT_CHANGED\",\"contractVersion\":\"9\"}");

        assertThatThrownBy(() -> cached.contracts.novate("u1", change(),
                NovationOptions.expecting("3"))).isInstanceOf(ContractChangedException.class);

        assertThat(cached.cache.get(cached.cache.getContractKey("u1"), Contract.class)).isNull();
        cached.close();
    }

    @Test
    @DisplayName("Other refusals with options stay generic SpaceApiExceptions")
    void otherRefusals() {
        newSpaceContract("3");
        next = json(400, "{\"error\":\"PRIVATE_PLAN\"}");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(), NovationOptions.expecting("3")))
                .isExactlyInstanceOf(SpaceApiException.class)
                .satisfies(error -> assertThat(((SpaceApiException) error).getErrorCode()).isEqualTo("PRIVATE_PLAN"));
    }

    @Test
    @DisplayName("updateContractSubscription(userId, subscription) is unchanged: one PUT, no If-Match, null on refusal")
    void legacyOverloadUnchanged() throws Exception {
        next = json(200, CONTRACT + "}");
        Contract contract = client.contracts.updateContractSubscription("u1", change());
        RecordedRequest request = nextApiRequest();
        assertThat(request.getHeader("If-Match")).isNull();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1");
        assertThat(contract).isNotNull();
        assertThat(count("GET ")).isZero();

        next = json(412, "{\"error\":\"CONTRACT_CHANGED\"}");
        assertThat(client.contracts.updateContractSubscription("u1", change())).isNull();
    }

    @Test
    @DisplayName("The three-argument overload is now named novate")
    void overloadRenamed() throws Exception {
        assertThat(ContractModule.class.getMethod("novate", String.class, Subscription.class, NovationOptions.class)
                .getReturnType()).isEqualTo(Contract.class);
        assertThatThrownBy(() -> ContractModule.class.getMethod("updateContractSubscription",
                String.class, Subscription.class, NovationOptions.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    // -- S8 ---------------------------------------------------------------

    @Test
    @DisplayName("updateContractSubscription can keep the billing period")
    void keepBillingPeriod() throws Exception {
        newSpaceContract("3");
        next = written(CONTRACT + "}", "\"4\"");

        Contract contract = client.contracts.novate("u1", change(),
                NovationOptions.expecting("3").keepBillingPeriod(true));

        RecordedRequest request = nextWrite();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1?keepBillingPeriod=true");
        assertThat(request.getHeader("If-Match")).isEqualTo("\"3\"");
        assertThat(contract.getBillingPeriod()).isNotNull();
    }

    @Test
    @DisplayName("Options without an expected version make an unconditional novation that still throws")
    void optionsWithoutExpectedVersion() throws Exception {
        newSpaceContract("3");
        next = written(CONTRACT + "}", "\"4\"");

        client.contracts.novate("u1", change(), new NovationOptions().keepBillingPeriod(true));

        RecordedRequest request = nextWrite();
        assertThat(request.getHeader("If-Match")).isNull();
        assertThat(request.getPath()).isEqualTo("/api/v1/contracts/u1?keepBillingPeriod=true");
    }

    // -- S0: capabilities, and what an older SPACE does with the options ----
    //
    // A SPACE without S5 ignores If-Match and one without S8 ignores keepBillingPeriod: the
    // novation would apply unconditionally, or restart the billing period, while the caller
    // believes otherwise. The client checks X-Space-Capabilities before writing, and the answer
    // after writing.

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("An explicit null or empty expected version is rejected before anything is sent")
    void emptyExpectedVersionRejected(String version) {
        next = written(CONTRACT + "}", "\"4\"");

        assertThatThrownBy(() -> NovationOptions.expecting(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedContractVersion");
        assertThatThrownBy(() -> new NovationOptions().expectedContractVersion(version))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(apiCalls).isEmpty();
    }

    @Test
    @DisplayName("An old SPACE without X-Space-Capabilities gets no write (expectedContractVersion)")
    void oldSpaceNoIfMatch() {
        // SPACE 1.7: no X-Space-Capabilities, no ETag, no contractVersion.
        nextGet = json(200, CONTRACT + "}");
        next = json(200, CONTRACT + "}");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(), NovationOptions.expecting("3")))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .isInstanceOf(SpaceException.class)
                .isNotInstanceOf(SpaceApiException.class)
                .hasMessageContaining("nothing was written")
                .satisfies(error -> {
                    SpaceFeatureUnsupportedException e = (SpaceFeatureUnsupportedException) error;
                    assertThat(e.getCapability()).isEqualTo("contract-version");
                    assertThat(e.isWritten()).isFalse();
                    assertThat(e.getContract()).isNull();
                });
        assertThat(count("GET /api/v1/contracts/u1")).isEqualTo(1);
        assertThat(count("PUT ")).isZero();
    }

    @Test
    @DisplayName("An old SPACE without X-Space-Capabilities gets no write (keepBillingPeriod)")
    void oldSpaceNoKeepBillingPeriod() {
        nextGet = json(200, CONTRACT + "}");
        next = json(200, CONTRACT + "}");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(), new NovationOptions().keepBillingPeriod(true)))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .satisfies(error -> assertThat(((SpaceFeatureUnsupportedException) error).getCapability())
                        .isEqualTo("keep-billing-period"));
        assertThat(count("PUT ")).isZero();
    }

    @Test
    @DisplayName("A SPACE with contract-version but not keep-billing-period gets no write")
    void partialCapabilities() {
        newSpaceContract("3");
        nextGet.setHeader("X-Space-Capabilities", "contract-version");
        next = written(CONTRACT + "}", "\"4\"");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(),
                NovationOptions.expecting("3").keepBillingPeriod(true)))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .satisfies(error -> assertThat(((SpaceFeatureUnsupportedException) error).getCapability())
                        .isEqualTo("keep-billing-period"));
        assertThat(count("PUT ")).isZero();
    }

    @Test
    @DisplayName("Capabilities are cached per client (60 s) and can be cleared")
    void capabilitiesCached() {
        newSpaceContract("5");
        next = written(CONTRACT + "}", "\"5\"");

        client.contracts.novate("u1", change(), NovationOptions.expecting("5"));
        client.contracts.novate("u1", change(), NovationOptions.expecting("5"));
        assertThat(count("GET /api/v1/contracts/u1")).isEqualTo(1);
        assertThat(count("PUT ")).isEqualTo(2);
        assertThat(SpaceClient.CAPABILITIES_TTL_MS).isEqualTo(60_000L);

        client.clearCapabilities();
        client.contracts.novate("u1", change(), NovationOptions.expecting("5"));
        assertThat(count("GET /api/v1/contracts/u1")).isEqualTo(2);
    }

    @Test
    @DisplayName("Capabilities learnt from any response avoid the read; a refusal without the header keeps them")
    void capabilitiesFromAnyResponse() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (!path.startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                apiCalls.add(request.getMethod() + " " + path);
                if (path.equals("/api/v1/contracts/u1/pricings")) {
                    return json(200, "{\"services\":{},\"contractVersion\":\"3\"}")
                            .setHeader("X-Space-Capabilities", CAPABILITIES);
                }
                if (path.endsWith("/new-contracts")) {
                    return new MockResponse().setResponseCode(502).setBody("Bad gateway");
                }
                if ("PUT".equals(request.getMethod())) {
                    return written(CONTRACT + "}", "\"4\"");
                }
                return json(500, "{\"error\":\"unexpected\"}");
            }
        });

        String version = client.contracts.getPricings("u1").getContractVersion();
        assertThatThrownBy(() -> client.services.getNewContractsPricing("acme")).isInstanceOf(SpaceApiException.class);
        client.contracts.novate("u1", change(), NovationOptions.expecting(version));

        // The contract itself was never read: the capabilities came from getPricings.
        assertThat(apiCalls).noneMatch(call -> call.equals("GET /api/v1/contracts/u1"));
        assertThat(count("PUT /api/v1/contracts/u1")).isEqualTo(1);
    }

    @Test
    @DisplayName("A stale expected version fails early on the read, with no write")
    void staleVersionFailsEarly() {
        SpaceClient cached = connect(true);
        cached.cache.set(cached.cache.getContractKey("u1"), new Contract());
        newSpaceContract("7:someone-else");
        next = written(CONTRACT + "}", "\"8\"");

        assertThatThrownBy(() -> cached.contracts.novate("u1", change(), NovationOptions.expecting("\"3\"")))
                .isInstanceOf(ContractChangedException.class)
                .satisfies(error -> {
                    ContractChangedException e = (ContractChangedException) error;
                    assertThat(e.getStatusCode()).isEqualTo(412);
                    assertThat(e.getExpectedContractVersion()).isEqualTo("\"3\"");
                    assertThat(e.getContractVersion()).isEqualTo("7:someone-else");
                });
        assertThat(count("PUT ")).isZero();
        assertThat(cached.cache.get(cached.cache.getContractKey("u1"), Contract.class)).isNull();
        cached.close();
    }

    @Test
    @DisplayName("A SPACE that claims contract-version but answers without ETag is caught after the write")
    void ignoredIfMatchCaughtAfterWrite() {
        SpaceClient cached = connect(true);
        cached.cache.set(cached.cache.getContractKey("u1"), new Contract());
        newSpaceContract("3");
        next = written(CONTRACT + "}", null);

        assertThatThrownBy(() -> cached.contracts.novate("u1", change(), NovationOptions.expecting("3")))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .hasMessageContaining("If-Match")
                .satisfies(error -> {
                    SpaceFeatureUnsupportedException e = (SpaceFeatureUnsupportedException) error;
                    assertThat(e.getCapability()).isEqualTo("contract-version");
                    assertThat(e.isWritten()).isTrue();
                    assertThat(e.getContract().getSubscriptionPlans()).containsEntry("acme", "basic");
                });
        // Nothing is kept for a contract in a state nobody chose.
        assertThat(cached.cache.get(cached.cache.getContractKey("u1"), Contract.class)).isNull();
        cached.close();
    }

    @Test
    @DisplayName("A SPACE that ignores keepBillingPeriod is caught after the write")
    void ignoredKeepBillingPeriodCaughtAfterWrite() {
        newSpaceContract("3");
        next = written(CONTRACT.replace("2026-10-01T00:00:00.000Z", "2026-10-27T00:00:00.000Z") + "}", "\"4\"");

        assertThatThrownBy(() -> client.contracts.novate("u1", change(),
                NovationOptions.expecting("3").keepBillingPeriod(true)))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .satisfies(error -> {
                    SpaceFeatureUnsupportedException e = (SpaceFeatureUnsupportedException) error;
                    assertThat(e.getCapability()).isEqualTo("keep-billing-period");
                    assertThat(e.isWritten()).isTrue();
                    assertThat(e.getContract()).isNotNull();
                });
    }

    @Test
    @DisplayName("Without a prior read, the billing-period backstop compares with the pushed history entry")
    void billingPeriodBackstopUsesHistory() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                apiCalls.add(request.getMethod() + " " + request.getPath());
                if ("GET".equals(request.getMethod())) {
                    return json(200, "{\"services\":{}}").setHeader("X-Space-Capabilities", CAPABILITIES);
                }
                MockResponse queued = queuedWrites.poll();
                return queued != null ? queued : next;
            }
        });
        client.contracts.getPricings("u1");
        String history = ",\"history\":[{\"startDate\":\"2026-10-01T00:00:00.000Z\"}]";
        String kept = CONTRACT.replace(",\"history\":[]", history) + "}";
        String restarted = kept.replaceFirst("2026-10-01T00:00:00.000Z", "2026-10-27T00:00:00.000Z");
        queuedWrites.add(written(kept, "\"4\""));
        next = written(restarted, "\"5\"");

        Contract ok = client.contracts.novate("u1", change(), new NovationOptions().keepBillingPeriod(true));
        assertThat(ok.getBillingPeriod().getStartDate().toInstant())
                .isEqualTo(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        assertThatThrownBy(() -> client.contracts.novate("u1", change(), new NovationOptions().keepBillingPeriod(true)))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .satisfies(error -> assertThat(((SpaceFeatureUnsupportedException) error).isWritten()).isTrue());
        assertThat(count("PUT ")).isEqualTo(2);
    }

    @Test
    @DisplayName("previewNovation with keepBillingPeriod on an old SPACE throws; without it the preview is an answer")
    void previewKeepBillingPeriodOnOldSpace() {
        next = json(200, PREVIEW);

        assertThatThrownBy(() -> client.contracts.previewNovation("u1", change(), true))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .satisfies(error -> {
                    SpaceFeatureUnsupportedException e = (SpaceFeatureUnsupportedException) error;
                    assertThat(e.getCapability()).isEqualTo("keep-billing-period");
                    assertThat(e.isWritten()).isFalse();
                });
        assertThat(client.contracts.previewNovation("u1", change()).isValid()).isFalse();
    }
}
