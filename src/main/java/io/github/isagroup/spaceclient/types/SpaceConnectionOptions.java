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
}
