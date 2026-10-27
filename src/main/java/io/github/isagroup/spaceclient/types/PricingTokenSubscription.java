package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Claim {@code subscription} of a pricing token: the subscription of the contract the token was
 * issued for. Lets the holder of a token know the contracted versions, plans and add-ons, and the
 * {@code contractVersion} to send with a change, without calling SPACE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PricingTokenSubscription extends Subscription {

    @JsonProperty("contractVersion")
    private String contractVersion;

    public PricingTokenSubscription() {
        super();
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    @Override
    public String toString() {
        return "PricingTokenSubscription{contractedServices=" + getContractedServices()
                + ", subscriptionPlans=" + getSubscriptionPlans()
                + ", subscriptionAddOns=" + getSubscriptionAddOns()
                + ", contractVersion=" + contractVersion + '}';
    }
}
