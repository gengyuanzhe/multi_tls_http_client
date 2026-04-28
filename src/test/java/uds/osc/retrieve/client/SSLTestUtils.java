package uds.osc.retrieve.client;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

final class SSLTestUtils {

    private SSLTestUtils() {}

    static void generateKeyStore(Path keystorePath, String alias, String cn, char[] password) throws Exception {
        Files.createDirectories(keystorePath.getParent());
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        ProcessBuilder pb = new ProcessBuilder(
                keytool, "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=" + cn,
                "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(password),
                "-keypass", new String(password)
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(System.out);
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool -genkeypair failed with exit code " + exitCode);
        }
    }

    static void exportCertificate(Path keystorePath, String alias, Path certPath, char[] password) throws Exception {
        Files.createDirectories(certPath.getParent());
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        ProcessBuilder pb = new ProcessBuilder(
                keytool, "-exportcert",
                "-alias", alias,
                "-keystore", keystorePath.toString(),
                "-storepass", new String(password),
                "-file", certPath.toString(),
                "-rfc"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(System.out);
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool -exportcert failed with exit code " + exitCode);
        }
    }

    static HttpsServer createHttpsServer(Path keystorePath, char[] password) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(keystorePath)) {
            ks.load(is, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/", exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        return server;
    }
}
