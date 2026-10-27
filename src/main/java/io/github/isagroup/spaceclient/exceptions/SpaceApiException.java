package io.github.isagroup.spaceclient.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SPACE answered, and the answer was a refusal (a non-2xx status).
 *
 * Thrown by the calls of the subscription-change API ({@code pricings.getYaml},
 * {@code contracts.getPricings}, {@code contracts.previewNovation},
 * {@code services.getNewContractsPricing} and the novation overloads that take options).
 * The older calls keep their behaviour of logging and returning {@code null}.
 *
 * Carries the status, the method and path, and the body, because the body is where SPACE
 * explains itself ({@code {"error": "..."}}). A {@link SpaceException}.
 */
public class SpaceApiException extends SpaceException {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int statusCode;
    private final String method;
    private final String path;
    private final String body;
    private final Map<String, String> headers;

    public SpaceApiException(int statusCode, String method, String path, String body) {
        this(statusCode, method, path, body, (String) null);
    }

    /**
     * A refusal together with its response headers (names compared ignoring case), e.g. to read
     * {@code Retry-After}.
     */
    public SpaceApiException(int statusCode, String method, String path, String body, Map<String, String> headers) {
        this(statusCode, method, path, body, null, headers);
    }

    protected SpaceApiException(int statusCode, String method, String path, String body, String message) {
        this(statusCode, method, path, body, message, null);
    }

    protected SpaceApiException(int statusCode, String method, String path, String body, String message,
                                Map<String, String> headers) {
        super(message != null ? message : describe(statusCode, method, path, body));
        this.statusCode = statusCode;
        this.method = method;
        this.path = path;
        this.body = body;
        Map<String, String> lower = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                lower.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(lower);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    /** A response header ({@code Retry-After}...), ignoring case, or {@code null}. */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The response headers, names in lower case. */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** The raw response body, or {@code null}. */
    public String getBody() {
        return body;
    }

    /** The {@code error} field of a JSON body ({@code {"error": "CONTRACT_CHANGED"}}), or {@code null}. */
    public String getErrorCode() {
        JsonNode node = bodyAsJson();
        return node != null && node.hasNonNull("error") ? node.get("error").asText() : null;
    }

    /** The body parsed as JSON, or {@code null} if it is not JSON. */
    public JsonNode bodyAsJson() {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(int statusCode, String method, String path, String body) {
        String detail = body == null ? "" : body.trim();
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        return "SPACE answered " + statusCode + " to " + method + " " + path
                + (detail.isEmpty() ? "" : ": " + detail);
    }
}
