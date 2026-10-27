package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * User contract information
 */
public class Contract {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("_id")
    private String _id;

    @JsonProperty("userContact")
    private UserContact userContact;
    
    @JsonProperty("billingPeriod")
    private BillingPeriod billingPeriod;
    
    @JsonProperty("organizationId")
    private String organizationId;

    @JsonProperty("groupId")
    private String groupId;
    
    @JsonProperty("usageLevels")
    private Map<String, Map<String, UsageLevel>> usageLevels;
    
    @JsonProperty("contractedServices")
    private Map<String, String> contractedServices;
    
    @JsonProperty("subscriptionPlans")
    private Map<String, String> subscriptionPlans;
    
    @JsonProperty("subscriptionAddOns")
    private Map<String, Map<String, Integer>> subscriptionAddOns;
    
    @JsonProperty("history")
    private List<ContractHistoryEntry> history;

    @JsonProperty("contractVersion")
    private String contractVersion;
    

    public Contract() {
    }

    // Getters and Setters
    public UserContact getUserContact() {
        return userContact;
    }

    public void setUserContact(UserContact userContact) {
        this.userContact = userContact;
    }

    public BillingPeriod getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(BillingPeriod billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    public Map<String, Map<String, UsageLevel>> getUsageLevels() {
        return usageLevels;
    }

    public void setUsageLevels(Map<String, Map<String, UsageLevel>> usageLevels) {
        this.usageLevels = usageLevels;
    }

    public Map<String, String> getContractedServices() {
        return contractedServices;
    }

    public void setContractedServices(Map<String, String> contractedServices) {
        this.contractedServices = contractedServices;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Map<String, String> getSubscriptionPlans() {
        return subscriptionPlans;
    }

    public void setSubscriptionPlans(Map<String, String> subscriptionPlans) {
        this.subscriptionPlans = subscriptionPlans;
    }

    public Map<String, Map<String, Integer>> getSubscriptionAddOns() {
        return subscriptionAddOns;
    }

    public void setSubscriptionAddOns(Map<String, Map<String, Integer>> subscriptionAddOns) {
        this.subscriptionAddOns = subscriptionAddOns;
    }

    public List<ContractHistoryEntry> getHistory() {
        return history;
    }

    public void setHistory(List<ContractHistoryEntry> history) {
        this.history = history;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    /**
     * Opaque version of the contract (also sent as the {@code ETag} header). Pass it as the expected
     * version of a novation to reject concurrent changes. {@code null} on SPACE versions without
     * optimistic concurrency.
     */
    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getUserId() {
        return userContact != null ? userContact.getUserId() : null;
    }

    public String toString() {
        return "Contract{" +
                "userContact=" + userContact +
                ", billingPeriod=" + billingPeriod +
                ", usageLevels=" + usageLevels +
                ", contractedServices=" + contractedServices +
                ", subscriptionPlans=" + subscriptionPlans +
                ", subscriptionAddOns=" + subscriptionAddOns +
                ", history=" + history +
                ", contractVersion=" + contractVersion +
                '}';
    }
}
