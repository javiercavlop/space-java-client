package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.ContractChangedException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceException;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.ContractPricings;
import io.github.isagroup.spaceclient.types.ContractToCreate;
import io.github.isagroup.spaceclient.types.NovationOptions;
import io.github.isagroup.spaceclient.types.NovationPreview;
import io.github.isagroup.spaceclient.types.Subscription;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module for managing user contracts with the Space platform
 */
public class ContractModule {
    private static final Logger logger = LoggerFactory.getLogger(ContractModule.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final SpaceClient spaceClient;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApiCalls api;

    /**
     * Creates an instance of the ContractModule class
     */
    public ContractModule(SpaceClient spaceClient, OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.spaceClient = spaceClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.api = new ApiCalls(spaceClient, httpClient);
    }

    /**
     * Retrieves the pricing version of every service of a user's contract, together with the
     * version SPACE assigns to new contracts and the current {@code contractVersion}
     * ({@code GET /contracts/{userId}/pricings}). Use it to render the pricing page of a signed-in
     * customer with the version of THEIR contract. Never cached: the {@code contractVersion} is
     * only useful while it is current.
     *
     * Unlike the older methods of this module, a refusal is thrown, not logged and returned as
     * {@code null}.
     *
     * @param userId The ID of the user whose contract pricings are to be retrieved
     * @return The pricings of the contract
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceApiException 404 if the contract is
     *         not in the caller's organisation, or any other refusal
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceConnectionException if SPACE cannot be reached
     */
    public ContractPricings getPricings(String userId) {
        ApiCalls.Answer answer = api.execute(
                api.request(api.url("contracts", userId, "pricings").build()).get().build());
        return fromJson(answer.body, ContractPricings.class);
    }

    /**
     * Retrieves the contract for a specific user from the Space platform
     * This method first checks the cache before making an API request
     *
     * @param userId The ID of the user whose contract is to be retrieved
     * @return The user's contract data
     */
    public Contract getContract(String userId) {
      try{
        CacheModule cache = spaceClient.cache;
        String cacheKey = cache.getContractKey(userId);

        // Try to get from cache first
        if (cache.isEnabled()) {
            Contract cachedContract = cache.get(cacheKey, Contract.class);
            if (cachedContract != null) {
                return cachedContract;
            }
        }

        // If not in cache, fetch from API
        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts/" + userId)
                .header("x-api-key", spaceClient.getApiKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error fetching contract: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            Contract contract = objectMapper.readValue(responseBody, Contract.class);

            // Cache the result if caching is enabled
            if (cache.isEnabled()) {
                cache.set(cacheKey, contract);
            }

            return contract;
        } 
      }catch (IOException e) {
          logger.error("Failed to get contract for userId: {}", userId, e);
          return null;
      }
    }

    /**
     * Adds a new contract to the Space platform
     * This method also invalidates any cached data for the user
     *
     * @param contractToCreate The contract details to be created
     * @return The created contract
     */
    public Contract addContract(ContractToCreate contractToCreate) {
      try{
        String json = objectMapper.writeValueAsString(contractToCreate);

        RequestBody body = RequestBody.create(json, JSON);

        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts")
                .header("x-api-key", spaceClient.getApiKey())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error adding contract: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            Contract contract = objectMapper.readValue(responseBody, Contract.class);

            CacheModule cache = spaceClient.cache;
            // Invalidate cache for this user if caching is enabled
            if (cache.isEnabled() && contract.getUserId() != null) {
                cache.invalidateUser(contract.getUserId());
                // Cache the new contract
                cache.set(cache.getContractKey(contract.getUserId()), contract);
            }

            return contract;
        }
      } catch (IOException e) {
          logger.error("Failed to add contract for userId: {}", contractToCreate.getUserContact().getUserId(), e);
          return null;
      }
    }

