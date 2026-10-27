package io.github.isagroup.spaceclient.modules;

import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.IdempotencyException;
import io.github.isagroup.spaceclient.exceptions.IdempotencyInProgressException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceConnectionException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.WriteOptions;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sends the requests of the subscription-change API and turns refusals into
 * {@link SpaceApiException} instead of {@code null}. Package-private: an implementation detail of
 * the modules.
 */
final class ApiCalls {
    /** Capability token of the {@code Idempotency-Key} header (SCORELabUS/space#85). */
    static final String IDEMPOTENCY_KEY = "idempotency-key";
    /** Capability token of bounded consumptions and revert by {@code consumptionId} (SCORELabUS/space#84). */
    static final String ATOMIC_USAGE_LIMITS = "atomic-usage-limits";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern QUOTED_ETAG = Pattern.compile("^(W/)?\".*\"$");

    private final SpaceClient spaceClient;
    private final OkHttpClient httpClient;

    ApiCalls(SpaceClient spaceClient, OkHttpClient httpClient) {
        this.spaceClient = spaceClient;
        this.httpClient = httpClient;
    }

    /** A builder for {@code <httpUrl>/<segments...>}, each segment escaped (so {@code v1/2} stays one). */
    HttpUrl.Builder url(String... segments) {
        HttpUrl.Builder builder = HttpUrl.get(spaceClient.getHttpUrl()).newBuilder();
        for (String segment : segments) {
            builder.addPathSegment(segment);
        }
        return builder;
    }

    Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("x-api-key", spaceClient.getApiKey());
    }

    /**
     * Executes the request; non-2xx statuses outside {@code accept} raise {@link SpaceApiException},
     * and a request that never got an answer raises {@link SpaceConnectionException}.
     */
    Answer execute(Request request, Set<Integer> accept) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : null;
            int code = response.code();
            spaceClient.observeCapabilities(response.header("X-Space-Capabilities"), code >= 200 && code < 400);
            if ((code < 200 || code >= 300) && !accept.contains(code)) {
                Map<String, String> headers = new HashMap<>();
                for (String name : response.headers().names()) {
                    headers.put(name, response.header(name));
                }
                throw new SpaceApiException(code, request.method(), pathOf(request.url()), body, headers);
            }
            return new Answer(code, response.headers(), body);
        } catch (IOException e) {
            throw new SpaceConnectionException("Could not reach SPACE at " + request.url() + ": " + e.getMessage(), e);
        }
    }

    Answer execute(Request request) {
        return execute(request, Collections.emptySet());
    }

    /**
     * Sends a write that SPACE applies at most once per {@code Idempotency-Key}.
     *
     * <ul>
     * <li>The key is the caller's (refused with {@link SpaceFeatureUnsupportedException} before
     * anything is sent if SPACE does not advertise {@code idempotency-key}) or a new random UUID. It
     * is the same on every attempt, as are the method, URL, body and other headers.</li>
     * <li>Up to {@code retries} retries after a {@link SpaceConnectionException}, a {@code 5xx} or
     * a {@code 409 IDEMPOTENCY_IN_PROGRESS}, only while SPACE advertises {@code idempotency-key}
     * (an older SPACE would apply each attempt). {@code Retry-After} is waited exactly; without it,
     * exponential backoff with jitter. Other {@code 4xx} are never retried.</li>
     * <li>Refusals of the key become {@link IdempotencyException} subclasses.</li>
     * </ul>
     *
     * @param builder        the request, without the key
     * @param idempotencyKey the caller's key, or {@code null} to generate one
     * @param accept         non-2xx statuses that are answers for this call
     */
    Answer executeKeyed(Request.Builder builder, String idempotencyKey, Set<Integer> accept) {
        if (idempotencyKey != null) {
            WriteOptions.checkKey(idempotencyKey);
            if (!spaceClient.hasCapability(IDEMPOTENCY_KEY)) {
                throw new SpaceFeatureUnsupportedException(IDEMPOTENCY_KEY,
                        "The connected SPACE does not support Idempotency-Key: nothing was written");
            }
        }
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        Request request = builder.header(IDEMPOTENCY_KEY_HEADER, key).build();

        for (int attempt = 0; ; attempt++) {
            RuntimeException failure;
            Integer retryAfter = null;
            boolean retryable;
            try {
                return execute(request, accept);
            } catch (SpaceApiException refusal) {
                retryAfter = parseRetryAfter(refusal.getHeader("Retry-After"));
                IdempotencyException typed = IdempotencyException.of(refusal, key, retryAfter);
                failure = typed != null ? typed : refusal;
                retryable = typed instanceof IdempotencyInProgressException
                        || (typed == null && refusal.getStatusCode() >= 500);
            } catch (SpaceConnectionException unreachable) {
                failure = unreachable;
                retryable = true;
            }
            if (retryable && attempt < spaceClient.getRetries()) {
                long delay = spaceClient.retryDelayMillis(attempt, retryAfter);
                if (delay >= 0 && spaceClient.hasCapability(IDEMPOTENCY_KEY)) {
                    spaceClient.sleepBeforeRetry(delay);
                    continue;
                }
            }
            throw failure;
        }
    }

    Answer executeKeyed(Request.Builder builder, String idempotencyKey) {
        return executeKeyed(builder, idempotencyKey, Collections.emptySet());
    }

    /** {@code Retry-After} in seconds (delta-seconds or an HTTP date), or {@code null}. */
    static Integer parseRetryAfter(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            ZonedDateTime moment = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(ZonedDateTime.now(moment.getZone()), moment).getSeconds();
            return (int) Math.max(0, seconds);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String quoteEtag(String etag) {
        return QUOTED_ETAG.matcher(etag).matches() ? etag : "\"" + etag + "\"";
    }

    static String unquoteEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String value = etag.startsWith("W/") ? etag.substring(2) : etag;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String pathOf(HttpUrl url) {
        String query = url.encodedQuery();
        return url.encodedPath() + (query != null ? "?" + query : "");
    }

    /** What came back: status, headers and body. */
    static final class Answer {
        final int code;
        final Headers headers;
        final String body;

        Answer(int code, Headers headers, String body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }
    }
}
