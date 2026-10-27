package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Why a novation would be rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NovationError {

    @JsonProperty("code")
    private String code;

    @JsonProperty("service")
    private String service;

    @JsonProperty("path")
    private String path;

    @JsonProperty("message")
    private String message;

    public NovationError() {
    }

    /** The code as SPACE sent it (see {@link NovationErrorCode}). */
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    /** {@link #getCode()} as a {@link NovationErrorCode}, or {@code null} if this client does not know it. */
    @JsonIgnore
    public NovationErrorCode getKnownCode() {
        return NovationErrorCode.fromCode(code);
    }

    public boolean is(NovationErrorCode expected) {
        return expected != null && expected.name().equals(code);
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    /** Path of the offending value, e.g. {@code subscriptionAddOns.acme.extraStorage}, or {@code null}. */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return code + " (" + service + (path != null ? ", " + path : "") + "): " + message;
    }
}
