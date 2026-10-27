package io.github.isagroup.spaceclient.exceptions;

/**
 * SPACE knows the pricing version but cannot hand out its original Pricing2Yaml file
 * ({@code 404 {"error": "PRICING_YAML_UNAVAILABLE"}}): historical SPHERE-linked versions may
 * survive only as parsed JSON. A {@link SpaceApiException}, so a generic handler still catches it.
 */
public class PricingYamlUnavailableException extends SpaceApiException {
    public static final String CODE = "PRICING_YAML_UNAVAILABLE";

    private final String serviceName;
    private final String pricingVersion;

    public PricingYamlUnavailableException(SpaceApiException cause, String serviceName, String pricingVersion) {
        super(cause.getStatusCode(), cause.getMethod(), cause.getPath(), cause.getBody(),
                "SPACE cannot provide the Pricing2Yaml of " + serviceName + " " + pricingVersion);
        initCause(cause);
        this.serviceName = serviceName;
        this.pricingVersion = pricingVersion;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPricingVersion() {
        return pricingVersion;
    }
}
