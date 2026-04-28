# CloseableHttpAsyncClient Return Type — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `HttpRetrieveAsyncClientFactory#getClient` return `CloseableHttpAsyncClient` by having `HttpRetrieveAsyncClient` extend `CloseableHttpAsyncClient` and delegate all abstract methods.

**Architecture:** `HttpRetrieveAsyncClient` becomes a `CloseableHttpAsyncClient` subclass that wraps the shared delegate client. Device context injection moves from custom `execute` methods to the `doExecute()` override. All other abstract methods (`start`, `getStatus`, `awaitShutdown`, `initiateShutdown`, `register`) delegate directly. `close()` remains a no-op since lifecycle is managed by the factory.

**Tech Stack:** Java 21, Apache HttpClient 5.5, HttpCore 5.3.4, JUnit 5

---

### Task 1: Rewrite HttpRetrieveAsyncClient to extend CloseableHttpAsyncClient

**Files:**
- Modify: `src/main/java/uds/osc/retrieve/client/HttpRetrieveAsyncClient.java`

- [ ] **Step 1: Rewrite HttpRetrieveAsyncClient.java**

Replace the entire file. The class now extends `CloseableHttpAsyncClient`, delegates all abstract methods to the wrapped `delegate`, and overrides `doExecute()` to inject device context before delegating. Remove the 4 custom `execute` methods and the auxiliary getters (`getDeviceId`, `isVerifyCert`, `getHttpClient`).

```java
package uds.osc.retrieve.client;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Supplier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.TimeValue;

import java.util.concurrent.Future;

public class HttpRetrieveAsyncClient extends CloseableHttpAsyncClient {

    private static final String CTX_DEVICE_ID = DeviceAwareConnectionOperator.CTX_DEVICE_ID;
    private static final String CTX_VERIFY_CERT = DeviceAwareConnectionOperator.CTX_VERIFY_CERT;

    private final String deviceId;
    private final boolean verifyCert;
    private final CloseableHttpAsyncClient delegate;

    public HttpRetrieveAsyncClient(String deviceId, boolean verifyCert, CloseableHttpAsyncClient delegate) {
        this.deviceId = deviceId;
        this.verifyCert = verifyCert;
        this.delegate = delegate;
    }

    @Override
    protected <T> Future<T> doExecute(HttpHost target, AsyncRequestProducer requestProducer,
                                       AsyncResponseConsumer<T> responseConsumer,
                                       HandlerFactory<AsyncPushConsumer> pushHandlerFactory,
                                       HttpContext context, FutureCallback<T> callback) {
        HttpContext effectiveContext = context;
        if (context instanceof HttpClientContext) {
            context.setAttribute(CTX_DEVICE_ID, deviceId);
            context.setAttribute(CTX_VERIFY_CERT, verifyCert);
        } else {
            HttpClientContext clientContext = HttpClientContext.create();
            if (context != null) {
                context.getAttributes().forEach(clientContext::setAttribute);
            }
            clientContext.setAttribute(CTX_DEVICE_ID, deviceId);
            clientContext.setAttribute(CTX_VERIFY_CERT, verifyCert);
            effectiveContext = clientContext;
        }
        return delegate.doExecute(target, requestProducer, responseConsumer,
                pushHandlerFactory, effectiveContext, callback);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public IOReactorStatus getStatus() {
        return delegate.getStatus();
    }

    @Override
    public void awaitShutdown(TimeValue waitTime) throws InterruptedException {
        delegate.awaitShutdown(waitTime);
    }

    @Override
    public void initiateShutdown() {
        delegate.initiateShutdown();
    }

    @Override
    public void register(String hostname, String uriPattern, Supplier<AsyncPushConsumer> supplier) {
        delegate.register(hostname, uriPattern, supplier);
    }

    @Override
    public void close(CloseMode closeMode) {
        // Lifecycle managed by HttpRetrieveAsyncClientFactory
    }

    @Override
    public void close() {
        // Lifecycle managed by HttpRetrieveAsyncClientFactory
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uds/osc/retrieve/client/HttpRetrieveAsyncClient.java
git commit -m "refactor: HttpRetrieveAsyncClient extends CloseableHttpAsyncClient

Delegate all abstract methods to the shared client. Override doExecute()
to inject device context. Remove custom execute methods and auxiliary getters."
```

---

### Task 2: Update getClient return type in HttpRetrieveAsyncClientFactory

