package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * The pricing version a contract holds for one of its services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractServicePricing {

    @JsonProperty("version")
    private String version;

    @JsonProperty("status")
    private String status;

    @JsonProperty("yamlUrl")
    private String yamlUrl;

    @JsonProperty("newContractsVersion")
    private String newContractsVersion;

    @JsonProperty("plan")
    private String plan;

    @JsonProperty("addOns")
    private Map<String, Integer> addOns = new HashMap<>();

    public ContractServicePricing() {
    }

    /** The version in {@code contract.contractedServices[service]}, unescaped. */
    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /** {@code "active"} or {@code "archived"}. */
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isArchived() {
        return "archived".equals(status);
    }

    /** Path of the raw Pricing2Yaml of that version (see {@code pricings.getYaml}). */
    public String getYamlUrl() {
        return yamlUrl;
    }

    public void setYamlUrl(String yamlUrl) {
        this.yamlUrl = yamlUrl;
    }

    /** The version SPACE assigns to new contracts of the service, or {@code null} if unknown. */
    public String getNewContractsVersion() {
        return newContractsVersion;
    }

    public void setNewContractsVersion(String newContractsVersion) {
        this.newContractsVersion = newContractsVersion;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public Map<String, Integer> getAddOns() {
        return addOns;
    }

    public void setAddOns(Map<String, Integer> addOns) {
        this.addOns = addOns != null ? addOns : new HashMap<>();
    }
}
