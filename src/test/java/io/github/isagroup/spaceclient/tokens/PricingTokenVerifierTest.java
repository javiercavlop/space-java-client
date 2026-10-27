package io.github.isagroup.spaceclient.tokens;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.exceptions.PricingTokenAudienceException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenExpiredException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenIssuerException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenSignatureException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenUnknownKeyException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenVerificationException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.PricingTokenPayload;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifying pricing tokens with the JWKS SPACE publishes (space#87), against a MockWebServer that
 * serves a JWKS with an ES256 key generated here.
 */
@DisplayName("Pricing token verification with the JWKS of SPACE")
class PricingTokenVerifierTest {

    private static final String ORG = "6630a1b2c3d4e5f6a7b8c9d0";
    private static final String OTHER_ORG = "6630a1b2c3d4e5f6a7b8c9ff";
    private static final String JWKS_PATH = "/api/v1/.well-known/jwks.json";
    private static final String CAPABILITIES = "contract-userid-per-org,pricing-token-jwks";

    private static ECKey key;

    private MockWebServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger attackerRequests = new AtomicInteger();
    private volatile List<String> published;
    private volatile String cacheControl;
    private volatile String capabilities;
    private volatile int status;

    @BeforeAll
    static void generateKey() throws Exception {
        key = newKey();
    }

    private static ECKey newKey() throws Exception {
        ECKey generated = new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate();
        return new ECKey.Builder(generated).keyIDFromThumbprint().build();
    }

    @BeforeEach
    void setUp() throws Exception {
        published = new ArrayList<>(List.of(key.toPublicJWK().toJSONString()));
        cacheControl = "public, max-age=300";
        capabilities = CAPABILITIES;
        status = 200;
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.startsWith("/attacker")) {
                    attackerRequests.incrementAndGet();
                    return new MockResponse().setBody("{\"keys\":[]}");
                }
                if (!JWKS_PATH.equals(path)) {
                    return new MockResponse().setResponseCode(404);
                }
                jwksRequests.incrementAndGet();
                MockResponse response = new MockResponse().setResponseCode(status)
                        .setHeader("Content-Type", "application/jwk-set+json")
                        .setBody(status == 200 ? "{\"keys\":[" + String.join(",", published) + "]}" : "{\"error\":\"x\"}");
                if (cacheControl != null) {
                    response.setHeader("Cache-Control", cacheControl);
                }
                if (capabilities != null) {
                    response.setHeader("X-Space-Capabilities", capabilities);
                }
                return response;
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String spaceUrl() {
        return server.url("/").toString();
    }

    private PricingTokenVerifier verifier() {
        return verifier(b -> { });
    }

    private PricingTokenVerifier verifier(Consumer<PricingTokenVerifier.Builder> options) {
        PricingTokenVerifier.Builder builder = PricingTokenVerifier.builder(spaceUrl()).organizationId(ORG);
        options.accept(builder);
        return builder.build();
    }

    // -- tokens -----------------------------------------------------------------------------

    private static JWTClaimsSet.Builder claims(String userId, String organizationId) {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .subject("urn:space:" + organizationId + ":" + URLEncoder.encode(userId, StandardCharsets.UTF_8))
                .claim("userId", userId)
                .claim("organizationId", organizationId)
                .claim("features", Map.of())
                .audience(organizationId)
                .issuer("space")
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 3_600_000));
    }

    private static String sign(ECKey signingKey, JWTClaimsSet claims) throws Exception {
        return sign(signingKey, new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(), claims);
    }

    private static String sign(ECKey signingKey, JWSHeader header, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }

    private static String sign(ECKey signingKey) throws Exception {
        return sign(signingKey, claims("user-1", ORG).build());
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // -- tests ------------------------------------------------------------------------------

    @Test
    @DisplayName("A valid token is accepted and its payload returned")
    void valid() throws Exception {
        PricingTokenPayload payload = verifier().verify(sign(key, claims("a:b/ñ", ORG).build()));

        assertThat(payload.getUserId()).isEqualTo("a:b/ñ");
        assertThat(payload.resolveUserId()).isEqualTo("a:b/ñ");
        assertThat(payload.getOrganizationId()).isEqualTo(ORG);
        assertThat(payload.getAud()).containsExactly(ORG);
        assertThat(payload.getIss()).isEqualTo("space");
        assertThat(payload.getSub()).startsWith("urn:space:" + ORG + ":");
        assertThat(jwksRequests.get()).isEqualTo(1);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("x-api-key")).isNull();
    }

    @Test
    @DisplayName("The organization is required, from the builder or the call")
    void organizationRequired() throws Exception {
        PricingTokenVerifier noOrg = PricingTokenVerifier.builder(spaceUrl()).build();
        String token = sign(key);
        assertThatThrownBy(() -> noOrg.verify(token)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
        assertThat(noOrg.verify(token, ORG).getOrganizationId()).isEqualTo(ORG);
        assertThatThrownBy(() -> verifier().verify(token, OTHER_ORG)).isInstanceOf(PricingTokenAudienceException.class);
    }

    @Test
    @DisplayName("Another key under a published kid is rejected")
    void wrongKey() throws Exception {
        ECKey attacker = new ECKey.Builder(newKey()).keyID(key.getKeyID()).build();
        assertThatThrownBy(() -> verifier().verify(sign(attacker)))
                .isInstanceOf(PricingTokenSignatureException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode())
                .isEqualTo("PRICING_TOKEN_SIGNATURE_INVALID");
    }

    @Test
    @DisplayName("A tampered payload is rejected")
    void tampered() throws Exception {
        String[] parts = sign(key).split("\\.");
        String forgedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"userId\":\"user-1\"", "\"userId\":\"admin\"");
        assertThat(forgedPayload).contains("admin");
        String forged = parts[0] + "." + b64(forgedPayload) + "." + parts[2];
        assertThatThrownBy(() -> verifier().verify(forged)).isInstanceOf(PricingTokenSignatureException.class);
    }

    @Test
    @DisplayName("An unknown kid is rejected; a JWKS older than the cooldown is fetched once more")
    void unknownKid() throws Exception {
        ECKey other = newKey();
        String token = sign(other);
        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOfSatisfying(PricingTokenUnknownKeyException.class, e -> {
                    assertThat(e.getKid()).isEqualTo(other.getKeyID());
                    assertThat(e.getCode()).isEqualTo("PRICING_TOKEN_UNKNOWN_KID");
                });
        assertThat(jwksRequests.get()).isEqualTo(1);

        AtomicLong now = new AtomicLong(1_000_000);
        PricingTokenVerifier verifier = verifier(b -> b.clock(now::get));
        verifier.verify(sign(key));
        now.addAndGet(60_000);
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(PricingTokenUnknownKeyException.class);
        assertThat(jwksRequests.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("An expired token is rejected, with an optional leeway")
    void expired() throws Exception {
        long expSeconds = Instant.now().getEpochSecond() - 10;
        String token = sign(key, claims("user-1", ORG).expirationTime(new Date(expSeconds * 1000)).build());

        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOfSatisfying(PricingTokenExpiredException.class, e -> {
                    assertThat(e.getExpiredAt()).isEqualTo(Instant.ofEpochSecond(expSeconds));
                    assertThat(e.getCode()).isEqualTo("PRICING_TOKEN_EXPIRED");
                });
        assertThat(verifier(b -> b.leeway(Duration.ofSeconds(60))).verify(token).getUserId()).isEqualTo("user-1");
        String older = sign(key, claims("user-1", ORG).expirationTime(new Date((expSeconds - 110) * 1000)).build());
        assertThatThrownBy(() -> verifier(b -> b.leeway(Duration.ofSeconds(60))).verify(older))
                .isInstanceOf(PricingTokenExpiredException.class);
    }

    @Test
    @DisplayName("A token without exp is rejected")
    void noExp() throws Exception {
        String token = sign(key, claims("user-1", ORG).expirationTime(null).build());
        assertThatThrownBy(() -> verifier().verify(token))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_INVALID");
    }

    @Test
    @DisplayName("A token of another organization is rejected (aud and organizationId)")
    void otherOrganization() throws Exception {
        assertThatThrownBy(() -> verifier().verify(sign(key, claims("user-1", OTHER_ORG).build())))
                .isInstanceOfSatisfying(PricingTokenAudienceException.class, e -> {
                    assertThat(e.getExpected()).isEqualTo(ORG);
                    assertThat(e.getCode()).isEqualTo("PRICING_TOKEN_WRONG_AUDIENCE");
                });
        String mixed = sign(key, claims("user-1", OTHER_ORG).audience(ORG).build());
        assertThatThrownBy(() -> verifier().verify(mixed)).isInstanceOf(PricingTokenAudienceException.class);
    }

    @Test
    @DisplayName("The issuer is space by default, or the configured one")
    void issuer() throws Exception {
        String token = sign(key, claims("user-1", ORG).issuer("https://space.example.com").build());
        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOfSatisfying(PricingTokenIssuerException.class, e -> {
                    assertThat(e.getExpected()).isEqualTo("space");
                    assertThat(e.getCode()).isEqualTo("PRICING_TOKEN_WRONG_ISSUER");
                });
        PricingTokenVerifier custom = verifier(b -> b.issuer("https://space.example.com"));
        assertThat(custom.verify(token).getIss()).isEqualTo("https://space.example.com");
        assertThatThrownBy(() -> custom.verify(sign(key))).isInstanceOf(PricingTokenIssuerException.class);
    }

    @Test
    @DisplayName("An HS256 token is rejected, even with a published kid")
    void hs256() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(),
                claims("user-1", ORG).build());
        jwt.sign(new MACSigner("a-shared-secret-of-thirty-two-bytes!".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> verifier().verify(jwt.serialize()))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .hasMessageContaining("HS256");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"alg\":\"none\"}", "{\"alg\":\"none\",\"kid\":\"KID\"}", "{\"alg\":\"NONE\",\"kid\":\"KID\"}"})
    @DisplayName("An unsecured token (alg none) is rejected")
    void algNone(String header) throws Exception {
        String payload = claims("user-1", ORG).build().toString();
        String token = b64(header.replace("KID", key.getKeyID())) + "." + b64(payload) + ".";
        assertThatThrownBy(() -> verifier().verify(token))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_INVALID");
    }

    @Test
    @DisplayName("jku, x5u and embedded jwk headers are never followed")
    void forbiddenHeaders() throws Exception {
        ECKey attacker = newKey();
        java.net.URI attackerUrl = server.url("/attacker/jwks.json").uri();
        List<String> tokens = List.of(
                sign(attacker, new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(attacker.getKeyID()).jwkURL(attackerUrl).build(),
                        claims("user-1", ORG).build()),
                sign(attacker, new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(attacker.getKeyID()).x509CertURL(attackerUrl).build(),
                        claims("user-1", ORG).build()),
                sign(attacker, new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(attacker.getKeyID()).jwk(attacker.toPublicJWK()).build(),
                        claims("user-1", ORG).build()),
                // The right key and kid: SPACE never sends these headers.
                sign(key, new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).jwkURL(attackerUrl)
                        .type(JOSEObjectType.JWT).build(), claims("user-1", ORG).build()));
        for (String token : tokens) {
            assertThatThrownBy(() -> verifier().verify(token))
                    .isExactlyInstanceOf(PricingTokenVerificationException.class)
                    .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_INVALID");
        }
        assertThat(attackerRequests.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "a.b.c", "eyJhbGciOiJFUzI1NiJ9.e30."})
    @DisplayName("Malformed tokens are rejected")
    void malformed(String token) {
        assertThatThrownBy(() -> verifier().verify(token))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_INVALID");
    }

    @Test
    @DisplayName("A SPACE without pricing-token-jwks is reported")
    void unsupported() throws Exception {
        String token = sign(key);
        status = 404;
        capabilities = "contract-userid-per-org";
        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOfSatisfying(SpaceFeatureUnsupportedException.class, e -> {
                    assertThat(e.getCapability()).isEqualTo("pricing-token-jwks");
                    assertThat(e.isWritten()).isFalse();
                });
        status = 200; // legacy HS256 mode: the route answers, the capability is missing
        assertThatThrownBy(() -> verifier().verify(token)).isInstanceOf(SpaceFeatureUnsupportedException.class);
        capabilities = null; // a SPACE that predates X-Space-Capabilities
        assertThatThrownBy(() -> verifier().verify(token)).isInstanceOf(SpaceFeatureUnsupportedException.class);
    }

    @Test
    @DisplayName("An unavailable JWKS is a verification error")
    void unavailable() throws Exception {
        String token = sign(key);
        status = 503;
        assertThatThrownBy(() -> verifier().verify(token))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_JWKS_UNAVAILABLE");
        String deadUrl = spaceUrl();
        server.shutdown();
        assertThatThrownBy(() -> PricingTokenVerifier.builder(deadUrl).organizationId(ORG).build().verify(token))
                .isExactlyInstanceOf(PricingTokenVerificationException.class)
                .extracting(e -> ((PricingTokenVerificationException) e).getCode()).isEqualTo("PRICING_TOKEN_JWKS_UNAVAILABLE");
        server = new MockWebServer();
    }

    @Test
    @DisplayName("The JWKS is cached for its max-age")
    void maxAge() throws Exception {
        cacheControl = "public, max-age=10";
        AtomicLong now = new AtomicLong(1_000_000);
        PricingTokenVerifier verifier = verifier(b -> b.clock(now::get));
        String token = sign(key);
        verifier.verify(token);
        verifier.verify(token);
        now.addAndGet(9_000);
        verifier.verify(token);
        assertThat(jwksRequests.get()).isEqualTo(1);
        now.addAndGet(2_000);
        verifier.verify(token);
        assertThat(jwksRequests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Five minutes without Cache-Control, no caching with no-store")
    void defaultAndNoStore() throws Exception {
        cacheControl = null;
        AtomicLong now = new AtomicLong(1_000_000);
        PricingTokenVerifier verifier = verifier(b -> b.clock(now::get));
        String token = sign(key);
        verifier.verify(token);
        now.addAndGet(299_000);
        verifier.verify(token);
        assertThat(jwksRequests.get()).isEqualTo(1);
        now.addAndGet(2_000);
        verifier.verify(token);
        assertThat(jwksRequests.get()).isEqualTo(2);

        cacheControl = "no-store";
        PricingTokenVerifier noStore = verifier(b -> b.clock(now::get));
        noStore.verify(token);
        noStore.verify(token);
        assertThat(jwksRequests.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("An unknown kid refetches the JWKS (rotation), with a cooldown")
    void rotation() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        PricingTokenVerifier verifier = verifier(b -> b.clock(now::get).refetchCooldown(Duration.ofSeconds(30)));
        verifier.verify(sign(key));
        assertThat(jwksRequests.get()).isEqualTo(1);

        ECKey rotated = newKey();
        published = List.of(rotated.toPublicJWK().toJSONString(), key.toPublicJWK().toJSONString());
        now.addAndGet(31_000);
        assertThat(verifier.verify(sign(rotated)).getUserId()).isEqualTo("user-1");
        assertThat(jwksRequests.get()).isEqualTo(2);
        verifier.verify(sign(key));
        assertThat(jwksRequests.get()).isEqualTo(2);

        ECKey unknown = newKey();
        now.addAndGet(1_000);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> verifier.verify(sign(unknown))).isInstanceOf(PricingTokenUnknownKeyException.class);
        }
        assertThat(jwksRequests.get()).isEqualTo(2);
        now.addAndGet(30_000);
        assertThatThrownBy(() -> verifier.verify(sign(unknown))).isInstanceOf(PricingTokenUnknownKeyException.class);
        assertThat(jwksRequests.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("A removed key stops verifying once the cache expires")
    void removedKey() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        PricingTokenVerifier verifier = verifier(b -> b.clock(now::get));
        String token = sign(key);
        verifier.verify(token);
        published = List.of(newKey().toPublicJWK().toJSONString());
        now.addAndGet(301_000);
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(PricingTokenUnknownKeyException.class);
    }

    @Test
    @DisplayName("Published keys that are not ES256 signing keys are ignored")
    void notSigningKeys() throws Exception {
        String json = key.toPublicJWK().toJSONString();
        published = List.of(json.replace("\"use\":\"sig\"", "\"use\":\"enc\""), "{\"kty\":\"oct\",\"kid\":\"" + key.getKeyID() + "\",\"k\":\"c2VjcmV0\"}");
        assertThat(published.get(0)).contains("\"use\":\"enc\"");
        assertThatThrownBy(() -> verifier().verify(sign(key))).isInstanceOf(PricingTokenUnknownKeyException.class);
    }

    @Test
    @DisplayName("Concurrent verifications fetch the JWKS once")
    void concurrent() throws Exception {
        PricingTokenVerifier verifier = verifier();
        String token = sign(key);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<PricingTokenPayload>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> verifier.verify(token)));
            }
            for (Future<PricingTokenPayload> result : results) {
                assertThat(result.get().getUserId()).isEqualTo("user-1");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jwksRequests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("features.verifyPricingToken uses the organization of the client or of the call")
    void throughTheClient() throws Exception {
        SpaceConnectionOptions withOrg = new SpaceConnectionOptions(spaceUrl(), "usr_key");
        withOrg.setOrganizationId(ORG);
        SpaceClient client = new SpaceClient(withOrg);
        SpaceClient orgKeyClient = new SpaceClient(new SpaceConnectionOptions(spaceUrl(), "org-key"));
        try {
            String token = sign(key);
            assertThat(client.features.verifyPricingToken(token).getUserId()).isEqualTo("user-1");
            assertThat(orgKeyClient.features.verifyPricingToken(token, ORG).getUserId()).isEqualTo("user-1");
            assertThatThrownBy(() -> orgKeyClient.features.verifyPricingToken(token)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.features.verifyPricingToken(token, OTHER_ORG))
                    .isInstanceOf(PricingTokenAudienceException.class);
            assertThat(jwksRequests.get()).isEqualTo(2); // one cache per client
        } finally {
            client.close();
            orgKeyClient.close();
        }
    }

    @Test
    @DisplayName("Cache-Control parsing")
    void cacheControlParsing() {
        assertThat(PricingTokenVerifier.cacheMaxAgeMs(null)).isEqualTo(300_000L);
        assertThat(PricingTokenVerifier.cacheMaxAgeMs("public, max-age=300")).isEqualTo(300_000L);
        assertThat(PricingTokenVerifier.cacheMaxAgeMs("max-age=0")).isZero();
        assertThat(PricingTokenVerifier.cacheMaxAgeMs("no-cache, max-age=60")).isZero();
        assertThat(PricingTokenVerifier.cacheMaxAgeMs("max-age=abc")).isEqualTo(300_000L);
    }
}
