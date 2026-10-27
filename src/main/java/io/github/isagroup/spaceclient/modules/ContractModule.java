package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.ContractChangedException;
import io.github.isagroup.spaceclient.exceptions.SpaceApiException;
import io.github.isagroup.spaceclient.exceptions.SpaceException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.Contract;
import io.github.isagroup.spaceclient.types.ContractPricings;
import io.github.isagroup.spaceclient.types.ContractToCreate;
import io.github.isagroup.spaceclient.types.NovationOptions;
import io.github.isagroup.spaceclient.types.NovationPreview;
import io.github.isagroup.spaceclient.types.Subscription;
import io.github.isagroup.spaceclient.types.WriteOptions;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module for managing user contracts with the Space platform
 */
public class ContractModule {
    private static final Logger logger = LoggerFactory.getLogger(ContractModule.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** {@code X-Space-Capabilities} token of optimistic concurrency (S5). */
    public static final String CONTRACT_VERSION = "contract-version";
    /** {@code X-Space-Capabilities} token of keeping the billing period (S8). */
    public static final String KEEP_BILLING_PERIOD = "keep-billing-period";
    /** {@code X-Space-Capabilities} token of the opt-in rejection of private plans/add-ons (S8). */
    public static final String REJECT_PRIVATE = "reject-private";
    
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
     * Sent with a new {@code Idempotency-Key} and retried with it per the client's {@code retries};
     * failures are logged and {@code null} is returned, as before. Use
     * {@link #addContract(ContractToCreate, WriteOptions)} to pass your own key or to get the
     * failure.
     *
     * @param contractToCreate The contract details to be created
     * @return The created contract, or {@code null} on any failure
     */
    public Contract addContract(ContractToCreate contractToCreate) {
        try {
            return addContract(contractToCreate, null);
        } catch (SpaceException e) {
            logger.error("Failed to add contract for userId: {}", contractToCreate.getUserContact().getUserId(), e);
            return null;
        }
    }

    /**
     * Adds a new contract ({@code POST /contracts}), a write SPACE applies at most once per
     * {@code Idempotency-Key}: on a SPACE with {@code idempotency-key}, a retry of a creation that
     * succeeded answers the created contract instead of {@code 409}. Failures are thrown.
     *
     * @param contractToCreate The contract details to be created
     * @param options          the {@code Idempotency-Key} ({@code null} or no key: a new UUID)
     * @return The created contract
     * @throws io.github.isagroup.spaceclient.exceptions.IdempotencyException a refusal of the key
     * @throws SpaceFeatureUnsupportedException a key of the caller's on a SPACE without {@code idempotency-key}
     * @throws SpaceApiException any other refusal
     */
    public Contract addContract(ContractToCreate contractToCreate, WriteOptions options) {
        Request.Builder request = api.request(api.url("contracts").build())
                .post(RequestBody.create(toJson(contractToCreate), JSON));
        ApiCalls.Answer answer = api.executeKeyed(request, keyOf(options));
        Contract contract = fromJson(answer.body, Contract.class);

        CacheModule cache = spaceClient.cache;
        // Invalidate cache for this user if caching is enabled
        if (cache.isEnabled() && contract.getUserId() != null) {
            cache.invalidateUser(contract.getUserId());
            // Cache the new contract
            cache.set(cache.getContractKey(contract.getUserId()), contract);
        }
        return contract;
    }

    /**
     * Updates the subscription for a user in the Space platform
     * This method also invalidates the cached data for the user
     *
     * Unconditional, and failures are logged rather than thrown. To make the change conditional on
     * the contract not having changed, to keep the billing period, or to tell a refusal from an
     * outage, use {@link #novate(String, Subscription, NovationOptions)}.
     *
     * @param userId The ID of the user whose subscription is to be updated
     * @param newSubscription The new subscription details to be applied
     * Sent with a new {@code Idempotency-Key} and retried with it per the client's {@code retries}.
     *
     * @return The updated contract, or {@code null} on any failure (refusal or SPACE unreachable)
     * @see #novate(String, Subscription, NovationOptions)
     */
    public Contract updateContractSubscription(String userId, Subscription newSubscription) {
        try {
            return novate(userId, newSubscription, new NovationOptions());
        } catch (SpaceException e) {
            logger.error("Failed to update contract subscription for userId: {}", userId, e);
            return null;
        }
    }
    
    /**
     * Updates the subscription for a user (novation) with the options of the subscription-change
     * API: optimistic concurrency and keeping the billing period.
     *
     * Unlike {@link #updateContractSubscription(String, Subscription)}, failures are thrown, not
     * logged and returned as {@code null}, so a 412 cannot be mistaken for any other failure.
     *
     * The options need a SPACE that implements them ({@code X-Space-Capabilities} tokens
     * {@value #CONTRACT_VERSION}, {@value #KEEP_BILLING_PERIOD} and {@value #REJECT_PRIVATE}). Before
     * writing, the client checks them, from any response of the last {@link SpaceClient#CAPABILITIES_TTL_MS} or by
     * reading the contract once (which also lets a stale expected version fail early), and throws
     * {@link SpaceFeatureUnsupportedException} without writing if one is missing. After writing it
     * checks the answer: no {@code ETag} with an expected version, or a different
     * {@code billingPeriod.startDate} with {@code keepBillingPeriod}, throws
     * {@link SpaceFeatureUnsupportedException} with {@code isWritten() == true}. With no option set
     * the request is a single PUT.
     *
     * @param userId          The ID of the user whose subscription is to be updated
     * @param newSubscription The new subscription details to be applied
     * @param options         {@code expectedContractVersion} (sent as {@code If-Match}),
     *                        {@code keepBillingPeriod} (sent as {@code ?keepBillingPeriod=true})
     *                        and {@code rejectPrivate} (sent as {@code ?rejectPrivate=true});
     *                        {@code idempotencyKey}: the {@code Idempotency-Key} of the novation
     *                        (default: a new UUID). The PUT is retried with the same key and
     *                        {@code If-Match} per the client's {@code retries}.
     * @return The updated contract, with {@code contractVersion} from the body or the {@code ETag}
     * @throws ContractChangedException          if the contract changed since {@code expectedContractVersion}
     * @throws SpaceFeatureUnsupportedException  if SPACE does not implement a requested option
     * @throws SpaceApiException                 for any other refusal
     * @throws io.github.isagroup.spaceclient.exceptions.SpaceConnectionException if SPACE cannot be reached
     * @see #updateContractSubscription(String, Subscription)
     */
    public Contract novate(String userId, Subscription newSubscription, NovationOptions options) {
        NovationOptions opts = options != null ? options : new NovationOptions();
        String expected = opts.getExpectedContractVersion();
        Contract before = ensureNovationSupport(userId, opts);

        okhttp3.HttpUrl.Builder url = api.url("contracts", userId);
        if (opts.isKeepBillingPeriod()) {
            url.addQueryParameter("keepBillingPeriod", "true");
        }
        if (opts.isRejectPrivate()) {
            url.addQueryParameter("rejectPrivate", "true");
        }
        Request.Builder request = api.request(url.build()).put(RequestBody.create(toJson(newSubscription), JSON));
        if (expected != null) {
            request.header("If-Match", ApiCalls.quoteEtag(expected));
        }

        ApiCalls.Answer answer;
        try {
            answer = api.executeKeyed(request, opts.getIdempotencyKey());
        } catch (SpaceApiException e) {
            if (e.getStatusCode() == 412) {
                // Whatever we hold for this user is known to be stale.
                invalidate(userId);
                throw new ContractChangedException(e, userId, expected);
            }
            throw e;
        }

        String etag = answer.headers.get("ETag");
        Contract contract = fromJson(answer.body, Contract.class);
        if (contract.getContractVersion() == null && etag != null) {
            contract.setContractVersion(ApiCalls.unquoteEtag(etag));
        }

        String ignored = ignoredOption(opts, etag, contract, before);
        if (ignored != null) {
            // SPACE wrote the novation without the guarantee: say so, and keep nothing cached for
            // a contract in a state nobody chose.
            invalidate(userId);
            String message = CONTRACT_VERSION.equals(ignored)
                    ? "SPACE applied the novation without an ETag: If-Match (expectedContractVersion) was not enforced"
                    : "SPACE applied the novation but restarted the billing period (keepBillingPeriod was ignored)";
            throw new SpaceFeatureUnsupportedException(ignored, message, true, contract);
        }

        CacheModule cache = spaceClient.cache;
        if (cache.isEnabled()) {
            cache.invalidateUser(userId);
            cache.set(cache.getContractKey(userId), contract);
        }
        return contract;
    }

    /**
     * Makes sure SPACE honours the requested options before anything is written (S0). When the
     * capabilities are not cached, reads the contract once: its answer carries them, and its
     * {@code ETag} lets a stale expected version fail before the write.
     *
     * @return The contract read for the check, or {@code null} if none was needed
     */
    private Contract ensureNovationSupport(String userId, NovationOptions opts) {
        List<String> required = new ArrayList<>();
        if (opts.getExpectedContractVersion() != null) {
            required.add(CONTRACT_VERSION);
        }
        if (opts.isKeepBillingPeriod()) {
            required.add(KEEP_BILLING_PERIOD);
        }
        if (opts.isRejectPrivate()) {
            required.add(REJECT_PRIVATE);
        }
        if (required.isEmpty()) {
            return null;
        }

        Contract current = null;
        String currentVersion = null;
        Set<String> capabilities = spaceClient.getCachedCapabilities();
        if (capabilities == null) {
            okhttp3.HttpUrl url = api.url("contracts", userId).build();
            ApiCalls.Answer answer = api.execute(api.request(url).get().build());
            capabilities = spaceClient.getCachedCapabilities();
            if (capabilities == null) {
                capabilities = Collections.emptySet();
            }
            current = fromJson(answer.body, Contract.class);
            String etag = answer.headers.get("ETag");
            currentVersion = etag != null ? ApiCalls.unquoteEtag(etag) : current.getContractVersion();
            requireAll(required, capabilities);
            String expected = opts.getExpectedContractVersion();
            if (expected != null && currentVersion != null && !currentVersion.equals(ApiCalls.unquoteEtag(expected))) {
                // Early and not atomic; If-Match on the PUT stays the real check.
                invalidate(userId);
                throw ContractChangedException.early("GET", url.encodedPath(), userId, expected, currentVersion);
            }
            return current;
        }
        requireAll(required, capabilities);
        return null;
    }

    private static void requireAll(List<String> required, Set<String> capabilities) {
        for (String capability : required) {
            if (!capabilities.contains(capability)) {
                throw new SpaceFeatureUnsupportedException(capability,
                        "The connected SPACE does not support " + optionOf(capability) + ": nothing was written");
            }
        }
    }

    private static String optionOf(String capability) {
        if (CONTRACT_VERSION.equals(capability)) {
            return "expectedContractVersion (If-Match)";
        }
        return KEEP_BILLING_PERIOD.equals(capability) ? "keepBillingPeriod" : "rejectPrivate";
    }

    /**
     * Post-write backstop: the capability whose option the stored contract shows was ignored, or
     * {@code null}. The previous start date comes from the pre-write read or, failing that, from
     * the history entry the novation pushed (SPACE records the replaced period there).
     */
    private static String ignoredOption(NovationOptions opts, String etag, Contract contract, Contract before) {
        if (opts.getExpectedContractVersion() != null && etag == null) {
            return CONTRACT_VERSION;
        }
        if (opts.isKeepBillingPeriod()) {
            Date previous = before != null && before.getBillingPeriod() != null
                    ? before.getBillingPeriod().getStartDate() : null;
            List<io.github.isagroup.spaceclient.types.ContractHistoryEntry> history = contract.getHistory();
            if (previous == null && history != null && !history.isEmpty()) {
                previous = history.get(history.size() - 1).getStartDate();
            }
            Date now = contract.getBillingPeriod() != null ? contract.getBillingPeriod().getStartDate() : null;
            if (previous != null && now != null && previous.getTime() != now.getTime()) {
                return KEEP_BILLING_PERIOD;
            }
        }
        return null;
    }

    private void invalidate(String userId) {
        CacheModule cache = spaceClient.cache;
        if (cache.isEnabled()) {
            cache.invalidateUser(userId);
        }
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
     *
     * @throws SpaceFeatureUnsupportedException with {@code keepBillingPeriod} when the SPACE that
     *         answered does not advertise {@value #KEEP_BILLING_PERIOD}
     */
    public NovationPreview previewNovation(String userId, Subscription newSubscription, boolean keepBillingPeriod) {
        return previewNovation(userId, newSubscription, new NovationOptions().keepBillingPeriod(keepBillingPeriod));
    }

    /**
     * {@link #previewNovation(String, Subscription)} with the options of the novation it previews:
     * {@code keepBillingPeriod} ({@code ?keepBillingPeriod=true}) and {@code rejectPrivate}
     * ({@code ?rejectPrivate=true}). {@code expectedContractVersion} is ignored: a dry run writes
     * nothing.
     *
     * @throws SpaceFeatureUnsupportedException when an option is set and the SPACE that answered
     *         does not advertise its capability ({@value #KEEP_BILLING_PERIOD},
     *         {@value #REJECT_PRIVATE}): the preview would describe a result the novation would not
     *         have
     */
    public NovationPreview previewNovation(String userId, Subscription newSubscription, NovationOptions options) {
        NovationOptions opts = options != null ? options : new NovationOptions();
        okhttp3.HttpUrl.Builder url = api.url("contracts", userId, "novation", "preview");
        if (opts.isKeepBillingPeriod()) {
            url.addQueryParameter("keepBillingPeriod", "true");
        }
        if (opts.isRejectPrivate()) {
            url.addQueryParameter("rejectPrivate", "true");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (newSubscription.getContractedServices() != null && !newSubscription.getContractedServices().isEmpty()) {
            body.put("contractedServices", newSubscription.getContractedServices());
        }
        body.put("subscriptionPlans", newSubscription.getSubscriptionPlans());
        body.put("subscriptionAddOns", newSubscription.getSubscriptionAddOns());

        ApiCalls.Answer answer = api.execute(api.request(url.build()).post(RequestBody.create(toJson(body), JSON)).build());
        List<String> required = new ArrayList<>();
        if (opts.isKeepBillingPeriod()) {
            required.add(KEEP_BILLING_PERIOD);
        }
        if (opts.isRejectPrivate()) {
            required.add(REJECT_PRIVATE);
        }
        Set<String> capabilities = spaceClient.getCachedCapabilities();
        for (String capability : required) {
            if (capabilities == null || !capabilities.contains(capability)) {
                // The preview would describe a result the novation would not have.
                throw new SpaceFeatureUnsupportedException(capability,
                        "The connected SPACE does not support " + optionOf(capability) + ": the preview ignored it");
            }
        }
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
        try {
            return updateContractSubscriptionByGroupId(groupId, newSubscription, null);
        } catch (SpaceException e) {
            logger.error("Failed to update contract subscription for groupId: {}", groupId, e);
            return null;
        }
    }

    /**
     * Novates every contract of a group ({@code PUT /contracts?groupId=}), a write SPACE applies at
     * most once per {@code Idempotency-Key}. Failures are thrown.
     *
     * @param groupId         The ID of the group whose contracts are to be updated
     * @param newSubscription The new subscription details to be applied
     * @param options         the {@code Idempotency-Key} ({@code null} or no key: a new UUID)
     * @return The updated contracts
     */
    public List<Contract> updateContractSubscriptionByGroupId(String groupId, Subscription newSubscription,
                                                              WriteOptions options) {
        HttpUrl url = api.url("contracts").addQueryParameter("groupId", groupId).build();
        ApiCalls.Answer answer = api.executeKeyed(
                api.request(url).put(RequestBody.create(toJson(newSubscription), JSON)), keyOf(options));
        List<Contract> contracts;
        try {
            contracts = objectMapper.readValue(answer.body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Contract.class));
        } catch (IOException e) {
            throw new SpaceException("Unexpected answer from SPACE: " + e.getMessage(), e);
        }

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

    /**
     * Applies usage level increments ({@code PUT /contracts/{userId}/usageLevels}); sent with a new
     * {@code Idempotency-Key} and retried with it per the client's {@code retries}. Failures are
     * logged and {@code null} is returned, as before.
     */
    public Contract updateContractUsageLevels(String userId, String serviceName, Map<String, Number> usageLevelsNovations) {
        try {
            return updateContractUsageLevels(userId, serviceName, usageLevelsNovations, null);
        } catch (SpaceException e) {
            logger.error("Failed to update contract usage levels for userId: {}", userId, e);
            return null;
        }
    }

    /**
     * Applies usage level increments ({@code PUT /contracts/{userId}/usageLevels}), a write SPACE
     * applies at most once per {@code Idempotency-Key}. Failures are thrown.
     *
     * @param options the {@code Idempotency-Key} ({@code null} or no key: a new UUID)
     * @return The updated contract
     */
    public Contract updateContractUsageLevels(String userId, String serviceName,
                                              Map<String, Number> usageLevelsNovations, WriteOptions options) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(serviceName, usageLevelsNovations);
        HttpUrl url = api.url("contracts", userId, "usageLevels").build();
        ApiCalls.Answer answer = api.executeKeyed(
                api.request(url).put(RequestBody.create(toJson(payload), JSON)), keyOf(options));
        Contract contract = fromJson(answer.body, Contract.class);

        CacheModule cache = spaceClient.cache;
        // Invalidate and update cache for this user if caching is enabled
        if (cache.isEnabled()) {
            cache.invalidateUser(userId);
            cache.set(cache.getContractKey(userId), contract);
        }
        return contract;
    }

    private static String keyOf(WriteOptions options) {
        return options != null ? options.getIdempotencyKey() : null;
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
