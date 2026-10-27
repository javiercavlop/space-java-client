package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.types.NewContractsPricing;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Module for service-level information of the Space platform
 */
public class ServiceModule {

    private final ApiCalls api;
    private final ObjectMapper objectMapper;

    /**
     * Creates an instance of the ServiceModule class
     */
    public ServiceModule(SpaceClient spaceClient, OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.api = new ApiCalls(spaceClient, httpClient);
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the pricing version SPACE assigns to NEW contracts of a service
     * ({@code GET /services/{serviceName}/pricings/new-contracts}). Use it to render the pricing
     * page for signed-out visitors.
     *
     * @param serviceName The name of the service
     * @return The version, the path of its YAML and how it was chosen
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceApiException if SPACE refuses
     * @throws UncheckedIOException if SPACE cannot be reached
     */
    public NewContractsPricing getNewContractsPricing(String serviceName) {
        ApiCalls.Answer answer = api.execute(
                api.request(api.url("services", serviceName, "pricings", "new-contracts").build()).get().build());
        try {
            return objectMapper.readValue(answer.body, NewContractsPricing.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unexpected answer from SPACE: " + e.getMessage(), e);
        }
    }
}
