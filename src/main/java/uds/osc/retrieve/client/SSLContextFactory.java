package uds.osc.retrieve.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public final class SSLContextFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(SSLContextFactory.class);
    private static final String CERT_DIR = "certs";
    private static final String CERT_FILE_PREFIX = "HDSC_";
    private static final String CERT_FILE_SUFFIX = ".crt";

    private SSLContextFactory() {}

    public static String getCertPath(String deviceId) {
        return CERT_DIR + "/" + CERT_FILE_PREFIX + deviceId + CERT_FILE_SUFFIX;
    }

    public static SSLContext createTrustAllSSLContext() {
        try {
            TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSLContext", e);
        }
    }

    public static SSLContext createDeviceSSLContext(String deviceId, String certBaseDir) {
        String certFileName = CERT_FILE_PREFIX + deviceId + CERT_FILE_SUFFIX;
        Path certPath = Path.of(certBaseDir, certFileName);

        if (!Files.exists(certPath)) {
            throw new RuntimeException("Certificate file not found: " + certPath.toAbsolutePath());
        }

        try (InputStream certStream = Files.newInputStream(certPath)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(certStream);

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("device-" + deviceId, cert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, tmf.getTrustManagers(), null);

            LOGGER.info("Created device SSLContext for deviceId={}", deviceId);
            return sslContext;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read certificate file: " + certPath.toAbsolutePath(), e);
        } catch (CertificateException e) {
            throw new RuntimeException("Invalid certificate for deviceId=" + deviceId + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create device SSLContext for deviceId=" + deviceId, e);
        }
    }

    public static SSLContext createDeviceSSLContext(String deviceId) {
        return createDeviceSSLContext(deviceId, CERT_DIR);
    }
}
