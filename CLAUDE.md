# CLAUDE.md — http-client

## Project Overview

Java 21 Maven project providing a shared-IOReactor async HTTP client factory with per-device TLS certificate routing. Built on Apache HttpClient 5.5 / HttpCore 5.3.4.

Supports up to 64 device IDs and 3000 concurrent connections through a single `CloseableHttpAsyncClient`.

## Architecture

Single `CloseableHttpAsyncClient` with a custom `AsyncClientConnectionOperator` that intercepts TLS handshakes. Device identity is passed via `HttpContext` attributes (`retrieve.deviceId`, `retrieve.verifyCert`). A `UserTokenHandler` returns `DeviceKey` as connection pool state to prevent cross-device connection reuse.

```
HttpRetrieveAsyncClientFactory  -- owns the shared CloseableHttpAsyncClient
  └─ HttpRetrieveAsyncClient    -- lightweight per-device wrapper, injects context
  └─ DeviceAwareConnectionOperator  -- intercepts TLS, selects per-device TlsStrategy
  └─ SSLContextFactory / DeviceKey  -- SSL context creation and cache key
```

## Key Design Decisions

- **Cannot extend `DefaultAsyncClientConnectionOperator`** — it is package-private in HC5 5.5. `DeviceAwareConnectionOperator` implements `AsyncClientConnectionOperator` directly with an inner `DefaultManagedAsyncClientConnectionShim` that wraps `IOSession`.
- **`TlsStrategy.upgrade()` does NOT receive `HttpContext`** — confirmed via bytecode analysis. The only extension points with HttpContext are `AsyncClientConnectionOperator.connect()` and `upgrade()` default methods.
- **`PoolingAsyncClientConnectionManagerBuilder.setSchemePortResolver/setDnsResolver` are `final`** — `DeviceAwareConnMgrBuilder` provides `withSchemePortResolver()/withDnsResolver()` as alternatives.
- **`init()` and `shutdown()` are `synchronized`** — lifecycle methods are thread-safe. `getClient()` reads `httpClient` into a local variable for safe concurrent access.
- **SSLContext creation happens outside `computeIfAbsent`** — avoids side-effecting lambdas and ensures exceptions don't corrupt the cache.

## Package Structure

```
uds.osc.retrieve.client/
├── DeviceKey.java                          — record(deviceId, verifyCert), cache key
├── SSLContextFactory.java                  — createTrustAllSSLContext / createDeviceSSLContext
├── DeviceAwareConnectionOperator.java      — core: intercepts TLS with per-device strategy
├── DeviceAwareConnMgrBuilder.java          — builder hook for custom operator injection
├── HttpRetrieveAsyncClient.java            — wrapper, injects device context per request
└── HttpRetrieveAsyncClientFactory.java     — entry point, shared client lifecycle
```

## HttpContext Attributes

Defined as public constants on `DeviceAwareConnectionOperator`:
- `CTX_DEVICE_ID = "retrieve.deviceId"` — String
- `CTX_VERIFY_CERT = "retrieve.verifyCert"` — Boolean

All classes reference these constants, never raw strings.

## Certificate Files

- Path pattern: `certs/HDSC_${deviceId}.crt` (X.509, used as trust anchor for single-sided TLS)
- `isVerifyCert=false` → trustAll SSLContext (skips all verification)
- Certificates are loaded lazily on first `getClient()` call and cached for the lifetime of the factory

## Connection Pool Configuration

| Parameter | Value |
|-----------|-------|
| maxConnTotal | 3000 |
| maxConnPerRoute | 3000 |
| connectionTimeToLive | 25s |
| validateAfterInactivity | 10s |
| poolConcurrencyPolicy | LAX |
| connPoolPolicy | LIFO |
| keepAlive | 20s |
| ioThreadCount | 64 |
| connectTimeout | 30s |

## Build & Test

```bash
mvn compile          # compile only
mvn test             # run all tests (15 tests)
mvn test -Dtest=HttpRetrieveAsyncClientFactoryTest  # factory tests only
```

## Dependencies

- `org.apache.httpcomponents.client5:httpclient5:5.5`
- `org.apache.httpcomponents.core5:httpcore5:5.3.4`
- `org.slf4j:slf4j-api:2.0.16`
- `ch.qos.logback:logback-classic:1.5.16` (test)
- `org.junit.jupiter:junit-jupiter:5.11.4` (test)
