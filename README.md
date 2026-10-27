# Space Java Client

Java client library for [Space](https://github.com/isa-group/space) - A pricing-driven self-adaptation platform for SaaS applications.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.isa-group/space-java-client.svg)](https://search.maven.org/artifact/io.github.isa-group/space-java-client)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Features

- 🚀 Easy-to-use API for Space platform integration
- 📦 Contract management (create, retrieve, update)
- ✨ Feature evaluation with caching support
- 💾 Built-in and Redis cache providers
- 🔌 WebSocket support for real-time events
- 📊 Pricing token generation
- ⚡ Thread-safe and production-ready

## Requirements

- Java 11 or higher
- Maven 3.6+

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.isa-group</groupId>
    <artifactId>space-java-client</artifactId>
    <version>0.3.0</version>
</dependency>
```

## Usage Guide

### 1) Plain Java Application

Use `SpaceClientFactory` to create a client and call modules directly.

```java
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.SpaceClientFactory;
import io.github.isagroup.spaceclient.types.FeatureEvaluationResult;

SpaceClient client = SpaceClientFactory.connect(
    "http://localhost:3000",
    "your-api-key",
    10000
);

FeatureEvaluationResult evaluation = client.features.evaluate("user123", "serviceA-featureX");
if (evaluation.getEval()) {
    System.out.println("Feature enabled");
}

String pricingToken = client.features.generateUserPricingToken("user123");
System.out.println(pricingToken);

client.close();
```

### 2) Spring Application

For Spring integration, configure a `SpaceClient` bean and a `SpringPricingConfigurator` implementation.
If you use `@RequireFeature`, make sure Spring AOP is enabled in your project (for example, with `spring-boot-starter-aop`).

#### 2.1 Configure properties

```properties
space.client.url=http://localhost:3000
space.client.api-key=your-api-key
space.client.timeout=10000
```

#### 2.2 Register configuration

```java
import io.github.isagroup.spaceclient.spring.SpaceClientAutoConfiguration;
import io.github.isagroup.spaceclient.spring.config.SpringPricingConfigurator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@EnableAspectJAutoProxy
@Import(SpaceClientAutoConfiguration.class)
public class SpacePricingConfig {

    @Bean
    public SpringPricingConfigurator springPricingConfigurator() {
        return new SpringPricingConfigurator() {
            @Override
            public String resolveUserId(ProceedingJoinPoint joinPoint) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                return auth != null ? auth.getName() : null;
            }
        };
    }
}
```

#### 2.3 Protect methods with `@RequireFeature`

```java
import io.github.isagroup.spaceclient.annotations.RequireFeature;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

    @RequireFeature("premium-export")
    public String exportReport() {
        return "exported";
    }
}
```

See the complete annotation guide in [REQUIRE_FEATURE_ANNOTATION.md](REQUIRE_FEATURE_ANNOTATION.md).

## Quick Start

### Basic Connection

```java
import io.github.isagroup.spaceclient.SpaceClient;
import io.github.isagroup.spaceclient.SpaceClientFactory;
import io.github.isagroup.spaceclient.types.SpaceConnectionOptions;

// Simple connection
SpaceClient client = SpaceClientFactory.connect("http://localhost:3000", "your-api-key");

// With custom timeout
SpaceClient client = SpaceClientFactory.connect("http://localhost:3000", "your-api-key", 10000);

// With options object
SpaceConnectionOptions options = new SpaceConnectionOptions(
    "http://localhost:3000",
    "your-api-key",
    5000
);
SpaceClient client = SpaceClientFactory.connect(options);
```

### Contract Management

```java
import io.github.isagroup.spaceclient.types.*;
import java.util.Map;
import java.util.HashMap;

// Create a new contract
UserContact userContact = new UserContact("user123", "john_doe");
userContact.setEmail("john@example.com");

Map<String, String> contractedServices = new HashMap<>();
contractedServices.put("serviceA", "pricing/v1");

Map<String, String> subscriptionPlans = new HashMap<>();
subscriptionPlans.put("serviceA", "basic");

Map<String, Map<String, Integer>> subscriptionAddOns = new HashMap<>();

ContractToCreate contractToCreate = new ContractToCreate(
    userContact,
    contractedServices,
    subscriptionPlans,
    subscriptionAddOns
);

Contract contract = client.contracts.addContract(contractToCreate);

// Get existing contract
Contract contract = client.contracts.getContract("user123");