    /**
     * Updates the subscription for a user in the Space platform
     * This method also invalidates the cached data for the user
     *
     * @param userId The ID of the user whose subscription is to be updated
     * @param newSubscription The new subscription details to be applied
     * @return The updated contract
     */
    public Contract updateContractSubscription(String userId, Subscription newSubscription) {
      try{
        String json = objectMapper.writeValueAsString(newSubscription);
        RequestBody body = RequestBody.create(json, JSON);

        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts/" + userId)
                .header("x-api-key", spaceClient.getApiKey())
                .put(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error updating contract subscription: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            Contract contract = objectMapper.readValue(responseBody, Contract.class);

            CacheModule cache = spaceClient.cache;
            // Invalidate and update cache for this user if caching is enabled
            if (cache.isEnabled()) {
                cache.invalidateUser(userId);
                cache.set(cache.getContractKey(userId), contract);
            }

            return contract;
        }
      } catch (IOException e) {
          logger.error("Failed to update contract subscription for userId: {}", userId, e);
          return null;
      }
    }
    
    /**
     * Updates the subscription for a user (novation) with the options of the subscription-change
     * API: optimistic concurrency and keeping the billing period.
     *
     * Unlike {@link #updateContractSubscription(String, Subscription)}, a refusal is thrown, not
     * logged and returned as {@code null}, so a 412 cannot be mistaken for any other failure.
     *
     * @param userId          The ID of the user whose subscription is to be updated
     * @param newSubscription The new subscription details to be applied
     * @param options         {@code expectedContractVersion} (sent as {@code If-Match}) and
     *                        {@code keepBillingPeriod} (sent as {@code ?keepBillingPeriod=true})
     * @return The updated contract, with {@code contractVersion} from the body or the {@code ETag}
     * @throws ContractChangedException if the contract changed since {@code expectedContractVersion}
     * @throws SpaceApiException        for any other refusal
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceConnectionException if SPACE cannot be reached
     */
    public Contract updateContractSubscription(String userId, Subscription newSubscription, NovationOptions options) {
        NovationOptions opts = options != null ? options : new NovationOptions();
        okhttp3.HttpUrl.Builder url = api.url("contracts", userId);
        if (opts.isKeepBillingPeriod()) {
            url.addQueryParameter("keepBillingPeriod", "true");
        }
        Request.Builder request = api.request(url.build()).put(RequestBody.create(toJson(newSubscription), JSON));
        if (opts.getExpectedContractVersion() != null) {
            request.header("If-Match", ApiCalls.quoteEtag(opts.getExpectedContractVersion()));
        }

        ApiCalls.Answer answer;
        try {
            answer = api.execute(request.build());
        } catch (SpaceApiException e) {
            if (e.getStatusCode() == 412) {
                // Whatever we hold for this user is known to be stale.
                CacheModule cache = spaceClient.cache;
                if (cache.isEnabled()) {
                    cache.invalidateUser(userId);
                }
                throw new ContractChangedException(e, userId, opts.getExpectedContractVersion());
            }
            throw e;
        }

        Contract contract = fromJson(answer.body, Contract.class);
        if (contract.getContractVersion() == null && answer.headers.get("ETag") != null) {
            contract.setContractVersion(ApiCalls.unquoteEtag(answer.headers.get("ETag")));
        }

        CacheModule cache = spaceClient.cache;
        if (cache.isEnabled()) {
            cache.invalidateUser(userId);
            cache.set(cache.getContractKey(userId), contract);
        }
        return contract;
    }

    /**
     * Previews a novation without applying it ({@code POST /contracts/{userId}/novation/preview}).
     * SPACE runs the same policy and validation as a novation and writes nothing, so the cache is
     * left alone. An invalid subscription is reported as {@code isValid() == false} with
     * {@code getErrors()}, not thrown. Leave {@code contractedServices} empty to keep the current
     * versions or let the SPHERE policy decide.
     *
     * @param userId          The ID of the user whose subscription would change
     * @param newSubscription The candidate subscription
     * @return The preview, including the current {@code contractVersion}
     * @throws SpaceApiException    if SPACE refuses (e.g. 404 contract not found)
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceConnectionException if SPACE cannot be reached
     */
    public NovationPreview previewNovation(String userId, Subscription newSubscription) {
        return previewNovation(userId, newSubscription, false);
    }

