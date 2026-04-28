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
