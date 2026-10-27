package io.github.isagroup.spaceclient.modules;

import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.PricingYamlUnavailableException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.types.PricingYaml;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.Collections;

/**
 * Module for reading pricing files from the Space platform
 */
public class PricingModule {

    private final ApiCalls api;

    /**
     * Creates an instance of the PricingModule class
     */
    public PricingModule(SpaceClient spaceClient, OkHttpClient httpClient) {
        this.api = new ApiCalls(spaceClient, httpClient);
    }

    /**
     * Retrieves the raw Pricing2Yaml file of a pricing version, byte for byte
     * ({@code GET /services/{serviceName}/pricings/{pricingVersion}?format=yaml}).
     *
     * @param serviceName    The name of the service
     * @param pricingVersion The pricing version (e.g. {@code 2024-06-08})
     * @return The file, its ETag and its syntax version
     * @throws PricingYamlUnavailableException if SPACE cannot provide the original file
     * @throws SpaceApiException               for any other refusal (e.g. 404 unknown version)
     * @throws java.io.UncheckedIOException    if SPACE cannot be reached
     */
    public PricingYaml getYaml(String serviceName, String pricingVersion) {
        return getYaml(serviceName, pricingVersion, null);
    }

    /**
     * Conditional variant of {@link #getYaml(String, String)}: pass the ETag of a copy you already
     * hold (quoted or not). If the file has not changed SPACE answers 304 and the result has
     * {@code isNotModified() == true} and a {@code null} YAML.
     */
    public PricingYaml getYaml(String serviceName, String pricingVersion, String etag) {
        HttpUrl url = api.url("services", serviceName, "pricings", pricingVersion)
                .addQueryParameter("format", "yaml")
                .build();
        Request.Builder request = api.request(url).header("Accept", "application/yaml").get();
        if (etag != null && !etag.isEmpty()) {
            request.header("If-None-Match", ApiCalls.quoteEtag(etag));
        }

        ApiCalls.Answer answer;
        try {
            answer = api.execute(request.build(), Collections.singleton(304));
        } catch (SpaceApiException e) {
            if (e.getStatusCode() == 404 && PricingYamlUnavailableException.CODE.equals(e.getErrorCode())) {
                throw new PricingYamlUnavailableException(e, serviceName, pricingVersion);
            }
            throw e;
        }

        String receivedEtag = answer.headers.get("ETag");
        String syntaxVersion = answer.headers.get("X-Pricing-Syntax-Version");
        if (answer.code == 304) {
            String known = receivedEtag != null ? receivedEtag : ApiCalls.quoteEtag(etag);
            return new PricingYaml(null, known, syntaxVersion, true);
        }
        return new PricingYaml(answer.body, receivedEtag, syntaxVersion, false);
    }
}
