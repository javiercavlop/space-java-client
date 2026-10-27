package io.github.isagroup.spaceclient.modules;

import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceConnectionException;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sends the requests of the subscription-change API and turns refusals into
 * {@link SpaceApiException} instead of {@code null}. Package-private: an implementation detail of
 * the modules.
 */
final class ApiCalls {
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
                throw new SpaceApiException(code, request.method(), pathOf(request.url()), body);
            }
            return new Answer(code, response.headers(), body);
        } catch (IOException e) {
            throw new SpaceConnectionException("Could not reach SPACE at " + request.url() + ": " + e.getMessage(), e);
        }
    }

    Answer execute(Request request) {
        return execute(request, Collections.emptySet());
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
