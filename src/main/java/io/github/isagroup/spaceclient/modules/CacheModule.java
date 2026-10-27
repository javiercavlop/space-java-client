package io.github.isagroup.spaceclient.modules;

import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.cache.CacheProvider;
import io.github.isagroup.spaceclient.cache.CacheProviderFactory;
import io.github.isagroup.spaceclient.types.CacheOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal cache module for SpaceClient
 * This module provides caching functionality that can only be accessed
 * by SpaceClient and its internal modules
 */
public class CacheModule {
    private static final Logger logger = LoggerFactory.getLogger(CacheModule.class);
    
    private final SpaceClient spaceClient;
    private CacheProvider provider;
    private boolean enabled;
    private final String keyPrefix = "space-client:";

    /**
     * Creates an instance of the CacheModule class
     * This constructor is internal and should only be called by SpaceClient
     */
    public CacheModule(SpaceClient spaceClient) {
        this.spaceClient = spaceClient;
        this.enabled = false;
    }

    /**
     * Initialize the cache with the provided options
     * This method is internal and should only be called by SpaceClient
     */
    public void initialize(CacheOptions options) {
        if (!options.isEnabled()) {
            this.enabled = false;
            return;
        }

        try {
            CacheProviderFactory.validate(options);
            this.provider = CacheProviderFactory.create(options);
            this.enabled = true;
        } catch (Exception e) {
            logger.error("[CacheModule] Failed to initialize cache", e);
            throw new RuntimeException("Cache initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Check if caching is enabled and available
     */
    public boolean isEnabled() {
        return enabled && provider != null;
    }

    /**
     * Get a value from the cache
     */
    public <T> T get(String key, Class<T> type) {
        if (!isEnabled()) {
            return null;
        }

        try {
            return provider.get(getFullKey(key), type);
        } catch (Exception e) {
            logger.error("[CacheModule] Error getting cached value", e);
            return null;
        }
    }

    /**
     * Set a value in the cache
     */
    public <T> void set(String key, T value, Integer ttl) {
        if (!isEnabled()) {
            return;
        }

        try {
            provider.set(getFullKey(key), value, ttl);
        } catch (Exception e) {
            logger.error("[CacheModule] Error setting cached value", e);
            // Don't throw error to avoid breaking the main functionality
        }
    }

    /**
     * Set a value in the cache with default TTL
     */
    public <T> void set(String key, T value) {
        set(key, value, null);
    }

    /**
     * Delete a value from the cache
     */
    public void delete(String key) {
        if (!isEnabled()) {
            return;
        }

        try {
            provider.delete(getFullKey(key));
        } catch (Exception e) {
            logger.error("[CacheModule] Error deleting cached value", e);
        }
    }

    /**
     * Check if a key exists in the cache
     */
    public boolean has(String key) {
        if (!isEnabled()) {
            return false;
        }

        try {
            return provider.has(getFullKey(key));
        } catch (Exception e) {
            logger.error("[CacheModule] Error checking cached value", e);
            return false;
        }
    }

    /**
     * Clear all cache entries
     */
    public void clear() {
        if (!isEnabled()) {
            return;
        }

        try {
            provider.clear();
        } catch (Exception e) {
            logger.error("[CacheModule] Error clearing cache", e);
        }
    }

    /**
     * Get all keys matching a pattern
     */
    public List<String> keys(String pattern) {
        if (!isEnabled()) {
            return new ArrayList<>();
        }

        try {
            String fullPattern = pattern != null ? keyPrefix + pattern : keyPrefix + "*";
            List<String> keys = provider.keys(fullPattern);
            // Remove the prefix from the returned keys
            List<String> result = new ArrayList<>();
            for (String key : keys) {
                if (key.startsWith(keyPrefix)) {
                    result.add(key.substring(keyPrefix.length()));
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("[CacheModule] Error getting cache keys", e);
            return new ArrayList<>();
        }
    }

    /**
     * Generate a cache key for contracts
     */
    public String getContractKey(String userId) {
        return "contract:" + userId;
    }

    /**
     * Generate a cache key for features
     */
    public String getFeatureKey(String userId, String featureName) {
        return "feature:" + userId + ":" + featureName;
    }

    /**
     * Generate a cache key for subscriptions
     */
    public String getSubscriptionKey(String userId) {
        return "subscription:" + userId;
    }

    /**
     * Generate a cache key for pricing tokens
     */
    public String getPricingTokenKey(String userId) {
        return "pricing-token:" + userId;
    }

    /**
     * Invalidate all cache entries for a specific user
     */
    public void invalidateUser(String userId) {
        if (!isEnabled()) {
            return;
        }

        try {
            String[] patterns = {
                "contract:" + userId,
                "feature:" + userId + ":*",
                "subscription:" + userId,
                "pricing-token:" + userId
            };

            for (String pattern : patterns) {
                List<String> keys = keys(pattern);
                for (String key : keys) {
                    delete(key);
                }
            }
        } catch (Exception e) {
            logger.error("[CacheModule] Error invalidating user cache", e);
        }
    }

    /**
     * Close the cache provider
     */
    /**
     * Invalidate the cache entries of every user (contracts, evaluations, subscriptions, pricing
     * tokens), e.g. after contract events may have been missed.
     */
    public void invalidateAllUsers() {
        if (!isEnabled()) {
            return;
        }

        try {
            for (String pattern : new String[]{"contract:*", "feature:*", "subscription:*", "pricing-token:*"}) {
                for (String key : keys(pattern)) {
                    delete(key);
                }
            }
        } catch (Exception e) {
            logger.error("[CacheModule] Error invalidating users cache", e);
        }
    }

    public void close() {
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                logger.error("[CacheModule] Error closing cache provider", e);
            }
        }
    }

    /**
     * Get the full key with prefix
     */
    private String getFullKey(String key) {
        return keyPrefix + key;
    }
}