// Update subscription
Subscription newSubscription = new Subscription(
    contractedServices,
    Map.of("serviceA", "premium"),
    subscriptionAddOns
);
Contract updated = client.contracts.updateContractSubscription("user123", newSubscription);
```

### Feature Evaluation

```java
import io.github.isagroup.spaceclient.types.FeatureEvaluationResult;
import java.util.Map;
import java.util.HashMap;

// Simple evaluation (read-only)
FeatureEvaluationResult result = client.features.evaluate("user123", "serviceA-featureX");

if (result.getEval()) {
    System.out.println("Feature is enabled for user");
}

// Evaluation with consumption
Map<String, Number> consumption = new HashMap<>();
consumption.put("requests", 10);
consumption.put("storage", 1024);

FeatureEvaluationResult result = client.features.evaluate(
    "user123", 
    "serviceA-featureX", 
    consumption
);

// Evaluation with options
FeatureEvaluationResult result = client.features.evaluate(
    "user123",
    "serviceA-featureX",
    consumption,
    true,  // details
    false  // server
);

// Revert evaluation (for failed transactions)
boolean reverted = client.features.revertEvaluation("user123", "serviceA-featureX");

// Generate pricing token
String token = client.features.generateUserPricingToken("user123");
```

### Caching

The client supports two caching strategies: built-in (in-memory) and Redis.

#### Built-in Cache

```java
import io.github.isagroup.spaceclient.types.CacheOptions;
import io.github.isagroup.spaceclient.types.CacheOptions.CacheType;

CacheOptions cacheOptions = new CacheOptions(true, CacheType.BUILTIN, 300);

SpaceConnectionOptions options = new SpaceConnectionOptions(
    "http://localhost:3000",
    "your-api-key",
    5000,
    cacheOptions
);

SpaceClient client = SpaceClientFactory.connect(options);
```

#### Redis Cache

```java
import io.github.isagroup.spaceclient.types.CacheOptions;
import io.github.isagroup.spaceclient.types.CacheOptions.*;

RedisConfig redisConfig = new RedisConfig("localhost", 6379);
redisConfig.setPassword("your-password"); // Optional
redisConfig.setDb(0);

ExternalCacheConfig externalConfig = new ExternalCacheConfig();
externalConfig.setRedis(redisConfig);

CacheOptions cacheOptions = new CacheOptions(true, CacheType.REDIS, 300);
cacheOptions.setExternal(externalConfig);

SpaceConnectionOptions options = new SpaceConnectionOptions(
    "http://localhost:3000",
    "your-api-key",
    5000,
    cacheOptions
);

SpaceClient client = SpaceClientFactory.connect(options);
```

### WebSocket Events

```java
// Register event handlers
client.on("synchronized", data -> {
    System.out.println("Connected to Space WebSocket");
});

client.on("pricing_created", data -> {
    System.out.println("New pricing created: " + data);
});

client.on("pricing_archived", data -> {
    System.out.println("Pricing archived: " + data);
});

client.on("pricing_actived", data -> {
    System.out.println("Pricing activated: " + data);
});

client.on("service_disabled", data -> {
    System.out.println("Service disabled: " + data);
});

client.on("error", error -> {
    System.err.println("WebSocket error: " + error);
});

// Connect to WebSocket
client.connect();

// Remove specific listener
client.removeListener("pricing_created");

// Remove all listeners
client.removeAllListeners();

// Disconnect
client.disconnect();
```

### Pricing Pages

A host backend (for example a Spring service holding the SPACE org key) renders a pricing page
with the version a visitor would get, or with the version a signed-in customer is on:

```java
import io.github.isagroup.spaceclient.exceptions.PricingYamlUnavailableException;
import io.github.isagroup.spaceclient.types.*;

// Signed-out visitor: the version SPACE assigns to new contracts.
NewContractsPricing current = client.services.getNewContractsPricing("acme");
PricingYaml pricing = client.pricings.getYaml("acme", current.getVersion());
// current.getSource(): "sphere-policy" | "default" | "latest-active"

// Signed-in customer: the version of THEIR contract.
ContractPricings mine = client.contracts.getPricings("user123");
ContractServicePricing acme = mine.getServices().get("acme");
acme.getVersion(); acme.isArchived(); acme.getPlan(); acme.getAddOns(); acme.getNewContractsVersion();
PricingYaml theirs = client.pricings.getYaml("acme", acme.getVersion());

