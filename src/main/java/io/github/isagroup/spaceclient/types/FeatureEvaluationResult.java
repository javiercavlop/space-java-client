package io.github.isagroup.spaceclient.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Feature evaluation result
 */
public class FeatureEvaluationResult {
    
    @JsonProperty("eval")
    private boolean eval;
    
    @JsonProperty("used")
    private Map<String, Object> used;
    
    @JsonProperty("limit")
    private Map<String, Object> limit;
    
    @JsonProperty("error")
    private EvaluationError error;

    /**
     * Only when this evaluation applied an expected consumption, on a SPACE with
     * {@code atomic-usage-limits}: the id to revert exactly that consumption with
     * {@code features.revertConsumption}. The same id comes back when SPACE replays the request.
     */
    @JsonProperty("consumptionId")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String consumptionId;

    public FeatureEvaluationResult() {
    }

    public FeatureEvaluationResult(boolean eval, Map<String, Object> used, Map<String, Object> limit, EvaluationError error) {
        this.eval = eval;
        this.used = used;
        this.limit = limit;
        this.error = error;
    }

    // Getters and Setters
    public boolean getEval() {
        return eval;
    }

    public void setEval(boolean eval) {
        this.eval = eval;
    }

    public Map<String, Object> getUsed() {
        return used;
    }

    public void setUsed(Map<String, Object> used) {
        this.used = used;
    }

    public Map<String, Object> getLimit() {
        return limit;
    }

    public void setLimit(Map<String, Object> limit) {
        this.limit = limit;
    }

    /** The id of the consumption this evaluation applied, or {@code null}. */
    public String getConsumptionId() {
        return consumptionId;
    }

    public void setConsumptionId(String consumptionId) {
        this.consumptionId = consumptionId;
    }

    public EvaluationError getError() {
        return error;
    }

    public void setError(EvaluationError error) {
        this.error = error;
    }

    public String toString() {
      if (error != null) {
          return "FeatureEvaluationResult{" +
                  "eval: " + eval +
                  ", used: " + used.toString() +
                  ", limit: " + limit.toString() +
                  ", error: " + error.toString() +
                  "}";
      }else {
          return "FeatureEvaluationResult{" +
                  "eval: " + eval +
                  ", used: " + used.toString() +
                  ", limit: " + limit.toString() +
                  "}";
      }
    }

    /**
     * Evaluation error information
     */
    public static class EvaluationError {
        @JsonProperty("code")
        private String code;
        
        @JsonProperty("message")
        private String message;

        public EvaluationError() {
        }

        public EvaluationError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String toString() {
            return "EvaluationError{" +
                   "code: '" + code + '\'' +
                   ", message: '" + message + '\'' +
                   '}';
        }
    }
}