**Files:**
- Modify: `src/main/java/uds/osc/retrieve/client/HttpRetrieveAsyncClientFactory.java:75-101`

- [ ] **Step 1: Change the getClient return type**

Change line 75 from:
```java
public HttpRetrieveAsyncClient getClient(String deviceId, boolean isVerifyCert) {
```
to:
```java
public CloseableHttpAsyncClient getClient(String deviceId, boolean isVerifyCert) {
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (factory still returns `HttpRetrieveAsyncClient` which is now a `CloseableHttpAsyncClient`)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uds/osc/retrieve/client/HttpRetrieveAsyncClientFactory.java
git commit -m "refactor: change getClient return type to CloseableHttpAsyncClient"
```

---

### Task 3: Update HttpRetrieveAsyncClientFactoryTest

**Files:**
- Modify: `src/test/java/uds/osc/retrieve/client/HttpRetrieveAsyncClientFactoryTest.java`

The tests currently use `HttpRetrieveAsyncClient` directly and call removed methods (`getDeviceId()`, `isVerifyCert()`, `getHttpClient()`). Update to use `CloseableHttpAsyncClient` return type and adjust assertions.

- [ ] **Step 1: Rewrite the test file**

```java
package uds.osc.retrieve.client;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRetrieveAsyncClientFactoryTest {

    private HttpRetrieveAsyncClientFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.shutdown();
        }
    }

    @Test
    void getClient_noVerify_returnsClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client);
    }

    @Test
    void getClient_sameKey_returnsSameUnderlyingClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient a = factory.getClient("dev1", false);
        CloseableHttpAsyncClient b = factory.getClient("dev1", false);
        assertNotNull(a);
        assertNotNull(b);
        // Both wrap the same delegate, so getStatus should reflect shared reactor
        assertEquals(a.getStatus(), b.getStatus());
    }

    @Test
    void getClient_differentDevices_sameUnderlyingClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient dev1 = factory.getClient("dev1", false);
        CloseableHttpAsyncClient dev2 = factory.getClient("dev2", false);
        // Both share the same IOReactor, so status must be identical
        assertEquals(dev1.getStatus(), dev2.getStatus());
        assertSame(dev1.getStatus(), dev2.getStatus());
    }

    @Test
    void getClient_verifyCertTrue_missingCert_throws() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        assertThrows(RuntimeException.class, () -> factory.getClient("nonexistent", true));
    }

    @Test
    void getClient_withoutInit_throws() {
        factory = new HttpRetrieveAsyncClientFactory();
        assertThrows(IllegalStateException.class, () -> factory.getClient("dev1", false));
    }

    @Test
    void shutdown_closesGracefully() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();
        CloseableHttpAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client);
        factory.shutdown();
        IOReactorStatus status = client.getStatus();
        assertTrue(status == IOReactorStatus.SHUT_DOWN || status == IOReactorStatus.SHUTTING_DOWN,
                "Expected SHUT_DOWN or SHUTTING_DOWN but was " + status);
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `mvn test -Dtest=HttpRetrieveAsyncClientFactoryTest -pl . -q`
Expected: All 6 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/uds/osc/retrieve/client/HttpRetrieveAsyncClientFactoryTest.java
git commit -m "test: update factory tests for CloseableHttpAsyncClient return type"
```

---

### Task 4: Update TlsRoutingIntegrationTest

**Files:**
- Modify: `src/test/java/uds/osc/retrieve/client/TlsRoutingIntegrationTest.java`

The integration test uses `HttpRetrieveAsyncClient` directly and calls its custom `execute` method with `SimpleResponseConsumer`. Now that it extends `CloseableHttpAsyncClient`, use the inherited `execute(SimpleHttpRequest, FutureCallback<SimpleHttpResponse>)` method instead.

- [ ] **Step 1: Rewrite the test file**

