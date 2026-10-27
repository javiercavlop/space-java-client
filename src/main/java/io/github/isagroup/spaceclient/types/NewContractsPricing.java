package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of {@code GET /services/{serviceName}/pricings/new-contracts}: the version SPACE assigns
 * to contracts created from now on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NewContractsPricing {

    @JsonProperty("version")
    private String version;

    @JsonProperty("yamlUrl")
    private String yamlUrl;

    @JsonProperty("source")
    private String source;

    public NewContractsPricing() {
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /** Path of the raw Pricing2Yaml of that version (see {@code pricings.getYaml}). */
    public String getYamlUrl() {
        return yamlUrl;
    }

    public void setYamlUrl(String yamlUrl) {
        this.yamlUrl = yamlUrl;
    }

    /** How SPACE chose it: {@code "sphere-policy"}, {@code "default"} or {@code "latest-active"}. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
