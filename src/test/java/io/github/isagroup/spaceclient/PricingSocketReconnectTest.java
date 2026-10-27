package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code disconnect()} followed by {@code connect()} against a minimal Socket.IO server (Engine.IO
 * 4 over WebSocket on MockWebServer, the {@code /events} path and {@code /pricings} namespace SPACE
 * uses): {@code synchronized} and the pricing events must keep reaching the listeners, once each.
 */
@DisplayName("SpaceClient disconnect/connect cycles")
class PricingSocketReconnectTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private FakePricingSocketServer server;
    private SpaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakePricingSocketServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    @Test
    @DisplayName("synchronized and pricing events still fire after several disconnect/connect cycles, once each")
    void eventsSurviveDisconnectConnectCycles() {
        AtomicInteger synchronizedCount = new AtomicInteger();
        List<Object> created = new CopyOnWriteArrayList<>();
        client = newClient();
        client.on("synchronized", data -> synchronizedCount.incrementAndGet());
        client.on("pricing_created", created::add);
        client.connect();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(synchronizedCount).hasValue(1));
        server.emitPricing("PRICING_CREATED", "{\"serviceName\":\"Zoom\",\"pricingVersion\":\"v1\"}");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(created).hasSize(1));

        for (int cycle = 1; cycle <= 3; cycle++) {
            reconnect();
            int expected = cycle + 1;
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(synchronizedCount).hasValue(expected));
            server.emitPricing("PRICING_CREATED", "{\"serviceName\":\"Zoom\",\"pricingVersion\":\"v" + expected + "\"}");
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(created).hasSize(expected));
        }

        // No handler was registered twice: nothing more arrives.
        await().during(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(synchronizedCount).hasValue(4);
            assertThat(created).hasSize(4);
        });
        assertThat(created).extracting(Object::toString)
                .allSatisfy(details -> assertThat(details).contains("\"serviceName\":\"Zoom\""));
    }

    @Test
    @DisplayName("a listener registered while disconnected receives events after connect()")
    void listenerRegisteredWhileDisconnected() {
        AtomicInteger synchronizedCount = new AtomicInteger();
        List<Object> archived = new CopyOnWriteArrayList<>();
        client = newClient();
        client.on("synchronized", data -> synchronizedCount.incrementAndGet());
        client.connect();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(synchronizedCount).hasValue(1));

        client.disconnect();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(server.closedConnections()).isEqualTo(1));
        client.on("pricing_archived", archived::add);
        client.connect();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(synchronizedCount).hasValue(2));
        server.emitPricing("PRICING_ARCHIVED", "{\"serviceName\":\"Zoom\",\"pricingVersion\":\"v1\"}");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(archived).hasSize(1));
    }

    /** disconnect(), wait until the server sees the connection close, then connect(). */
    private void reconnect() {
        int closedBefore = server.closedConnections();
        client.disconnect();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(server.closedConnections()).isGreaterThan(closedBefore));
        client.connect();
    }

    private SpaceClient newClient() {
        return new SpaceClient(new SpaceConnectionOptions(server.url(), "test-api-key"));
    }

    /**
     * Speaks just enough Engine.IO 4 / Socket.IO 5 for the client: the open packet, the
     * {@code /pricings} namespace connection, and {@code message} events.
     */
    private static final class FakePricingSocketServer implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicInteger sessions = new AtomicInteger();
        private final Set<WebSocket> closed = ConcurrentHashMap.newKeySet();
        private volatile WebSocket pricings;

        FakePricingSocketServer() throws IOException {
            server.setDispatcher(new Dispatcher() {
                @NotNull
                @Override
                public MockResponse dispatch(@NotNull RecordedRequest request) {
                    String path = request.getPath();
                    if (path != null && path.startsWith("/events/") && path.contains("transport=websocket")) {
                        return new MockResponse().withWebSocketUpgrade(new Session());
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();
        }

        String url() {
            String url = server.url("/").toString();
            return url.substring(0, url.length() - 1);
        }

        int closedConnections() {
            return closed.size();
        }

        void emitPricing(String code, String detailsJson) {
            WebSocket socket = pricings;
            assertThat(socket).as("connected /pricings namespace").isNotNull();
            socket.send("42/pricings,[\"message\",{\"code\":\"" + code + "\",\"details\":" + detailsJson + "}]");
        }

        @Override
        public void close() throws IOException {
            server.shutdown();
        }

        private final class Session extends WebSocketListener {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                webSocket.send("0{\"sid\":\"engine-" + sessions.incrementAndGet()
                        + "\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}");
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                if (text.startsWith("40/pricings,")) {
                    pricings = webSocket;
                    webSocket.send("40/pricings,{\"sid\":\"pricings-" + sessions.get() + "\"}");
                } else if (text.startsWith("41/pricings,")) {
                    pricings = null;
                }
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                webSocket.close(1000, null);
                closed.add(webSocket);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                closed.add(webSocket);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                closed.add(webSocket);
            }
        }
    }
}
