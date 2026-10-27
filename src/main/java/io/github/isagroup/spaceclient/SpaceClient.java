package io.github.isagroup.spaceclient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.isagroup.spaceclient.modules.CacheModule;
import io.github.isagroup.spaceclient.modules.ContractModule;
import io.github.isagroup.spaceclient.modules.FeatureModule;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import io.socket.client.IO;
import io.socket.client.Socket;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The SpaceClient class provides an interface to interact with the Space API and WebSocket services
 * It allows for HTTP requests, WebSocket connections, and event handling for real-time updates
 */
public class SpaceClient {
    private static final Logger logger = LoggerFactory.getLogger(SpaceClient.class);

    private final String httpUrl;
    private final String apiKey;
    private final int timeout;
    
    private Socket socketClient;
    private Socket pricingSocketNamespace;
    
    private final Set<String> validEvents = new HashSet<>(Arrays.asList(
            "synchronized", "pricing_created", "pricing_archived", 
            "pricing_actived", "service_disabled", "error"
    ));
    
    private final Map<String, Consumer<Object>> callbackFunctions = new HashMap<>();
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public final CacheModule cache;
    public final ContractModule contracts;
    public final FeatureModule features;

    /**
     * Constructs a new instance of the SpaceClient class
     *
     * @param options Configuration options for the client
     */
    public SpaceClient(SpaceConnectionOptions options) {
        if (options.getUrl() == null || options.getUrl().isEmpty()) {
            throw new IllegalArgumentException("URL is required");
        }
        if (options.getApiKey() == null || options.getApiKey().isEmpty()) {
            throw new IllegalArgumentException("API key is required");
        }

        String url = options.getUrl();
        this.httpUrl = url.endsWith("/") 
                ? url.substring(0, url.length() - 1) + "/api/v1" 
                : url + "/api/v1";
        this.apiKey = options.getApiKey();
        this.timeout = options.getTimeout();

        // Initialize ObjectMapper with JavaTimeModule for Date handling
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Initialize HTTP client
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        // Initialize modules
        this.cache = new CacheModule(this);
        this.contracts = new ContractModule(this, httpClient, objectMapper);
        this.features = new FeatureModule(this, httpClient, objectMapper);

        // Initialize cache if configured
        if (options.getCache() != null && options.getCache().isEnabled()) {
            initializeCache(options.getCache());
        }

        // Initialize WebSocket
        try {
            initializeWebSocket(url);
        } catch (URISyntaxException e) {
            logger.error("Failed to initialize WebSocket", e);
            throw new RuntimeException("Failed to initialize WebSocket: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize WebSocket connection
     */
    private void initializeWebSocket(String url) throws URISyntaxException {
        String wsUrl = url.replace("http://", "ws://").replace("https://", "wss://");
        
        IO.Options opts = new IO.Options();
        opts.path = "/events";
        opts.transports = new String[]{"websocket"};
        opts.reconnection = true;

        this.socketClient = IO.socket(wsUrl, opts);
        // Don't auto-connect on initialization
        this.pricingSocketNamespace = socketClient.io().socket("/pricings");
        
        configureSocket();
        // Connect manually after setup
        this.pricingSocketNamespace.connect();
    }

    /**
     * Initialize the cache with the provided options
     */
    private void initializeCache(io.github.isagroup.spaceclient.types.CacheOptions cacheOptions) {
        try {
            cache.initialize(cacheOptions);
        } catch (Exception e) {
            logger.warn("[SpaceClient] Cache initialization failed, continuing without cache", e);
        }
    }

    /**
     * Configure WebSocket event handlers
     */
    private void configureSocket() {
        pricingSocketNamespace.on(Socket.EVENT_CONNECT, args -> {
            logger.info("Connected to Space WebSocket");
            Consumer<Object> callback = callbackFunctions.get("synchronized");
            if (callback != null) {
                callback.accept(null);
            }
        });

        pricingSocketNamespace.on("message", args -> {
            if (args.length > 0) {
                JSONObject data = (JSONObject) args[0];
                String code = data.optString("code", "").toLowerCase();
                Object details = data.opt("details");
                
                Consumer<Object> callback = callbackFunctions.get(code);
                if (callback != null) {
                    callback.accept(details);
                }
            }
        });

        pricingSocketNamespace.on(Socket.EVENT_CONNECT_ERROR, args -> {
            Consumer<Object> callback = callbackFunctions.get("error");
            if (callback != null) {
                callback.accept(args.length > 0 ? args[0] : null);
            }
        });
    }

    /**
     * Checks if the client is connected to the Space API by performing a health check
     *
     * @return true if the connection is healthy, otherwise false
     */
    public boolean isConnectedToSpace() {
        Request request = new Request.Builder()
                .url(httpUrl + "/healthcheck")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                try {
                    JSONObject json = new JSONObject(responseBody);
                    return json.has("message");
                } catch (org.json.JSONException e) {
                    logger.error("Failed to parse health check response", e);
                    return false;
                }
            }
            return false;
        } catch (IOException e) {
            logger.error("Health check failed", e);
            return false;
        }
    }

    /**
     * Registers a callback function for a specific event
     *
     * @param event The name of the event to listen for
     * @param callback The function to execute when the event is triggered
     */
    public void on(String event, Consumer<Object> callback) {
        String eventLower = event.toLowerCase();
        if (validEvents.contains(eventLower)) {
            callbackFunctions.put(eventLower, callback);
        } else {
            logger.warn("No handler for event: {}", event);
        }
    }

    /**
     * Remove a specific event listener
     *
     * @param event The name of the event to remove
     */
    public void removeListener(String event) {
        String eventLower = event.toLowerCase();
        if (validEvents.contains(eventLower)) {
            callbackFunctions.remove(eventLower);
        } else {
            logger.warn("No handler to remove for event: {}", event);
        }
    }

    /**
     * Remove all event listeners
     */
    public void removeAllListeners() {
        callbackFunctions.clear();
    }

    /**
     * Establishes a connection to the pricing WebSocket namespace
     */
    public void connect() {
        if (pricingSocketNamespace != null && !pricingSocketNamespace.connected()) {
            pricingSocketNamespace.connect();
        }
    }

    /**
     * Disconnects from the pricing WebSocket namespace. The socket handlers and the listeners
     * registered with {@link #on(String, Consumer)} are kept, so {@link #connect()} resumes
     * delivering events to them.
     */
    public void disconnect() {
        if (pricingSocketNamespace != null && pricingSocketNamespace.connected()) {
            pricingSocketNamespace.disconnect();
        }
    }

    /**
     * Close all connections and cleanup resources
     */
    public void close() {
        disconnect();
        if (pricingSocketNamespace != null) {
            // The client is not reused after close(): drop the socket handlers.
            pricingSocketNamespace.off();
        }
        
        if (cache != null) {
            cache.close();
        }
        
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    // Getters
    public String getHttpUrl() {
        return httpUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getTimeout() {
        return timeout;
    }



    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
