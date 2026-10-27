package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** The per-client cache of {@code X-Space-Capabilities} (API design S0). */
@DisplayName("Capability cache")
class CapabilitiesTest {

    private SpaceClient client;
    private final long[] now = {1_000_000L};

    @BeforeEach
    void setUp() {
        client = new SpaceClient(new SpaceConnectionOptions("http://localhost:1", "org-key"));
        client.setClockForTesting(() -> now[0]);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    @DisplayName("Unknown until a response is seen; tokens are trimmed and lower-cased")
    void parsesTokens() {
        assertThat(client.getCachedCapabilities()).isNull();

        client.observeCapabilities(" Contract-Version,keep-billing-period ,, pricing-yaml", true);

        assertThat(client.getCachedCapabilities())
                .containsExactlyInAnyOrder("contract-version", "keep-billing-period", "pricing-yaml");
    }

    @Test
    @DisplayName("A success without the header means none; a refusal without it is ignored")
    void missingHeader() {
        client.observeCapabilities("contract-version", true);
        client.observeCapabilities(null, false);
        assertThat(client.getCachedCapabilities()).containsExactly("contract-version");

        client.observeCapabilities(null, true);
        assertThat(client.getCachedCapabilities()).isEmpty();
    }

    @Test
    @DisplayName("Trusted for CAPABILITIES_TTL_MS, then asked again; clearCapabilities forgets them")
    void expires() {
        client.observeCapabilities("contract-version", true);
        now[0] += SpaceClient.CAPABILITIES_TTL_MS - 1;
        assertThat(client.getCachedCapabilities()).isNotNull();
        now[0] += 1;
        assertThat(client.getCachedCapabilities()).isNull();

        client.observeCapabilities("contract-version", true);
        client.clearCapabilities();
        assertThat(client.getCachedCapabilities()).isNull();
    }
}
