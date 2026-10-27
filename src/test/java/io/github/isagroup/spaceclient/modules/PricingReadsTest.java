package io.github.isagroup.spaceclient.modules;

import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.PricingYamlUnavailableException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceConnectionException;
import io.github.isagroup.spaceclient.exceptions.SpaceException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.ContractPricings;
import io.github.isagroup.spaceclient.types.ContractServicePricing;
import io.github.isagroup.spaceclient.types.NewContractsPricing;
import io.github.isagroup.spaceclient.types.PricingYaml;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Pricing reads for a pricing page (API design S2, S3, S13), against a MockWebServer.
 */
@DisplayName("Pricing reads (YAML of a version, contract pricings, new-contracts pricing)")
class PricingReadsTest {

    private static final String YAML = "syntaxVersion: '3.0'\n"
            + "saasName: Acme\n"
            + "version: '2024-06-08'\n"
            + "# comments and key order survive: the file is returned byte for byte\n"
            + "plans:\n  growth:\n    price: 20\n";
    private static final String ETAG = "\"5d41402abc4b2a76b9719d911017c592\"";

    private MockWebServer server;
    private SpaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        // Answer before the client exists: its constructor opens the socket.io handshake, and the
        // default QueueDispatcher would block that request forever (and MockWebServer.shutdown()
        // with it) if it arrived before a test called respond().
        respond(new MockResponse().setResponseCode(500).setBody("{\"error\":\"no answer set\"}"));
        server.start();
        client = new SpaceClient(new SpaceConnectionOptions(server.url("/").toString(), "org-key"));
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    /** The next request to the HTTP API, skipping the socket.io handshakes the client opens. */
    private RecordedRequest nextApiRequest() throws InterruptedException {
        while (true) {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).as("an API request").isNotNull();
            if (request.getPath().startsWith("/api/v1")) {
                return request;
            }
        }
    }

    private void respond(MockResponse response) {
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/api/v1")) {
                    return new MockResponse().setResponseCode(404);
                }
                return response;
            }
        });
    }

    // -- S2 ---------------------------------------------------------------

    @Test
    @DisplayName("getYaml returns the file byte for byte with its ETag and syntax version")
    void getYamlReturnsTheFile() throws Exception {
        respond(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/yaml; charset=utf-8")
                .setHeader("ETag", ETAG)
                .setHeader("X-Pricing-Syntax-Version", "3.0")
                .setBody(YAML));

        PricingYaml result = client.pricings.getYaml("acme", "2024-06-08");

        assertThat(result.getYaml()).isEqualTo(YAML);
        assertThat(result.getEtag()).isEqualTo(ETAG);
        assertThat(result.getSyntaxVersion()).isEqualTo("3.0");
        assertThat(result.isNotModified()).isFalse();

        RecordedRequest request = nextApiRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/services/acme/pricings/2024-06-08?format=yaml");
        assertThat(request.getHeader("x-api-key")).isEqualTo("org-key");
        assertThat(request.getHeader("If-None-Match")).isNull();
    }

    @Test
    @DisplayName("getYaml with a known ETag is a conditional request (304 -> notModified)")
    void getYamlConditional() throws Exception {
        respond(new MockResponse().setResponseCode(304).setHeader("ETag", ETAG));

        // Unquoted etags are quoted, so callers can pass either form.
        PricingYaml result = client.pricings.getYaml("acme", "2024-06-08", ETAG.replace("\"", ""));

        assertThat(nextApiRequest().getHeader("If-None-Match")).isEqualTo(ETAG);
        assertThat(result.isNotModified()).isTrue();
        assertThat(result.getYaml()).isNull();
        assertThat(result.getEtag()).isEqualTo(ETAG);
    }

    @Test
    @DisplayName("getYaml escapes path segments")
    void getYamlEscapesSegments() throws Exception {
        respond(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/yaml").setBody("x: 1\n"));

        PricingYaml result = client.pricings.getYaml("my svc", "v1/2");

        assertThat(nextApiRequest().getPath()).isEqualTo("/api/v1/services/my%20svc/pricings/v1%2F2?format=yaml");
        assertThat(result.getYaml()).isEqualTo("x: 1\n");
        assertThat(result.getEtag()).isNull();
        assertThat(result.getSyntaxVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/yaml", "text/yaml; charset=utf-8", "application/x-yaml"})
    @DisplayName("getYaml accepts the YAML media types")
    void getYamlAcceptsYamlTypes(String contentType) {
        respond(new MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(YAML));

        assertThat(client.pricings.getYaml("acme", "1.0").getYaml()).isEqualTo(YAML);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=utf-8", ""})
    @DisplayName("An old SPACE that ignores format=yaml and answers JSON raises SpaceFeatureUnsupportedException")
    void getYamlFromOldSpace(String contentType) {
        // A SPACE without S2 answers 200 with the JSON pricing; it must not be handed out as YAML.
        MockResponse response = new MockResponse().setResponseCode(200).setBody("{\"saasName\":\"Acme\"}");
        if (contentType.isEmpty()) {
            response.removeHeader("Content-Type");
        } else {
            response.setHeader("Content-Type", contentType);
        }
        respond(response);

        assertThatThrownBy(() -> client.pricings.getYaml("acme", "1.0"))
                .isInstanceOf(SpaceFeatureUnsupportedException.class)
                .isInstanceOf(SpaceException.class)
                .isNotInstanceOf(SpaceApiException.class)
                .hasMessageContaining("format=yaml")
                .satisfies(error -> {
                    SpaceFeatureUnsupportedException e = (SpaceFeatureUnsupportedException) error;
                    assertThat(e.getCapability()).isEqualTo("pricing-yaml");
                    assertThat(e.getCode()).isEqualTo("SPACE_FEATURE_UNSUPPORTED");
                    assertThat(e.isWritten()).isFalse();
                    assertThat(e.getContract()).isNull();
                });
    }

    @Test
    @DisplayName("PRICING_YAML_UNAVAILABLE raises PricingYamlUnavailableException")
    void getYamlUnavailable() {
        respond(new MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"PRICING_YAML_UNAVAILABLE\"}"));

        assertThatThrownBy(() -> client.pricings.getYaml("acme", "2019"))
                .isInstanceOf(PricingYamlUnavailableException.class)
                .isInstanceOf(SpaceApiException.class)
                .satisfies(error -> {
                    PricingYamlUnavailableException e = (PricingYamlUnavailableException) error;
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getServiceName()).isEqualTo("acme");
                    assertThat(e.getPricingVersion()).isEqualTo("2019");
                });
    }

    @Test
    @DisplayName("An unknown version is a plain SpaceApiException with the body")
    void getYamlUnknownVersion() {
        respond(new MockResponse().setResponseCode(404).setBody("{\"error\":\"Pricing not found\"}"));

        assertThatThrownBy(() -> client.pricings.getYaml("acme", "nope"))
                .isExactlyInstanceOf(SpaceApiException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("Pricing not found");
    }

    // -- S3 ---------------------------------------------------------------

    @Test
    @DisplayName("getPricings parses every service of the contract and the contractVersion")
    void getPricings() throws Exception {
        respond(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"services\":{"
                        + "\"acme\":{\"version\":\"2024-06-08\",\"status\":\"archived\","
                        + "\"yamlUrl\":\"/api/v1/services/acme/pricings/2024-06-08?format=yaml\","
                        + "\"newContractsVersion\":\"2025-01-10\",\"plan\":\"growth\",\"addOns\":{\"extraStorage\":2}},"
                        + "\"beta\":{\"version\":\"1.0\",\"status\":\"active\","
                        + "\"yamlUrl\":\"/api/v1/services/beta/pricings/1.0?format=yaml\",\"newContractsVersion\":null}},"
                        + "\"contractVersion\":\"3:2026-10-27T10:00:00.000Z\",\"somethingNew\":true}"));

        ContractPricings result = client.contracts.getPricings("user/1");

        assertThat(nextApiRequest().getPath()).isEqualTo("/api/v1/contracts/user%2F1/pricings");
        assertThat(result.getContractVersion()).isEqualTo("3:2026-10-27T10:00:00.000Z");
        ContractServicePricing acme = result.getServices().get("acme");
        assertThat(acme.getVersion()).isEqualTo("2024-06-08");
        assertThat(acme.getStatus()).isEqualTo("archived");
        assertThat(acme.isArchived()).isTrue();
        assertThat(acme.getYamlUrl()).endsWith("?format=yaml");
        assertThat(acme.getNewContractsVersion()).isEqualTo("2025-01-10");
        assertThat(acme.getPlan()).isEqualTo("growth");
        assertThat(acme.getAddOns()).isEqualTo(Map.of("extraStorage", 2));
        ContractServicePricing beta = result.getServices().get("beta");
        assertThat(beta.getNewContractsVersion()).isNull();
        assertThat(beta.getPlan()).isNull();
        assertThat(beta.isArchived()).isFalse();
    }

    @Test
    @DisplayName("getPricings of a contract outside the organisation throws with status 404")
    void getPricingsNotFound() {
        respond(new MockResponse().setResponseCode(404).setBody("{\"error\":\"Contract not found\"}"));

        assertThatThrownBy(() -> client.contracts.getPricings("other"))
                .isInstanceOf(SpaceApiException.class)
                .satisfies(error -> assertThat(((SpaceApiException) error).getStatusCode()).isEqualTo(404));
    }

    // -- S13 --------------------------------------------------------------

    @Test
    @DisplayName("getNewContractsPricing returns the version, its YAML path and the source")
    void getNewContractsPricing() throws Exception {
        respond(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"version\":\"2025-01-10\","
                        + "\"yamlUrl\":\"/api/v1/services/acme/pricings/2025-01-10?format=yaml\","
                        + "\"source\":\"sphere-policy\"}"));

        NewContractsPricing result = client.services.getNewContractsPricing("acme");

        assertThat(nextApiRequest().getPath()).isEqualTo("/api/v1/services/acme/pricings/new-contracts");
        assertThat(result.getVersion()).isEqualTo("2025-01-10");
        assertThat(result.getYamlUrl()).isEqualTo("/api/v1/services/acme/pricings/2025-01-10?format=yaml");
        assertThat(result.getSource()).isEqualTo("sphere-policy");
    }

    @Test
    @DisplayName("An unreachable SPACE is reported as a SpaceConnectionException")
    void unreachable() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.services.getNewContractsPricing("acme"))
                .isInstanceOf(SpaceConnectionException.class)
                .isInstanceOf(SpaceException.class)
                .isNotInstanceOf(SpaceApiException.class)
                .hasMessageContaining("Could not reach SPACE")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("An answer that is not the expected JSON is a SpaceException, not a parse error")
    void unparsableAnswer() {
        respond(new MockResponse().setResponseCode(200).setBody("<html>proxy</html>"));

        assertThatThrownBy(() -> client.services.getNewContractsPricing("acme"))
                .isExactlyInstanceOf(SpaceException.class)
                .hasMessageContaining("Unexpected answer from SPACE");
    }
}
