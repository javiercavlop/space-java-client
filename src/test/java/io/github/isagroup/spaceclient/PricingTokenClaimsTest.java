package io.github.isagroup.spaceclient;

import io.github.isagroup.spaceclient.types.PricingTokenPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * The claims SPACE adds to pricing tokens with per-organization userIds (space#80): {@code sub}
 * became the opaque {@code urn:space:<organizationId>:<percent-encoded userId>}, and the token
 * gained {@code userId}, {@code organizationId}, {@code aud} and {@code iss}. Tokens of older
 * servers only have the bare userId in {@code sub}.
 */
@DisplayName("Pricing token claims")
class PricingTokenClaimsTest {

    private static final String ORG = "6630a1b2c3d4e5f6a7b8c9d0";

    private static String token(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"ES256\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** encodeURIComponent, as SPACE builds the URN. */
    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
                .replace("%7E", "~");
    }

    private static String newServerPayload(String userId, String aud) {
        return "{\"sub\":\"urn:space:" + ORG + ":" + quote(encodeUriComponent(userId)) + "\","
                + "\"userId\":\"" + quote(userId) + "\",\"organizationId\":\"" + ORG + "\","
                + "\"aud\":" + aud + ",\"iss\":\"space\",\"iat\":1761559200,\"exp\":1761562800,\"features\":{}}";
    }

    private static String newServerPayload(String userId) {
        return newServerPayload(userId, "\"" + ORG + "\"");
    }

    private static String oldServerPayload(String userId) {
        return "{\"sub\":\"" + quote(userId) + "\",\"iat\":1761559200,\"exp\":1761562800,\"features\":{}}";
    }

    @Test
    @DisplayName("decode reads userId, organizationId, aud and iss")
    void newClaims() {
        PricingTokenPayload payload = PricingTokenPayload.decode(token(newServerPayload("user-1")));

        assertThat(payload.getUserId()).isEqualTo("user-1");
        assertThat(payload.getOrganizationId()).isEqualTo(ORG);
        assertThat(payload.getAud()).containsExactly(ORG);
        assertThat(payload.getIss()).isEqualTo("space");
        assertThat(payload.getSub()).isEqualTo("urn:space:" + ORG + ":user-1");
        assertThat(payload.resolveUserId()).isEqualTo("user-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a:b", "a%3Ab", "with space", "mail@example.com", "a/b", "ñandú"})
    @DisplayName("resolveUserId returns the raw userId claim, not part of the URN")
    void rawUserId(String userId) {
        PricingTokenPayload payload = PricingTokenPayload.decode(token(newServerPayload(userId)));

        assertThat(payload.getSub()).isNotEqualTo(userId);
        assertThat(payload.resolveUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("sub is never parsed when the token has a userId")
    void neverParsesSub() {
        String json = newServerPayload("user-1").replace("urn:space:" + ORG + ":user-1", "urn:space:other:someone-else");
        assertThat(PricingTokenPayload.decode(token(json)).resolveUserId()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("Tokens of older servers fall back to sub")
    void oldServers() {
        PricingTokenPayload payload = PricingTokenPayload.decode(token(oldServerPayload("user-1")));

        assertThat(payload.getUserId()).isNull();
        assertThat(payload.getOrganizationId()).isNull();
        assertThat(payload.getAud()).isNull();
        assertThat(payload.getIss()).isNull();
        assertThat(payload.resolveUserId()).isEqualTo("user-1");
        // An old token whose userId looks like a URN is returned as it is.
        assertThat(PricingTokenPayload.decode(token(oldServerPayload("urn:space:x:y"))).resolveUserId())
                .isEqualTo("urn:space:x:y");
    }

    @Test
    @DisplayName("aud may be an array")
    void audArray() {
        PricingTokenPayload payload = PricingTokenPayload.decode(token(newServerPayload("u", "[\"" + ORG + "\",\"other\"]")));
        assertThat(payload.getAud()).containsExactly(ORG, "other");
    }

    @Test
    @DisplayName("A payload built by hand has no new claims")
    void byHand() {
        PricingTokenPayload payload = new PricingTokenPayload();
        payload.setSub("user-1");
        assertThat(payload.resolveUserId()).isEqualTo("user-1");
        payload.setUserId("raw");
        assertThat(payload.resolveUserId()).isEqualTo("raw");
        assertThat(new PricingTokenPayload().resolveUserId()).isNull();
    }
}