    /**
     * {@link #previewNovation(String, Subscription)}, optionally keeping the current billing period.
     */
    public NovationPreview previewNovation(String userId, Subscription newSubscription, boolean keepBillingPeriod) {
        okhttp3.HttpUrl.Builder url = api.url("contracts", userId, "novation", "preview");
        if (keepBillingPeriod) {
            url.addQueryParameter("keepBillingPeriod", "true");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (newSubscription.getContractedServices() != null && !newSubscription.getContractedServices().isEmpty()) {
            body.put("contractedServices", newSubscription.getContractedServices());
        }
        body.put("subscriptionPlans", newSubscription.getSubscriptionPlans());
        body.put("subscriptionAddOns", newSubscription.getSubscriptionAddOns());

        ApiCalls.Answer answer = api.execute(api.request(url.build()).post(RequestBody.create(toJson(body), JSON)).build());
        return fromJson(answer.body, NovationPreview.class);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new SpaceException("Could not serialise the request: " + e.getMessage(), e);
        }
    }

    private <T> T fromJson(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new SpaceException("Unexpected answer from SPACE: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the subscription for all contracts with the given groupId in the SPACE platform
     * This method also invalidates the cached data for the group of users
     *
     * @param groupId The ID of the group whose contracts are to be updated
     * @param newSubscription The new subscription details to be applied
     * @return The updated contract
     */
    public List<Contract> updateContractSubscriptionByGroupId(String groupId, Subscription newSubscription) {
      try{
        String json = objectMapper.writeValueAsString(newSubscription);
        RequestBody body = RequestBody.create(json, JSON);

        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts?groupId=" + groupId)
                .header("x-api-key", spaceClient.getApiKey())
                .put(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error updating contract subscription: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            List<Contract> contracts = objectMapper.readValue(responseBody, objectMapper.getTypeFactory().constructCollectionType(List.class, Contract.class));

            CacheModule cache = spaceClient.cache;
            // Invalidate and update cache for this user if caching is enabled
            if (cache.isEnabled()) {
              for (Contract contract : contracts) {
                cache.invalidateUser(contract.getUserId());
                cache.set(cache.getContractKey(contract.getUserId()), contract);
              }
            }

            return contracts;
        }
      } catch (IOException e) {
          logger.error("Failed to update contract subscription for groupId: {}", groupId, e);
          return null;
      }
    }



    public Contract updateContractUsageLevels(String userId, String serviceName, Map<String, Number> usageLevelsNovations) {
      try{
        Map<String, Object> payload = new HashMap<>();
        payload.put(serviceName, usageLevelsNovations);
        
        String json = objectMapper.writeValueAsString(payload);
        RequestBody body = RequestBody.create(json, JSON);

        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts/" + userId + "/usageLevels")
                .header("x-api-key", spaceClient.getApiKey())
                .put(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error updating contract usage levels: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            Contract contract = objectMapper.readValue(responseBody, Contract.class);

            CacheModule cache = spaceClient.cache;
            // Invalidate and update cache for this user if caching is enabled
            if (cache.isEnabled()) {
                cache.invalidateUser(userId);
                cache.set(cache.getContractKey(userId), contract);
            }

            return contract;
        }
      } catch (IOException e) {
          logger.error("Failed to update contract usage levels for userId: {}", userId, e);
          return null;
      }
    }

    public void removeContract(String userId) {
      try{
        Request request = new Request.Builder()
                .url(spaceClient.getHttpUrl() + "/contracts/" + userId)
                .header("x-api-key", spaceClient.getApiKey())
                .delete()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Error removing contract: {}", errorBody);
                throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
            }

            CacheModule cache = spaceClient.cache;
            // Invalidate cache for this user if caching is enabled
            if (cache.isEnabled()) {
                cache.invalidateUser(userId);
            }
        }
      } catch (IOException e) {
          logger.error("Failed to remove contract for userId: {}", userId, e);
      }
    }
}