// The file is returned byte for byte; keep its ETag to revalidate cheaply.
PricingYaml again = client.pricings.getYaml("acme", acme.getVersion(), theirs.getEtag());
if (again.isNotModified()) {
    // reuse theirs.getYaml()
}
```

`getYaml` throws `PricingYamlUnavailableException` when SPACE knows the version but no longer has
its original file. `getPricings` is never served from cache.

### Cleanup

Always close the client when done to release resources:

```java
// Close client and cleanup resources
client.close();
```

## API Reference

### SpaceClient

Main client class for interacting with the Space platform.

**Methods:**
- `isConnectedToSpace()`: Check if connected to Space API
- `on(String event, Consumer<Object> callback)`: Register event listener
- `removeListener(String event)`: Remove specific event listener
- `removeAllListeners()`: Remove all event listeners
- `connect()`: Connect to WebSocket
- `disconnect()`: Disconnect from WebSocket
- `close()`: Close all connections and cleanup
- `contracts`: ContractModule instance (for managing contracts)
- `features`: FeatureModule instance (for evaluating features)
- `pricings`: PricingModule instance (for reading Pricing2Yaml files)
- `services`: ServiceModule instance (for service-level information)
- `cache`: CacheModule instance (for cache management)

### ContractModule

Manage user contracts.

**Methods:**
- `getContract(String userId)`: Retrieve user contract
- `addContract(ContractToCreate contract)`: Create new contract
- `updateContractSubscription(String userId, Subscription subscription)`: Update subscription
- `getPricings(String userId)`: Pricing version, status, plan and add-ons of every service of the contract, plus its `contractVersion` (`ContractPricings`)

### PricingModule

**Methods:**
- `getYaml(String serviceName, String pricingVersion)`: Raw Pricing2Yaml of a version (`PricingYaml`: YAML, ETag, syntax version)
- `getYaml(String serviceName, String pricingVersion, String etag)`: Conditional variant (`isNotModified()` on 304)

### ServiceModule

**Methods:**
- `getNewContractsPricing(String serviceName)`: The version SPACE assigns to new contracts (`NewContractsPricing`)

### FeatureModule

Evaluate features and manage pricing.

**Methods:**
- `evaluate(String userId, String featureId)`: Evaluate feature (read-only)
- `evaluate(String userId, String featureId, Map<String, Number> consumption)`: Evaluate with consumption
- `evaluate(String userId, String featureId, Map<String, Number> consumption, boolean details, boolean server)`: Full evaluation
- `revertEvaluation(String userId, String featureId)`: Revert evaluation
- `revertEvaluation(String userId, String featureId, boolean revertToLatest)`: Revert with options
- `generateUserPricingToken(String userId)`: Generate pricing token

## Events

Available WebSocket events:
- `synchronized`: Connected to WebSocket
- `pricing_created`: New pricing created
- `pricing_archived`: Pricing archived
- `pricing_actived`: Pricing activated
- `service_disabled`: Service disabled
- `error`: Connection error

## Error Handling

The methods of the subscription-change API (`pricings.getYaml`, `contracts.getPricings`,
`services.getNewContractsPricing`, and the ones added with them) throw unchecked exceptions instead
of returning `null`:

- `SpaceApiException`: SPACE refused (non-2xx). Carries `getStatusCode()`, `getMethod()`,
  `getPath()`, `getBody()` and `getErrorCode()` (the `error` field of a JSON body).
- `PricingYamlUnavailableException` (a `SpaceApiException`): `404 PRICING_YAML_UNAVAILABLE`.
- `java.io.UncheckedIOException`: SPACE could not be reached.

The older methods keep their existing behaviour (they log the failure and return `null`):

```java
Contract contract = client.contracts.getContract("user123");
if (contract == null) {
    System.err.println("Failed to get contract (see the logs)");
}
```

## Thread Safety

SpaceClient is thread-safe and can be used concurrently from multiple threads. The cache implementations use thread-safe data structures.

## Publishing to Maven Central

This package is configured for publishing to Maven Central. To publish:

1. Configure your `~/.m2/settings.xml` with OSSRH credentials
2. Generate GPG keys for signing
3. Run: `mvn clean deploy -P release`

See [Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-guide/) for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Authors

- ISA-Group

## Links

- [Space Platform](https://github.com/isa-group/space)
- [API Documentation](https://github.com/isa-group/space/wiki)
- [Issue Tracker](https://github.com/Alex-GF/space-java-client/issues)

