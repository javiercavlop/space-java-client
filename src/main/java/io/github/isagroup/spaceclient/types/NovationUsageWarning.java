package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A usage limit that would end up below current consumption. Not an error: SPACE reports it so the
 * host can warn the customer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NovationUsageWarning {

    @JsonProperty("service")
    private String service;

    @JsonProperty("usageLimit")
    private String usageLimit;

    @JsonProperty("consumed")
    private double consumed;

    @JsonProperty("newLimit")
    private double newLimit;

    public NovationUsageWarning() {
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(String usageLimit) {
        this.usageLimit = usageLimit;
    }

    public double getConsumed() {
        return consumed;
    }

    public void setConsumed(double consumed) {
        this.consumed = consumed;
    }

    public double getNewLimit() {
        return newLimit;
    }

    public void setNewLimit(double newLimit) {
        this.newLimit = newLimit;
    }
}
