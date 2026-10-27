package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Response of {@code GET /contracts/{userId}/pricings}: the pricing version of every service of a
 * contract, plus the contract's current {@code contractVersion}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractPricings {

    @JsonProperty("services")
    private Map<String, ContractServicePricing> services = new HashMap<>();

    @JsonProperty("contractVersion")
    private String contractVersion;

    public ContractPricings() {
    }

    public Map<String, ContractServicePricing> getServices() {
        return services;
    }

    public void setServices(Map<String, ContractServicePricing> services) {
        this.services = services != null ? services : new HashMap<>();
    }

    /** Opaque; pass it as {@code expectedContractVersion} when applying a change computed from this. */
    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }
}
