package io.github.isagroup.spaceclient.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.IdempotencyException;
import io.github.isagroup.spaceclient.exceptions.SpaceException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.FeatureEvaluationResult;
import io.github.isagroup.spaceclient.types.WriteOptions;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Module for evaluating features and managing pricing tokens
 */
public class FeatureModule {
  private static final Logger logger = LoggerFactory.getLogger(FeatureModule.class);
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final SpaceClient spaceClient;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ApiCalls api;

  /**
   * Creates an instance of the FeatureModule class
   */
  public FeatureModule(SpaceClient spaceClient, OkHttpClient httpClient, ObjectMapper objectMapper) {
    this.spaceClient = spaceClient;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.api = new ApiCalls(spaceClient, httpClient);
  }

  /**
   * Evaluates a feature for a specific user
   * Results are cached only for read-only evaluations (when expectedConsumption
   * is empty)
   *
   * With an expected consumption the evaluation is a write: it carries a new {@code Idempotency-Key}
   * and is retried with it per {@link io.github.isagroup.spaceclient.types.SpaceConnectionOptions#getRetries()},
   * and the result carries {@link FeatureEvaluationResult#getConsumptionId()}. Failures are reported
   * as an evaluation error, as before ({@code IO_ERROR}, or the code of an idempotency refusal);
   * {@link #evaluate(String, String, Map, WriteOptions)} throws them instead.
   *
   * @param userId              The ID of the user for whom the feature is being
   *                            evaluated
   * @param featureId           The ID of the feature to be evaluated (i.e.
   *                            ${serviceName}-${featureName})
   * @param expectedConsumption Expected consumption values for the feature
   * @param details             Whether to include details in the response
   * @param server              Whether to use server-side evaluation
   * @return The evaluation result
   */
  public FeatureEvaluationResult evaluate(String userId, String featureId,
      Map<String, Number> expectedConsumption,
      boolean details, boolean server) {
    if (expectedConsumption != null && !expectedConsumption.isEmpty()) {
      try {
        return send(userId, featureId, expectedConsumption, details, server, null);
      } catch (IdempotencyException e) {
        logger.error("Error evaluating feature: {}", e.getMessage());
        return errorResult(e.getCode(), e.getMessage());
      } catch (SpaceException e) {
        logger.error("Error evaluating feature", e);
        return errorResult("IO_ERROR", e.getMessage());
      }
    }
    try {
      if (expectedConsumption == null) {
        expectedConsumption = new HashMap<>();
      }

      CacheModule cache = spaceClient.cache;
      String cacheKey = cache.getFeatureKey(userId, featureId);

      // Only use cache for read-only evaluations (no consumption)
      if (cache.isEnabled()) {
        FeatureEvaluationResult cachedResult = cache.get(cacheKey, FeatureEvaluationResult.class);
        if (cachedResult != null) {
          return cachedResult;
        }
      }

      // Build query parameters
      StringBuilder queryParams = new StringBuilder();
      if (details) {
        queryParams.append("details=true");
      }
      if (server) {
        if (queryParams.length() > 0)
          queryParams.append("&");
        queryParams.append("server=true");
      }
      String queryString = queryParams.length() > 0 ? "?" + queryParams.toString() : "";

      String json = objectMapper.writeValueAsString(expectedConsumption);
      RequestBody body = RequestBody.create(json, JSON);

      Request request = new Request.Builder()
          .url(spaceClient.getHttpUrl() + "/features/" + userId + "/" + featureId + queryString)
          .header("x-api-key", spaceClient.getApiKey())
          .post(body)
          .build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "Unknown error";
          logger.error("Error evaluating feature: {}", errorBody);
          throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
        }

        String responseBody = response.body().string();
        FeatureEvaluationResult result = objectMapper.readValue(responseBody, FeatureEvaluationResult.class);

        // Cache the result only for read-only evaluations with shorter TTL
        if (cache.isEnabled()) {
          // Use shorter TTL for feature evaluations (60 seconds)
          cache.set(cacheKey, result, 60);
        }

        return result;
      }
    } catch (IOException e) {
      logger.error("IO error during feature evaluation", e);
      return errorResult("IO_ERROR", e.getMessage());
    }
  }

  /**
   * Evaluates a feature for a specific user with default options
   *
   * @param userId    The ID of the user for whom the feature is being evaluated
   * @param featureId The ID of the feature to be evaluated
   * @return The evaluation result
   */
  public FeatureEvaluationResult evaluate(String userId, String featureId) {
    return evaluate(userId, featureId, new HashMap<>(), false, false);
  }

  /**
   * Evaluates a feature for a specific user with expected consumption
   *
   * @param userId              The ID of the user for whom the feature is being
   *                            evaluated
   * @param featureId           The ID of the feature to be evaluated
   * @param expectedConsumption Expected consumption values for the feature
   * @return The evaluation result
   */
  public FeatureEvaluationResult evaluate(String userId, String featureId, Map<String, Number> expectedConsumption) {
    return evaluate(userId, featureId, expectedConsumption, false, false);
  }

  /**
   * Evaluates a feature and, with an expected consumption, consumes (a write SPACE applies at most
   * once per {@code Idempotency-Key}). On a SPACE with {@code atomic-usage-limits} the consumption is
   * applied only if every amount fits its limit, also under concurrency; one that does not fit
   * answers {@code eval == false} and consumes nothing. The result carries
   * {@link FeatureEvaluationResult#getConsumptionId()} for {@link #revertConsumption}.
   *
   * Unlike the other overloads, failures are thrown.
   *
   * @param userId              the user
   * @param featureId           {@code <service>-<feature>}
   * @param expectedConsumption usage limit id to amount; empty for a read-only evaluation (no key,
   *                            served from the cache when enabled)
   * @param options             the {@code Idempotency-Key} of the consumption ({@code null} or no
   *                            key: a new UUID, reused on the client's retries)
   * @return the evaluation result
   * @throws io.github.isagroup.spaceclient.exceptions.IdempotencyConflictException   the key was used for another request
   * @throws io.github.isagroup.spaceclient.exceptions.IdempotencyInProgressException a request with the key is still running
   * @throws io.github.isagroup.spaceclient.exceptions.IdempotencyKeyInvalidException SPACE rejected the key
   * @throws SpaceFeatureUnsupportedException a key of the caller's on a SPACE without {@code idempotency-key}
   * @throws io.github.isagroup.spaceclient.exceptions.SpaceApiException             any other refusal
   * @throws io.github.isagroup.spaceclient.exceptions.SpaceConnectionException      SPACE could not be reached
   */
  public FeatureEvaluationResult evaluate(String userId, String featureId, Map<String, Number> expectedConsumption,
      WriteOptions options) {
    Map<String, Number> consumption = expectedConsumption != null ? expectedConsumption : new HashMap<>();
    return send(userId, featureId, consumption, false, false, options != null ? options.getIdempotencyKey() : null);
  }

  private FeatureEvaluationResult send(String userId, String featureId, Map<String, Number> consumption,
      boolean details, boolean server, String idempotencyKey) {
    CacheModule cache = spaceClient.cache;
    boolean readOnly = consumption.isEmpty();
    String cacheKey = cache.getFeatureKey(userId, featureId);
    if (readOnly && cache.isEnabled()) {
      FeatureEvaluationResult cachedResult = cache.get(cacheKey, FeatureEvaluationResult.class);
      if (cachedResult != null) {
        return cachedResult;
      }
    }

    HttpUrl.Builder url = api.url("features", userId, featureId);
    if (details) {
      url.addQueryParameter("details", "true");
    }
    if (server) {
      url.addQueryParameter("server", "true");
    }
    Request.Builder request = api.request(url.build()).post(RequestBody.create(toJson(consumption), JSON));
    // Only a consumption is a write: SPACE ignores the key on the others, and they are never retried.
    ApiCalls.Answer answer = readOnly ? api.execute(request.build()) : api.executeKeyed(request, idempotencyKey);
    FeatureEvaluationResult result = fromJson(answer.body, FeatureEvaluationResult.class);

    if (readOnly && cache.isEnabled()) {
      cache.set(cacheKey, result, 60);
    } else if (cache.isEnabled()) {
      forgetUsage(userId, featureId);
    }
    return result;
  }

  /**
   * Reverts exactly the consumption an evaluation reported as {@code consumptionId}
   * ({@code POST /features/{userId}/{featureId}?revert=true&consumptionId=...}). Its amounts are
   * subtracted once, never below zero, and nothing recorded since is lost; reverting it again
   * changes nothing. Needs {@code atomic-usage-limits}: an older SPACE ignores the id and restores a
   * cached snapshot of the level instead, so the client refuses without writing. Sent with an
   * {@code Idempotency-Key} and retried like the other keyed writes. Failures are thrown.
   *
   * @return {@code true} when the consumption is reverted (now or before); {@code false} when SPACE
   *         does not know it (404: unknown or expired id, another user or feature, no contract)
   * @throws IllegalArgumentException          an empty {@code consumptionId}
   * @throws SpaceFeatureUnsupportedException  SPACE does not advertise {@code atomic-usage-limits}
   */
  public boolean revertConsumption(String userId, String featureId, String consumptionId) {
    return revertConsumption(userId, featureId, consumptionId, null);
  }

  /**
   * {@link #revertConsumption(String, String, String)} with the {@code Idempotency-Key} of the
   * revert.
   */
  public boolean revertConsumption(String userId, String featureId, String consumptionId, WriteOptions options) {
    if (consumptionId == null || consumptionId.isEmpty() || consumptionId.length() > WriteOptions.MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("consumptionId must have 1 to " + WriteOptions.MAX_KEY_LENGTH + " characters");
    }
    if (!spaceClient.hasCapability(ApiCalls.ATOMIC_USAGE_LIMITS)) {
      throw new SpaceFeatureUnsupportedException(ApiCalls.ATOMIC_USAGE_LIMITS,
          "The connected SPACE does not support revert by consumptionId: nothing was written");
    }
    HttpUrl url = api.url("features", userId, featureId)
        .addQueryParameter("revert", "true")
        .addQueryParameter("consumptionId", consumptionId)
        .build();
    ApiCalls.Answer answer = api.executeKeyed(api.request(url).post(RequestBody.create("{}", JSON)),
        options != null ? options.getIdempotencyKey() : null, Collections.singleton(404));
    if (answer.code == 404) {
      return false;
    }
    forgetUsage(userId, featureId);
    return true;
  }

  /**
   * Reverts the optimistic usage level update performed during a previous
   * evaluation
   * This method also invalidates cached data for the user
   *
   * @deprecated Use {@link #revertConsumption(String, String, String)} with the
   *             {@code consumptionId} of the evaluation: this reverts whichever consumption of the
   *             feature SPACE finds in the last two minutes (the oldest, or the newest with
   *             {@code revertToLatest}), which may not be the one meant.
   * @param userId         The ID of the user for whom the feature is being
   *                       evaluated
   * @param featureId      The ID of the feature to be evaluated
   * @param revertToLatest Whether to reset to the latest stored value (true =>
   *                       newest | false => oldest)
   * @return true if the revert operation was successful
   */
  @Deprecated
  public boolean revertEvaluation(String userId, String featureId, boolean revertToLatest) {
    try {
      // On the feature route: /features/{userId} evaluates every feature and reverts nothing,
      // which is where this call used to go.
      HttpUrl url = api.url("features", userId, featureId)
          .addQueryParameter("revert", "true")
          .addQueryParameter("latest", String.valueOf(revertToLatest))
          .build();
      api.executeKeyed(api.request(url).post(RequestBody.create("{}", JSON)), null);
      // Invalidate related cache entries after reverting
      forgetUsage(userId, featureId);
      return true;
    } catch (SpaceException e) {
      logger.error("Error reverting usage level for evaluation: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Reverts the optimistic usage level update with default (latest) behavior
   *
   * @deprecated Use {@link #revertConsumption(String, String, String)}.
   * @param userId    The ID of the user for whom the feature is being evaluated
   * @param featureId The ID of the feature to be evaluated
   * @return true if the revert operation was successful
   */
  @Deprecated
  public boolean revertEvaluation(String userId, String featureId) {
    return revertEvaluation(userId, featureId, true);
  }

  private void forgetUsage(String userId, String featureId) {
    CacheModule cache = spaceClient.cache;
    if (cache.isEnabled()) {
      cache.delete(cache.getFeatureKey(userId, featureId));
      cache.delete(cache.getContractKey(userId));
      // Invalidate pricing token as user's consumption has changed
      cache.delete(cache.getPricingTokenKey(userId));
    }
  }

  private static FeatureEvaluationResult errorResult(String code, String message) {
    Map<String, Object> used = new HashMap<>();
    Map<String, Object> limit = new HashMap<>();
    return new FeatureEvaluationResult(false, used, limit, new FeatureEvaluationResult.EvaluationError(code, message));
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
   * Generates a pricing token for a user
   * The token is cached to improve performance
   *
   * @param userId The ID of the user for whom the pricing token is being
   *               generated
   * @return The generated pricing token
   */
  public String generateUserPricingToken(String userId) {
    try {
      CacheModule cache = spaceClient.cache;
      String cacheKey = cache.getPricingTokenKey(userId);

      // Try to get from cache first
      if (cache.isEnabled()) {
        String cachedToken = cache.get(cacheKey, String.class);
        if (cachedToken != null) {
          return cachedToken;
        }
      }

      RequestBody body = RequestBody.create("{}", JSON);

      Request request = new Request.Builder()
          .url(spaceClient.getHttpUrl() + "/features/" + userId + "/pricing-token")
          .header("x-api-key", spaceClient.getApiKey())
          .post(body)
          .build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "Unknown error";
          logger.error("Error generating user pricing token: {}", errorBody);
          throw new IOException("Unexpected response code: " + response.code() + " - " + errorBody);
        }

        String responseBody = response.body().string();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        String token = (String) responseMap.get("pricingToken");

        // Cache the token if caching is enabled (with longer TTL as tokens are more
        // stable)
        if (cache.isEnabled() && token != null) {
          // Use longer TTL for pricing tokens (15 minutes)
          cache.set(cacheKey, token, 900);
        }

        return token;
      }
    } catch (IOException e) {
      logger.error("IO error during pricing token generation", e);
      return null;
    }
  }
}
