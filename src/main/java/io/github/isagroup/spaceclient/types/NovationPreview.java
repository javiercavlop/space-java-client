package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@code POST /contracts/{userId}/novation/preview}: what a novation with the same body
 * would do, without writing anything. Feature/limit diffs and prices are not computed by SPACE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NovationPreview {

    @JsonProperty("valid")
    private boolean valid;

    @JsonProperty("errors")
    private List<NovationError> errors = new ArrayList<>();

    @JsonProperty("effective")
    private Subscription effective = new Subscription();

    @JsonProperty("versionChanges")
    private Map<String, NovationVersionChange> versionChanges = new HashMap<>();

    @JsonProperty("usageWarnings")
    private List<NovationUsageWarning> usageWarnings = new ArrayList<>();

    @JsonProperty("billingPeriod")
    private BillingPeriodPreview billingPeriod = new BillingPeriodPreview();

    @JsonProperty("contractVersion")
    private String contractVersion;

    public NovationPreview() {
    }

    /** {@code false} when the novation would be rejected; {@link #getErrors()} then says why. */
    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<NovationError> getErrors() {
        return errors;
    }

    public void setErrors(List<NovationError> errors) {
        this.errors = errors != null ? errors : new ArrayList<>();
    }

    /** The subscription that would be stored, after applying the SPHERE policy. */
    public Subscription getEffective() {
        return effective;
    }

    public void setEffective(Subscription effective) {
        this.effective = effective;
    }

    public Map<String, NovationVersionChange> getVersionChanges() {
        return versionChanges;
    }

    public void setVersionChanges(Map<String, NovationVersionChange> versionChanges) {
        this.versionChanges = versionChanges != null ? versionChanges : new HashMap<>();
    }

    public List<NovationUsageWarning> getUsageWarnings() {
        return usageWarnings;
    }

    public void setUsageWarnings(List<NovationUsageWarning> usageWarnings) {
        this.usageWarnings = usageWarnings != null ? usageWarnings : new ArrayList<>();
    }

    public BillingPeriodPreview getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(BillingPeriodPreview billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    /** The current {@code contractVersion}; pass it as the expected version when applying. */
    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    /**
     * The billing period the contract would have after the novation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BillingPeriodPreview {

        @JsonProperty("startDate")
        private String startDate;

        @JsonProperty("endDate")
        private String endDate;

        @JsonProperty("restarts")
        private boolean restarts = true;

        public BillingPeriodPreview() {
        }

        public String getStartDate() {
            return startDate;
        }

        public void setStartDate(String startDate) {
            this.startDate = startDate;
        }

        public String getEndDate() {
            return endDate;
        }

        public void setEndDate(String endDate) {
            this.endDate = endDate;
        }

        /** {@code false} when {@code keepBillingPeriod} keeps the current period. */
        public boolean isRestarts() {
            return restarts;
        }

        public void setRestarts(boolean restarts) {
            this.restarts = restarts;
        }
    }
}
