package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The decoded payload of a pricing token returned by {@code features.generateUserPricingToken}.
 *
 * {@link #decode(String)} reads the payload WITHOUT verifying the signature: use it to read a token
 * you just received from SPACE, never to authenticate one a caller sent you.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PricingTokenPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @JsonProperty("sub")
    private String sub;

    @JsonProperty("iat")
    private Long iat;

    @JsonProperty("exp")
    private Long exp;

    @JsonProperty("features")
    private Map<String, FeatureEvaluationResult> features = new HashMap<>();

    @JsonProperty("pricingContext")
    private Map<String, Object> pricingContext;

    @JsonProperty("subscriptionContext")
    private Map<String, Object> subscriptionContext;

    @JsonProperty("subscription")
    private PricingTokenSubscription subscription;

    public PricingTokenPayload() {
    }

    /**
     * Decodes the payload of a pricing token (a JWT) without verifying its signature.
     *
     * @param token The JWT
     * @return The decoded claims
     * @throws IllegalArgumentException if the token is not a JWT with a JSON object payload
     */
    public static PricingTokenPayload decode(String token) {
        String[] parts = token != null ? token.split("\\.", -1) : new String[0];
        if (parts.length != 3) {
            throw new IllegalArgumentException("A pricing token is a JWT with three dot-separated parts");
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("The pricing token payload is not a JSON object");
            }
            return MAPPER.treeToValue(node, PricingTokenPayload.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The pricing token payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** The userId of the contract. */
    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public Long getIat() {
        return iat;
    }

    public void setIat(Long iat) {
        this.iat = iat;
    }

    public Long getExp() {
        return exp;
    }

    public void setExp(Long exp) {
        this.exp = exp;
    }

    /** Evaluation of every feature of the contract, keyed by feature id. */
    public Map<String, FeatureEvaluationResult> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, FeatureEvaluationResult> features) {
        this.features = features;
    }

    public Map<String, Object> getPricingContext() {
        return pricingContext;
    }

    public void setPricingContext(Map<String, Object> pricingContext) {
        this.pricingContext = pricingContext;
    }

    public Map<String, Object> getSubscriptionContext() {
        return subscriptionContext;
    }

    public void setSubscriptionContext(Map<String, Object> subscriptionContext) {
        this.subscriptionContext = subscriptionContext;
    }

    /** The subscription of the contract; {@code null} for tokens from SPACE versions without it. */
    public PricingTokenSubscription getSubscription() {
        return subscription;
    }

    public void setSubscription(PricingTokenSubscription subscription) {
        this.subscription = subscription;
    }
}
