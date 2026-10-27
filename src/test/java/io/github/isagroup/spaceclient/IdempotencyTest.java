package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.exceptions.IdempotencyConflictException;
import io.github.isagroup.spaceclient.exceptions.IdempotencyException;
import io.github.isagroup.spaceclient.exceptions.IdempotencyInProgressException;
import io.github.isagroup.spaceclient.exceptions.IdempotencyKeyInvalidException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceConnectionException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.ContractToCreate;
import io.github.isagroup.spaceclient.types.FeatureEvaluationResult;
import io.github.isagroup.spaceclient.types.NovationOptions;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import io.github.isagroup.spaceclient.types.Subscription;
import io.github.isagroup.spaceclient.types.UserContact;
import io.github.isagroup.spaceclient.types.WriteOptions;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Idempotency-Key, retries and revert by consumption id (SCORELabUS/space#84, #85), against a
 * MockWebServer.
 */
@DisplayName("Idempotency-Key, retries and consumption ids")
class IdempotencyTest {

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final String CAPABILITIES = "atomic-usage-limits,idempotency-key,contract-version";
    private static final String CONSUMPTION_ID = "6f1c2a5e-6a7b-4c4d-9e8f-0a1b2c3d4e5f";
    private static final String EVALUATION = "{\"eval\":true,\"used\":{\"acme-maxPets\":2},"
            + "\"limit\":{\"acme-maxPets\":4},\"error\":null,\"consumptionId\":\"" + CONSUMPTION_ID + "\"}";
    private static final String CONTRACT = "{\"userContact\":{\"userId\":\"u1\",\"username\":\"u1\"},"
            + "\"billingPeriod\":{\"startDate\":\"2026-10-01T00:00:00.000Z\",\"endDate\":\"2026-11-01T00:00:00.000Z\","
            + "\"autoRenew\":true,\"renewalDays\":30},"
            + "\"usageLevels\":{},\"contractedServices\":{\"acme\":\"2025-01-10\"},"
            + "\"subscriptionPlans\":{\"acme\":\"pro\"},\"subscriptionAddOns\":{},\"history\":[]}";
    private static final String FEATURE = "/api/v1/features/u1/acme-pets";

    private MockWebServer server;
    /** Answers per {@code "METHOD /path"} (query excluded), in order; the last one repeats. */
    private final Map<String, LinkedList<MockResponse>> answers = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    private final List<SpaceClient> clients = new ArrayList<>();
    /** {@code X-Space-Capabilities} of every answer; {@code null} for none. */
    private volatile String capabilities = CAPABILITIES;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                requests.add(request);
                String path = request.getPath().split("\\?", 2)[0];
                LinkedList<MockResponse> queue = answers.get(request.getMethod() + " " + path);
                MockResponse answer;
                if (queue == null || queue.isEmpty()) {
                    answer = new MockResponse().setResponseCode(404).setBody("{\"error\":\"no mock\"}");
                } else {
                    synchronized (queue) {
                        answer = queue.size() > 1 ? queue.removeFirst() : queue.getFirst();
                    }
                }
                if (capabilities != null) {
                    answer = answer.clone().setHeader("X-Space-Capabilities", capabilities);
                }
                return answer;
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (SpaceClient client : clients) {
            client.close();
        }
        server.shutdown();
    }

    private SpaceClient client(int retries) {
        SpaceConnectionOptions options = new SpaceConnectionOptions(server.url("/").toString(), "org-key");
        options.setRetries(retries);
        return register(options);
    }

    private SpaceClient register(SpaceConnectionOptions options) {
        SpaceClient client = new SpaceClient(options);
        client.setSleeperForTesting(sleeps::add);
        clients.add(client);
        return client;
    }

    private void on(String method, String path, MockResponse... responses) {
        LinkedList<MockResponse> queue = new LinkedList<>();
        Collections.addAll(queue, responses);
        answers.put(method + " " + path, queue);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse inProgress(String retryAfter) {
        return json(409, "{\"error\":\"still running\",\"code\":\"IDEMPOTENCY_IN_PROGRESS\"}")
                .setHeader("Retry-After", retryAfter);
    }

    private List<RecordedRequest> requestsTo(String method, String path) {
        synchronized (requests) {
            return requests.stream()
                    .filter(r -> r.getMethod().equals(method) && r.getPath().split("\\?", 2)[0].equals(path))
                    .collect(Collectors.toList());
        }
    }

    private List<String> keys(String method, String path) {
        return requestsTo(method, path).stream().map(r -> r.getHeader("Idempotency-Key")).collect(Collectors.toList());
    }

    private static void assertSingleUuid(List<String> keys) {
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).matches(UUID_V4);
    }

    private static Map<String, Number> consume(int amount) {
        Map<String, Number> consumption = new HashMap<>();
        consumption.put("acme-maxPets", amount);
        return consumption;
    }

    private static ContractToCreate contractToCreate() {
        return new ContractToCreate(new UserContact("u1", "u1"), new ContractToCreate.BillingPeriodToCreate(true, 30),
                Collections.singletonMap("acme", "2025-01-10"), null, Collections.singletonMap("acme", "pro"),
                new HashMap<>());
    }

    private static Subscription subscription() {
        return new Subscription(Collections.singletonMap("acme", "2025-01-10"),
                Collections.singletonMap("acme", "basic"), new HashMap<>());
    }

    // -- the key ------------------------------------------------------------------------------

    @Test
    @DisplayName("A consumption carries a UUID v4 key and returns its consumptionId")
    void consumptionKeyAndId() {
        on("POST", FEATURE, json(200, EVALUATION));
        SpaceClient client = client(0);

        FeatureEvaluationResult result = client.features.evaluate("u1", "acme-pets", consume(1));

        assertThat(result.getConsumptionId()).isEqualTo(CONSUMPTION_ID);
        assertSingleUuid(keys("POST", FEATURE));
    }

    @Test
    @DisplayName("Every call gets its own key")
    void newKeyPerCall() {
        on("POST", FEATURE, json(200, EVALUATION));
        SpaceClient client = client(0);

        client.features.evaluate("u1", "acme-pets", consume(1));
        client.features.evaluate("u1", "acme-pets", consume(1));

        List<String> keys = keys("POST", FEATURE);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("An evaluation that does not consume carries no key")
    void noKeyWithoutConsumption() {
        on("POST", FEATURE, json(200, "{\"eval\":true,\"used\":{},\"limit\":{},\"error\":null}"));
        SpaceClient client = client(0);

        FeatureEvaluationResult result = client.features.evaluate("u1", "acme-pets");

        assertThat(keys("POST", FEATURE)).containsExactly((String) null);
        assertThat(result.getConsumptionId()).isNull();
    }

    @Test
    @DisplayName("The caller's key is sent verbatim")
    void callersKey() {
        on("POST", FEATURE, json(200, EVALUATION));
        SpaceClient client = client(0);

        client.features.evaluate("u1", "acme-pets", consume(1), WriteOptions.withIdempotencyKey("order-42:pets"));

        assertThat(keys("POST", FEATURE)).containsExactly("order-42:pets");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    @DisplayName("A key that is not 1 to 255 characters is rejected before anything is sent")
    void invalidKey(int length) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < length; i++) {
            key.append('k');
        }
        assertThatThrownBy(() -> WriteOptions.withIdempotencyKey(key.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NovationOptions().idempotencyKey(key.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("A caller's key is refused without writing when SPACE does not support it")
    void callersKeyNeedsCapability() {
        capabilities = "atomic-usage-limits";
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        on("POST", FEATURE, json(200, EVALUATION));
        SpaceClient client = client(0);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1),
                WriteOptions.withIdempotencyKey("order-42")))
                .isInstanceOfSatisfying(SpaceFeatureUnsupportedException.class, e -> {
                    assertThat(e.getCapability()).isEqualTo("idempotency-key");
                    assertThat(e.isWritten()).isFalse();
                });
        assertThat(requestsTo("POST", FEATURE)).isEmpty();
    }

    // -- retries ------------------------------------------------------------------------------

    @Test
    @DisplayName("No retries by default")
    void noRetriesByDefault() {
        on("POST", FEATURE, json(503, "{\"error\":\"down\"}"));
        SpaceClient client = client(0);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOfSatisfying(SpaceApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(503));
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
    }

    @Test
    @DisplayName("A 5xx is retried with the same key")
    void retries5xx() {
        on("POST", FEATURE, json(502, "{\"error\":\"bad gateway\"}"), json(200, EVALUATION));
        SpaceClient client = client(2);

        FeatureEvaluationResult result = client.features.evaluate("u1", "acme-pets", consume(1));

        assertThat(result.getConsumptionId()).isEqualTo(CONSUMPTION_ID);
        List<String> keys = keys("POST", FEATURE);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).matches(UUID_V4);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
    }

    @Test
    @DisplayName("A dropped connection is retried with the same key and body")
    void retriesDroppedConnection() {
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        on("POST", FEATURE, new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                json(200, EVALUATION));
        SpaceClient client = client(2);

        client.features.evaluate("u1", "acme-pets", consume(1), WriteOptions.withIdempotencyKey("order-7"));

        // OkHttp may also retry the dropped connection on its own, with the same headers.
        List<RecordedRequest> attempts = requestsTo("POST", FEATURE);
        assertThat(attempts).isNotEmpty();
        assertThat(attempts).extracting(r -> r.getHeader("Idempotency-Key")).containsOnly("order-7");
        assertThat(attempts).extracting(r -> r.getBody().clone().readUtf8()).containsOnly("{\"acme-maxPets\":1}");
    }

    @Test
    @DisplayName("No retries against a SPACE that does not advertise idempotency-key")
    void noRetriesWithoutCapability() {
        capabilities = "contract-version";
        on("POST", FEATURE, json(503, "{\"error\":\"down\"}"));
        SpaceClient client = client(3);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOf(SpaceApiException.class);
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
    }

    @Test
    @DisplayName("Retry-After is honoured on 409 IDEMPOTENCY_IN_PROGRESS and the key kept")
    void retryAfter() {
        on("POST", FEATURE, inProgress("1"), json(200, EVALUATION).setHeader("Idempotent-Replayed", "true"));
        SpaceClient client = client(2);

        FeatureEvaluationResult result = client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions());

        assertThat(result.getConsumptionId()).isEqualTo(CONSUMPTION_ID);
        assertThat(sleeps).containsExactly(1000L);
        List<String> keys = keys("POST", FEATURE);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
    }

    @Test
    @DisplayName("IdempotencyInProgressException once the retries are spent")
    void inProgressExhausted() {
        on("POST", FEATURE, inProgress("1"));
        SpaceClient client = client(2);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOfSatisfying(IdempotencyInProgressException.class, e -> {
                    assertThat(e).isInstanceOf(IdempotencyException.class).isInstanceOf(SpaceApiException.class);
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(1);
                    assertThat(e.getIdempotencyKey()).matches(UUID_V4);
                });
        assertThat(requestsTo("POST", FEATURE)).hasSize(3);
        assertThat(sleeps).containsExactly(1000L, 1000L);
    }

    @Test
    @DisplayName("A Retry-After longer than retryMaxDelayMs ends the retries")
    void retryAfterTooLong() {
        on("POST", FEATURE, inProgress("120"));
        SpaceConnectionOptions options = new SpaceConnectionOptions(server.url("/").toString(), "org-key");
        options.setRetries(2);
        options.setRetryMaxDelayMs(5000L);
        SpaceClient client = register(options);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOfSatisfying(IdempotencyInProgressException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(120));
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("Backoff is exponential and bounded by retryMaxDelayMs")
    void backoff() {
        on("POST", FEATURE, json(500, "{\"error\":\"boom\"}"));
        SpaceConnectionOptions options = new SpaceConnectionOptions(server.url("/").toString(), "org-key");
        options.setRetries(4);
        options.setRetryBaseDelayMs(100L);
        options.setRetryMaxDelayMs(300L);
        SpaceClient client = register(options);
        client.setRandomForTesting(() -> 0.999999);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOf(SpaceApiException.class);

        assertThat(requestsTo("POST", FEATURE)).hasSize(5);
        assertThat(sleeps).containsExactly(100L, 200L, 300L, 300L);
    }

    @Test
    @DisplayName("409 IDEMPOTENCY_CONFLICT is typed and not retried")
    void conflict() {
        on("POST", FEATURE, json(409, "{\"error\":\"Idempotency-Key reused\",\"code\":\"IDEMPOTENCY_CONFLICT\"}"));
        SpaceClient client = client(3);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(2),
                WriteOptions.withIdempotencyKey("order-1")))
                .isInstanceOfSatisfying(IdempotencyConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getIdempotencyKey()).isEqualTo("order-1");
                });
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
    }

    @Test
    @DisplayName("400 IDEMPOTENCY_KEY_INVALID is typed")
    void keyInvalid() {
        on("POST", FEATURE, json(400, "{\"error\":\"bad key\",\"code\":\"IDEMPOTENCY_KEY_INVALID\"}"));
        SpaceClient client = client(3);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOf(IdempotencyKeyInvalidException.class);
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
    }

    @Test
    @DisplayName("Other 4xx are never retried")
    void other4xx() {
        on("POST", FEATURE, json(409, "{\"error\":\"The contract kept changing\"}"));
        on("POST", "/api/v1/contracts", json(400, "{\"error\":\"Invalid plan\"}"));
        SpaceClient client = client(3);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isExactlyInstanceOf(SpaceApiException.class);
        assertThatThrownBy(() -> client.contracts.addContract(contractToCreate(), new WriteOptions()))
                .isExactlyInstanceOf(SpaceApiException.class);
        assertThat(requestsTo("POST", FEATURE)).hasSize(1);
        assertThat(requestsTo("POST", "/api/v1/contracts")).hasSize(1);
    }

    @Test
    @DisplayName("Requests without a key are never retried")
    void unkeyedNotRetried() {
        on("GET", "/api/v1/contracts/u1/pricings", json(503, "{\"error\":\"down\"}"));
        SpaceClient client = client(3);

        assertThatThrownBy(() -> client.contracts.getPricings("u1")).isInstanceOf(SpaceApiException.class);
        assertThat(requestsTo("GET", "/api/v1/contracts/u1/pricings")).hasSize(1);
    }

    @Test
    @DisplayName("The legacy evaluate keeps reporting failures as an evaluation error")
    void legacyEvaluateReportsFailures() {
        on("POST", FEATURE, inProgress("1"));
        SpaceClient client = client(0);

        FeatureEvaluationResult result = client.features.evaluate("u1", "acme-pets", consume(1));

        assertThat(result.getEval()).isFalse();
        assertThat(result.getError().getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    @DisplayName("retries outside 0..10 is rejected")
    void invalidRetries() {
        SpaceConnectionOptions options = new SpaceConnectionOptions(server.url("/").toString(), "org-key");
        options.setRetries(11);
        assertThatThrownBy(() -> new SpaceClient(options)).isInstanceOf(IllegalArgumentException.class);
        options.setRetries(-1);
        assertThatThrownBy(() -> new SpaceClient(options)).isInstanceOf(IllegalArgumentException.class);
    }

    // -- revert by consumption id -------------------------------------------------------------

    @Test
    @DisplayName("revertConsumption reverts by id with a key; true on 204")
    void revertConsumption() throws InterruptedException {
        on("POST", FEATURE, new MockResponse().setResponseCode(204));
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        SpaceClient client = client(0);

        assertThat(client.features.revertConsumption("u1", "acme-pets", CONSUMPTION_ID)).isTrue();

        RecordedRequest request = requestsTo("POST", FEATURE).get(0);
        assertThat(request.getRequestUrl().queryParameter("revert")).isEqualTo("true");
        assertThat(request.getRequestUrl().queryParameter("consumptionId")).isEqualTo(CONSUMPTION_ID);
        assertThat(request.getRequestUrl().queryParameter("latest")).isNull();
        assertThat(request.getHeader("Idempotency-Key")).matches(UUID_V4);
    }

    @Test
    @DisplayName("revertConsumption answers false on 404")
    void revertConsumptionUnknown() {
        on("POST", FEATURE, json(404, "{\"error\":\"Consumption not found\"}"));
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        SpaceClient client = client(0);

        assertThat(client.features.revertConsumption("u1", "acme-pets", "unknown")).isFalse();
    }

    @Test
    @DisplayName("revertConsumption is retried with the same key")
    void revertConsumptionRetried() {
        on("POST", FEATURE, json(503, "{\"error\":\"down\"}"), new MockResponse().setResponseCode(204));
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        SpaceClient client = client(1);

        assertThat(client.features.revertConsumption("u1", "acme-pets", "c-1",
                WriteOptions.withIdempotencyKey("undo-c-1"))).isTrue();
        assertThat(keys("POST", FEATURE)).containsExactly("undo-c-1", "undo-c-1");
    }

    @Test
    @DisplayName("revertConsumption needs atomic-usage-limits and writes nothing without it")
    void revertConsumptionNeedsCapability() {
        capabilities = "idempotency-key";
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        on("POST", FEATURE, new MockResponse().setResponseCode(204));
        SpaceClient client = client(0);

        assertThatThrownBy(() -> client.features.revertConsumption("u1", "acme-pets", "c-1"))
                .isInstanceOfSatisfying(SpaceFeatureUnsupportedException.class,
                        e -> assertThat(e.getCapability()).isEqualTo("atomic-usage-limits"));
        assertThat(requestsTo("POST", FEATURE)).isEmpty();
    }

    @Test
    @DisplayName("revertConsumption rejects an empty id")
    void revertConsumptionEmptyId() {
        SpaceClient client = client(0);
        assertThatThrownBy(() -> client.features.revertConsumption("u1", "acme-pets", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    @DisplayName("The deprecated revertEvaluation posts to the feature route with a key")
    void deprecatedRevert() {
        on("POST", FEATURE, new MockResponse().setResponseCode(204));
        SpaceClient client = client(0);

        assertThat(client.features.revertEvaluation("u1", "acme-pets", false)).isTrue();

        RecordedRequest request = requestsTo("POST", FEATURE).get(0);
        assertThat(request.getRequestUrl().queryParameter("revert")).isEqualTo("true");
        assertThat(request.getRequestUrl().queryParameter("latest")).isEqualTo("false");
        assertThat(request.getHeader("Idempotency-Key")).matches(UUID_V4);
    }

    // -- contracts ----------------------------------------------------------------------------

    @Test
    @DisplayName("addContract sends a key and reuses it on a retry")
    void addContractRetried() {
        on("POST", "/api/v1/contracts", json(504, "{\"error\":\"timeout\"}"), json(201, CONTRACT));
        SpaceClient client = client(1);

        Contract contract = client.contracts.addContract(contractToCreate());

        assertThat(contract).isNotNull();
        List<String> keys = keys("POST", "/api/v1/contracts");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).matches(UUID_V4);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
    }

    @Test
    @DisplayName("addContract with the caller's key")
    void addContractCallersKey() {
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        on("POST", "/api/v1/contracts", json(201, CONTRACT).setHeader("Idempotent-Replayed", "true"));
        SpaceClient client = client(0);

        client.contracts.addContract(contractToCreate(), WriteOptions.withIdempotencyKey("signup-u1"));

        assertThat(keys("POST", "/api/v1/contracts")).containsExactly("signup-u1");
    }

    @Test
    @DisplayName("A novation sends the key of its options and reuses it on a retry")
    void novationRetried() {
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        on("PUT", "/api/v1/contracts/u1", inProgress("1"), json(200, CONTRACT).setHeader("ETag", "\"2\""));
        SpaceClient client = client(1);

        client.contracts.novate("u1", subscription(), new NovationOptions().idempotencyKey("change-plan-u1"));

        assertThat(keys("PUT", "/api/v1/contracts/u1")).containsExactly("change-plan-u1", "change-plan-u1");
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    @DisplayName("A guarded novation keeps If-Match and the key on every attempt")
    void guardedNovationRetried() {
        on("GET", "/api/v1/contracts/u1", json(200, CONTRACT).setHeader("ETag", "\"1\""));
        on("PUT", "/api/v1/contracts/u1", json(503, "{\"error\":\"down\"}"),
                json(200, CONTRACT).setHeader("ETag", "\"2\""));
        SpaceClient client = client(1);

        client.contracts.novate("u1", subscription(), NovationOptions.expecting("1"));

        List<RecordedRequest> puts = requestsTo("PUT", "/api/v1/contracts/u1");
        assertThat(puts).extracting(r -> r.getHeader("If-Match")).containsExactly("\"1\"", "\"1\"");
        assertThat(puts.get(0).getHeader("Idempotency-Key")).matches(UUID_V4);
        assertThat(puts.get(1).getHeader("Idempotency-Key")).isEqualTo(puts.get(0).getHeader("Idempotency-Key"));
    }

    @Test
    @DisplayName("The legacy novation, group novation and usage level changes send keys")
    void legacyWritesSendKeys() {
        on("PUT", "/api/v1/contracts/u1", json(200, CONTRACT));
        on("PUT", "/api/v1/contracts", json(200, "[" + CONTRACT + "]"));
        on("PUT", "/api/v1/contracts/u1/usageLevels", json(500, "{\"error\":\"boom\"}"), json(200, CONTRACT));
        SpaceClient client = client(1);

        assertThat(client.contracts.updateContractSubscription("u1", subscription())).isNotNull();
        assertThat(client.contracts.updateContractSubscriptionByGroupId("g1", subscription())).hasSize(1);
        assertThat(client.contracts.updateContractUsageLevels("u1", "acme",
                Collections.singletonMap("maxPets", 1))).isNotNull();

        assertSingleUuid(keys("PUT", "/api/v1/contracts/u1"));
        assertSingleUuid(keys("PUT", "/api/v1/contracts"));
        List<String> levels = keys("PUT", "/api/v1/contracts/u1/usageLevels");
        assertThat(levels).hasSize(2);
        assertThat(levels.get(1)).isEqualTo(levels.get(0));
    }

    // -- capabilities -------------------------------------------------------------------------

    @Test
    @DisplayName("hasCapability reads the healthcheck once and then the cache")
    void hasCapability() {
        on("GET", "/api/v1/healthcheck", json(200, "{\"message\":\"ok\"}"));
        SpaceClient client = client(0);

        assertThat(client.hasCapability("idempotency-key")).isTrue();
        assertThat(client.hasCapability("atomic-usage-limits")).isTrue();
        assertThat(client.hasCapability("something-else")).isFalse();
        assertThat(requestsTo("GET", "/api/v1/healthcheck")).hasSize(1);
    }

    @Test
    @DisplayName("A network error that is not retried is a SpaceConnectionException")
    void connectionErrorNotRetried() throws IOException {
        SpaceConnectionOptions options = new SpaceConnectionOptions("http://127.0.0.1:1", "org-key");
        options.setRetries(2);
        SpaceClient client = register(options);

        assertThatThrownBy(() -> client.features.evaluate("u1", "acme-pets", consume(1), new WriteOptions()))
                .isInstanceOf(SpaceConnectionException.class);
        assertThat(sleeps).isEmpty();
    }
}
