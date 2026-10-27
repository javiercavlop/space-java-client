package io.github.isagroup.spaceclient.types;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Details of a contract event ({@code contract_created}, {@code contract_novated},
 * {@code contract_deleted}, {@code contract_renewed}), passed to the listener registered with
 * {@code SpaceClient.on}.
 */
public class ContractEventDetails {

    private final String userId;
    private final String organizationId;
    private final String contractVersion;
    private final List<String> services;

    public ContractEventDetails(String userId, String organizationId, String contractVersion, List<String> services) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.contractVersion = contractVersion;
        this.services = services != null ? Collections.unmodifiableList(services) : Collections.emptyList();
    }

    /** Reads the {@code details} object of a socket.io message; {@code null} in, {@code null} out. */
    public static ContractEventDetails fromJson(JSONObject details) {
        if (details == null) {
            return null;
        }
        List<String> services = new ArrayList<>();
        JSONArray array = details.optJSONArray("services");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                services.add(array.optString(i));
            }
        }
        return new ContractEventDetails(
                details.optString("userId", null),
                details.optString("organizationId", null),
                details.optString("contractVersion", null),
                services);
    }

    public String getUserId() {
        return userId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    /** The contract's version after the change ({@code null} for a deletion if SPACE omits it). */
    public String getContractVersion() {
        return contractVersion;
    }

    /** The services of the contract affected by the change. */
    public List<String> getServices() {
        return services;
    }

    @Override
    public String toString() {
        return "ContractEventDetails{userId=" + userId + ", organizationId=" + organizationId
                + ", contractVersion=" + contractVersion + ", services=" + services + '}';
    }
}
