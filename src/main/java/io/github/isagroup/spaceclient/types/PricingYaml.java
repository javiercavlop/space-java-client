package io.github.isagroup.spaceclient.types;

/**
 * The raw Pricing2Yaml file of a pricing version, as SPACE stores it
 * ({@code GET /services/{serviceName}/pricings/{pricingVersion}?format=yaml}).
 */
public class PricingYaml {

    private final String yaml;
    private final String etag;
    private final String syntaxVersion;
    private final boolean notModified;

    public PricingYaml(String yaml, String etag, String syntaxVersion, boolean notModified) {
        this.yaml = yaml;
        this.etag = etag;
        this.syntaxVersion = syntaxVersion;
        this.notModified = notModified;
    }

    /** The file, byte for byte (UTF-8). {@code null} when {@link #isNotModified()} is true. */
    public String getYaml() {
        return yaml;
    }

    /** The strong ETag of the file ({@code "<sha256 hex>"}), or {@code null}. */
    public String getEtag() {
        return etag;
    }

    /** The {@code syntaxVersion} of the file (header {@code X-Pricing-Syntax-Version}), or {@code null}. */
    public String getSyntaxVersion() {
        return syntaxVersion;
    }

    /** True when the ETag passed in still matches (HTTP 304) and no body was sent. */
    public boolean isNotModified() {
        return notModified;
    }
}
