package uds.osc.retrieve.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SSLContextFactoryTest {

    @Test
    void createTrustAll_returnsNonNullSSLContext() {
        SSLContext ctx = SSLContextFactory.createTrustAllSSLContext();
        assertNotNull(ctx);
        assertEquals("TLSv1.2", ctx.getProtocol());
    }

    @Test
    void createTrustAll_hasSocketFactory() {
        SSLContext ctx = SSLContextFactory.createTrustAllSSLContext();
        assertNotNull(ctx.getSocketFactory());
    }

    @Test
    void createDeviceSSLContext_missingFile_throwsException(@TempDir Path tempDir) {
        assertThrows(RuntimeException.class, () ->
            SSLContextFactory.createDeviceSSLContext("nonexistent", tempDir.toString()));
    }

    @Test
    void createDeviceSSLContext_invalidCert_throwsException(@TempDir Path tempDir) throws Exception {
        Path certDir = tempDir.resolve("certs");
        Files.createDirectories(certDir);
        Path certFile = certDir.resolve("HDSC_test.crt");
        Files.writeString(certFile, "not a real certificate");
        assertThrows(Exception.class, () ->
            SSLContextFactory.createDeviceSSLContext("test", certDir.toString()));
    }

    @Test
    void certPath_defaultDir_format() {
        String path = SSLContextFactory.getCertPath("myDevice");
        assertTrue(path.endsWith("certs/HDSC_myDevice.crt"),
            "Expected cert path to match pattern, got: " + path);
    }
}