```java
package uds.osc.retrieve.client;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TlsRoutingIntegrationTest {

    private static final char[] KS_PASSWORD = "changeit".toCharArray();
    private static HttpsServer server;
    private static int port;
    private static HttpRetrieveAsyncClientFactory factory;
    private static Path trustedCertPath;
    private static Path untrustedCertPath;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        tempDir = Files.createTempDirectory("tls-test");

        Path trustedKs = tempDir.resolve("trusted.p12");
        Path untrustedKs = tempDir.resolve("untrusted.p12");
        SSLTestUtils.generateKeyStore(trustedKs, "test", "localhost", KS_PASSWORD);
        SSLTestUtils.generateKeyStore(untrustedKs, "test", "otherhost", KS_PASSWORD);

        Path certsDir = Path.of("certs");
        Files.createDirectories(certsDir);
        trustedCertPath = certsDir.resolve("HDSC_trusted.crt");
        untrustedCertPath = certsDir.resolve("HDSC_untrusted.crt");
        SSLTestUtils.exportCertificate(trustedKs, "test", trustedCertPath, KS_PASSWORD);
        SSLTestUtils.exportCertificate(untrustedKs, "test", untrustedCertPath, KS_PASSWORD);

        server = SSLTestUtils.createHttpsServer(trustedKs, KS_PASSWORD);
        server.start();
        port = server.getAddress().getPort();

        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();
    }

    @AfterAll
    static void teardown() {
        if (factory != null) {
            factory.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
        cleanupFile(trustedCertPath);
        cleanupFile(untrustedCertPath);
        try { Files.deleteIfExists(Path.of("certs")); } catch (Exception ignored) {}
        cleanupDir(tempDir);
    }

    private static void cleanupFile(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (Exception ignored) {}
        }
    }

    private static void cleanupDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception ignored) {}
    }

    @Test
    void verifyCertTrue_matchingCert_succeeds() throws Exception {
        CloseableHttpAsyncClient client = factory.getClient("trusted", true);
        SimpleHttpResponse response = executeRequest(client, "https://127.0.0.1:" + port + "/");
        assertEquals(200, response.getCode());
    }

    @Test
    void verifyCertFalse_trustAll_succeeds() throws Exception {
        CloseableHttpAsyncClient client = factory.getClient("trusted", false);
        SimpleHttpResponse response = executeRequest(client, "https://127.0.0.1:" + port + "/");
        assertEquals(200, response.getCode());
    }

    @Test
    void verifyCertTrue_wrongCert_handshakeFails() throws Exception {
        CloseableHttpAsyncClient client = factory.getClient("untrusted", true);
        CompletableFuture<SimpleHttpResponse> future = executeRequestAsync(
                client, "https://127.0.0.1:" + port + "/");

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(SSLException.class, ex.getCause(),
                "Expected SSLException but got: " + ex.getCause().getClass().getName()
                        + ": " + ex.getCause().getMessage());
    }

    private SimpleHttpResponse executeRequest(CloseableHttpAsyncClient client, String url) throws Exception {
        return executeRequestAsync(client, url).get(10, TimeUnit.SECONDS);
    }

    private CompletableFuture<SimpleHttpResponse> executeRequestAsync(
            CloseableHttpAsyncClient client, String url) {
        CompletableFuture<SimpleHttpResponse> future = new CompletableFuture<>();
        SimpleHttpRequest request = SimpleHttpRequests.get(URI.create(url));
        client.execute(request, new org.apache.hc.core5.concurrent.FutureCallback<>() {
            @Override
            public void completed(SimpleHttpResponse result) {
                future.complete(result);
            }
            @Override
            public void failed(Exception ex) {
                future.completeExceptionally(ex);
            }
            @Override
            public void cancelled() {
                future.cancel(true);
            }
        });
        return future;
    }
}
```

Key changes from the original:
- All `HttpRetrieveAsyncClient` type references → `CloseableHttpAsyncClient`
- `execute(request, SimpleResponseConsumer.create(), callback)` → `execute(request, callback)` (uses the inherited `execute(SimpleHttpRequest, FutureCallback)` final method)

- [ ] **Step 2: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS (6 factory tests + 3 integration tests)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/uds/osc/retrieve/client/TlsRoutingIntegrationTest.java
git commit -m "test: update TLS integration tests for CloseableHttpAsyncClient return type"
```

---

### Task 5: Update CLAUDE.md to reflect the new API

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the architecture diagram and relevant sections**

In the Architecture section, update the class relationship. In Key Design Decisions, note that `HttpRetrieveAsyncClient` now extends `CloseableHttpAsyncClient`. Update the Package Structure to reflect the new inheritance.

Specific changes:
1. In the Architecture diagram, change the description of `HttpRetrieveAsyncClient` from "lightweight per-device wrapper, injects context" to "extends CloseableHttpAsyncClient, injects device context via doExecute()"
2. Add a Key Design Decision: "HttpRetrieveAsyncClient extends CloseableHttpAsyncClient — overrides doExecute() to inject device context, all other abstract methods delegate. getClient() returns CloseableHttpAsyncClient."

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for CloseableHttpAsyncClient return type"
```
