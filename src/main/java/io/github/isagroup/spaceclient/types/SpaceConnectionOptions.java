package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Configuration options for connecting to the Space server
 */
public class SpaceConnectionOptions {
    
    @JsonProperty("url")
    private String url;
    
    @JsonProperty("apiKey")
    private String apiKey;
    
    @JsonProperty("timeout")
    private Integer timeout;
    
    @JsonProperty("cache")
    private CacheOptions cache;

    @JsonProperty("organizationId")
    private String organizationId;

    @JsonProperty("retries")
    private Integer retries;

    @JsonProperty("retryBaseDelayMs")
    private Long retryBaseDelayMs;

    @JsonProperty("retryMaxDelayMs")
    private Long retryMaxDelayMs;

    public SpaceConnectionOptions() {
    }

    public SpaceConnectionOptions(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
        this.timeout = 5000;
    }

    public SpaceConnectionOptions(String url, String apiKey, Integer timeout) {
        this.url = url;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    public SpaceConnectionOptions(String url, String apiKey, Integer timeout, CacheOptions cache) {
        this.url = url;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.cache = cache;
    }

    // Getters and Setters
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getTimeout() {
        return timeout != null ? timeout : 5000;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public CacheOptions getCache() {
        return cache;
    }

    public void setCache(CacheOptions cache) {
        this.cache = cache;
    }

    /**
     * The organisation of a member user key ({@code usr_...}). Sent with the handshake of the
     * authenticated {@code /contracts} events namespace ({@code {apiKey, organizationId}}), which
     * SPACE requires for user keys. Not needed for organisation keys.
     */
    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    /**
     * How many times a write carrying an {@code Idempotency-Key} (consumptions, reverts, contract
     * creation, novations, usage level changes) is retried, with the same key, after a network
     * error, a {@code 5xx} or a {@code 409 IDEMPOTENCY_IN_PROGRESS}. 0 to 10; default 0 (no
     * retries). Retries only happen while SPACE advertises {@code idempotency-key}: an older SPACE
     * would apply each attempt. Other requests are never retried.
     */
    public int getRetries() {
        return retries != null ? retries : 0;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    /**
     * Base of the exponential backoff between retries (default 250 ms): the n-th retry waits
     * between half and all of {@code retryBaseDelayMs * 2^(n-1)}, capped at
     * {@link #getRetryMaxDelayMs()}. A {@code Retry-After} from SPACE is waited exactly instead.
     */
    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs != null ? retryBaseDelayMs : 250L;
    }

    public void setRetryBaseDelayMs(Long retryBaseDelayMs) {
        this.retryBaseDelayMs = retryBaseDelayMs;
    }

    /**
     * Longest wait before a retry (default 10000 ms). A longer {@code Retry-After} ends the
     * retries: the client never waits less than SPACE asks.
     */
    public long getRetryMaxDelayMs() {
        return retryMaxDelayMs != null ? retryMaxDelayMs : 10_000L;
    }

    public void setRetryMaxDelayMs(Long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
    }
}
