package io.github.isagroup.spaceclient.tokens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.Header;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.isagroup.spaceclient.exceptions.PricingTokenAudienceException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenExpiredException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenIssuerException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenSignatureException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenUnknownKeyException;
import io.github.isagroup.spaceclient.exceptions.PricingTokenVerificationException;
import io.github.isagroup.spaceclient.exceptions.SpaceFeatureUnsupportedException;
import io.github.isagroup.spaceclient.types.PricingTokenPayload;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Verifies pricing tokens with the public keys SPACE publishes (JWKS, capability
 * {@value #PRICING_TOKEN_JWKS}) at {@code GET /api/v1/.well-known/jwks.json}.
 *
 * Checks the ES256 signature by the published key the token's {@code kid} names (keys are never
 * taken from {@code jwk}, {@code jku}, {@code x5u} or {@code x5c} headers, and any other
 * algorithm is rejected), {@code exp} (required, with an optional leeway), {@code iss}
 * ({@value #DEFAULT_ISSUER} unless configured) and {@code aud}/{@code organizationId} (the expected
 * organization, required). The JWKS is cached for the {@code max-age} of its response (five minutes
 * without one) and fetched again when a token names an unknown {@code kid}, at most once per
 * refetch cooldown (30 seconds by default).
 *
 * Thread-safe. Keep one per SPACE and reuse it, so that the cached keys are shared. Signatures are
 * checked with Nimbus JOSE + JWT; the JWKS is fetched with OkHttp.
 */
public final class PricingTokenVerifier {

    /** The {@code X-Space-Capabilities} token of SPACE versions that publish the keys. */
    public static final String PRICING_TOKEN_JWKS = "pricing-token-jwks";

    /** The {@code iss} of pricing tokens when SPACE has no {@code JWT_ISSUER} configured. */
    public static final String DEFAULT_ISSUER = "space";

    static final long DEFAULT_CACHE_MAX_AGE_MS = 300_000L;
    private static final Set<String> FORBIDDEN_HEADERS =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("jwk", "jku", "x5u", "x5c")));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jwksUrl;
    private final String organizationId;
    private final String issuer;
    private final long leewaySeconds;
    private final long refetchCooldownMs;
    private final OkHttpClient httpClient;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private volatile KeySet keySet;
    private long lastFetchAt = Long.MIN_VALUE;

    private PricingTokenVerifier(Builder builder) {
        this.jwksUrl = builder.jwksUrl;
        this.organizationId = builder.organizationId;
        this.issuer = builder.issuer;
        this.leewaySeconds = builder.leeway.getSeconds();
        this.refetchCooldownMs = builder.refetchCooldown.toMillis();
        OkHttpClient base = builder.httpClient != null ? builder.httpClient : new OkHttpClient();
        this.httpClient = base.newBuilder()
                .callTimeout(builder.timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
        this.clock = builder.clock;
    }

    /**
     * A builder for the SPACE at {@code spaceUrl} (the URL given to the client; the JWKS is read from
     * {@code {spaceUrl}/api/v1/.well-known/jwks.json}).
     */
    public static Builder builder(String spaceUrl) {
        Objects.requireNonNull(spaceUrl, "spaceUrl");
        String base = spaceUrl.replaceAll("/+$", "");
        return new Builder(base + "/api/v1/.well-known/jwks.json");
    }

    /** A builder for a JWKS at {@code jwksUrl} itself. */
    public static Builder builderForJwksUrl(String jwksUrl) {
        return new Builder(Objects.requireNonNull(jwksUrl, "jwksUrl"));
    }

    /**
     * Verifies {@code token} for the organization configured in the builder.
     *
     * @see #verify(String, String)
     */
    public PricingTokenPayload verify(String token) {
        return verify(token, null);
    }

    /**
     * Verifies {@code token} and returns its payload.
     *
     * @param token          the pricing token
     * @param organizationId the organization it must be issued for; {@code null} for the one of the
     *                       builder
     * @return the verified payload
     * @throws IllegalArgumentException          without an organizationId (here or in the builder)
     * @throws PricingTokenVerificationException or a subclass (signature, expired, audience, issuer,
     *                                           unknown kid) when the token cannot be trusted
     * @throws SpaceFeatureUnsupportedException  when SPACE does not publish its keys
     *                                           ({@value #PRICING_TOKEN_JWKS})
     */
    public PricingTokenPayload verify(String token, String organizationId) {
        String organization = organizationId != null && !organizationId.isEmpty() ? organizationId : this.organizationId;
        if (organization == null || organization.isEmpty()) {
            throw new IllegalArgumentException("Verifying a pricing token needs the organizationId it must be issued for");
        }
        String kid = checkHeader(token);
        ECKey key = keySetFor(kid).keys.get(kid);
        if (key == null) {
            throw new PricingTokenUnknownKeyException(kid);
        }

        JWTClaimsSet claims;
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new ECDSAVerifier(key))) {
                throw new PricingTokenSignatureException();
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            throw new PricingTokenVerificationException(PricingTokenVerificationException.INVALID,
                    "The pricing token is invalid: " + e.getMessage(), e);
        }
        checkClaims(claims, organization);
        return PricingTokenPayload.decode(token);
    }

    /** Forgets the cached JWKS, so that the next verification fetches it again. */
    public void clearCache() {
        keySet = null;
    }

    // -- claims -----------------------------------------------------------------------------

    private void checkClaims(JWTClaimsSet claims, String organization) {
        long now = System.currentTimeMillis();
        long leewayMs = leewaySeconds * 1000L;
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw invalid("The pricing token has no exp claim");
        }
        if (now >= exp.getTime() + leewayMs) {
            throw new PricingTokenExpiredException(exp.toInstant());
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && now + leewayMs < nbf.getTime()) {
            throw invalid("The pricing token is not valid yet (nbf)");
        }
        if (claims.getSubject() == null) {
            throw invalid("The pricing token has no sub claim");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new PricingTokenIssuerException(issuer, claims.getIssuer());
        }
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(organization)) {
            throw new PricingTokenAudienceException(organization, audience);
        }
        Object tokenOrganization = claims.getClaim("organizationId");
        if (!organization.equals(tokenOrganization)) {
            throw new PricingTokenAudienceException(organization, tokenOrganization);
        }
    }

    private static PricingTokenVerificationException invalid(String message) {
        return new PricingTokenVerificationException(PricingTokenVerificationException.INVALID, message);
    }

    /** Checks the protected header before any key is looked up, and returns its {@code kid}. */
    private static String checkHeader(String token) {
        Header header;
        try {
            if (token == null) {
                throw new ParseException("no token", 0);
            }
            Base64URL[] parts = JOSEObject.split(token);
            if (parts.length != 3) {
                throw new ParseException("not a JWS in compact serialization", 0);
            }
            header = Header.parse(parts[0]);
        } catch (ParseException e) {
            throw new PricingTokenVerificationException(PricingTokenVerificationException.INVALID,
                    "The pricing token is malformed", e);
        }
        if (!(header instanceof JWSHeader) || !JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            throw invalid("Pricing tokens must be signed with ES256, not " + header.getAlgorithm());
        }
        for (String name : header.getIncludedParams()) {
            if (FORBIDDEN_HEADERS.contains(name)) {
                throw invalid("The pricing token has a \"" + name + "\" header; keys are only taken from the JWKS of SPACE");
            }
        }
        String kid = ((JWSHeader) header).getKeyID();
        if (kid == null || kid.isEmpty()) {
            throw invalid("The pricing token names no key (kid)");
        }
        return kid;
    }

    // -- JWKS -------------------------------------------------------------------------------

    private static final class KeySet {
        final Map<String, ECKey> keys;
        final long expiresAt;

        KeySet(Map<String, ECKey> keys, long expiresAt) {
            this.keys = keys;
            this.expiresAt = expiresAt;
        }
    }

    private KeySet keySetFor(String kid) {
        synchronized (lock) {
            KeySet current = keySet;
            long now = clock.getAsLong();
            if (current != null && now < current.expiresAt) {
                boolean cooldown = lastFetchAt != Long.MIN_VALUE && now - lastFetchAt < refetchCooldownMs;
                if (current.keys.containsKey(kid) || cooldown) {
                    return current;
                }
            }
            return fetch();
        }
    }

    private KeySet fetch() {
        lastFetchAt = clock.getAsLong();
        Request request = new Request.Builder()
                .url(jwksUrl)
                .header("Accept", "application/jwk-set+json, application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            // Every SPACE response carries X-Space-Capabilities (S0). Without the token the server
            // is older or in legacy HS256 mode; without the header it predates S0, unless it is a
            // 5xx, which may come from a proxy.
            String capabilities = response.header("X-Space-Capabilities");
            boolean unsupported = capabilities == null
                    ? response.code() < 500
                    : !hasToken(capabilities, PRICING_TOKEN_JWKS);
            if (unsupported) {
                throw new SpaceFeatureUnsupportedException(PRICING_TOKEN_JWKS,
                        "The connected SPACE does not publish the keys of its pricing tokens (pricing-token-jwks): "
                                + "it is older, or signs them with the legacy HS256");
            }
            ResponseBody body = response.body();
            JsonNode json = response.code() == 200 && body != null ? readJson(body.string()) : null;
            if (json == null || !json.path("keys").isArray()) {
                throw new PricingTokenVerificationException(PricingTokenVerificationException.JWKS_UNAVAILABLE,
                        "SPACE answered " + response.code() + " to " + jwksUrl);
            }
            Map<String, ECKey> keys = new HashMap<>();
            for (JsonNode jwk : json.get("keys")) {
                ECKey key = es256SigningKey(jwk);
                if (key != null) {
                    keys.put(key.getKeyID(), key);
                }
            }
            KeySet fetched = new KeySet(Collections.unmodifiableMap(keys),
                    clock.getAsLong() + cacheMaxAgeMs(response.header("Cache-Control")));
            keySet = fetched;
            return fetched;
        } catch (IOException e) {
            throw new PricingTokenVerificationException(PricingTokenVerificationException.JWKS_UNAVAILABLE,
                    "Could not fetch the pricing token keys from " + jwksUrl, e);
        }
    }

    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    private static ECKey es256SigningKey(JsonNode jwk) {
        if (!jwk.isObject()
                || !"EC".equals(jwk.path("kty").asText(null))
                || !"P-256".equals(jwk.path("crv").asText(null))
                || !jwk.path("kid").isTextual()
                || jwk.has("d")
                || (jwk.has("alg") && !"ES256".equals(jwk.path("alg").asText(null)))
                || (jwk.has("use") && !"sig".equals(jwk.path("use").asText(null)))) {
            return null;
        }
        try {
            ECKey key = ECKey.parse(jwk.toString());
            return Curve.P_256.equals(key.getCurve()) && !key.isPrivate() ? key : null;
        } catch (ParseException e) {
            return null;
        }
    }

    private static boolean hasToken(String header, String token) {
        for (String part : header.split(",")) {
            if (part.trim().toLowerCase(Locale.ROOT).equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** {@code max-age} of a Cache-Control header in ms; 0 for {@code no-store}/{@code no-cache}; five minutes if absent. */
    static long cacheMaxAgeMs(String header) {
        if (header == null || header.trim().isEmpty()) {
            return DEFAULT_CACHE_MAX_AGE_MS;
        }
        long maxAge = -1;
        for (String directive : header.toLowerCase(Locale.ROOT).split(",")) {
            String d = directive.trim();
            if (d.equals("no-store") || d.equals("no-cache")) {
                return 0;
            }
            if (d.startsWith("max-age=") && maxAge < 0) {
                try {
                    maxAge = Long.parseLong(d.substring("max-age=".length())) * 1000L;
                } catch (NumberFormatException e) {
                    maxAge = -1;
                }
            }
        }
        return maxAge >= 0 ? maxAge : DEFAULT_CACHE_MAX_AGE_MS;
    }

    /** Options of a {@link PricingTokenVerifier}. */
    public static final class Builder {
        private final String jwksUrl;
        private String organizationId;
        private String issuer = DEFAULT_ISSUER;
        private Duration leeway = Duration.ZERO;
        private Duration refetchCooldown = Duration.ofSeconds(30);
        private Duration timeout = Duration.ofSeconds(5);
        private OkHttpClient httpClient;
        private LongSupplier clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

        private Builder(String jwksUrl) {
            this.jwksUrl = jwksUrl;
        }

        /** The organization tokens must be issued for; required here or on every {@code verify}. */
        public Builder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        /** The expected {@code iss} (the {@code JWT_ISSUER} of your SPACE). Default {@code space}. */
        public Builder issuer(String issuer) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            return this;
        }

        /** Tolerance for {@code exp}/{@code nbf}. Default zero. */
        public Builder leeway(Duration leeway) {
            this.leeway = Objects.requireNonNull(leeway, "leeway");
            return this;
        }

        /** Minimum time between two fetches triggered by an unknown {@code kid}. Default 30 seconds. */
        public Builder refetchCooldown(Duration refetchCooldown) {
            this.refetchCooldown = Objects.requireNonNull(refetchCooldown, "refetchCooldown");
            return this;
        }

        /** Timeout of the JWKS request. Default 5 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** The OkHttp client to derive the JWKS client from (its connection pool is shared). */
        public Builder httpClient(OkHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Monotonic clock of the JWKS cache and the refetch cooldown, in ms (tests). */
        public Builder clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public PricingTokenVerifier build() {
            return new PricingTokenVerifier(this);
        }
    }
}
