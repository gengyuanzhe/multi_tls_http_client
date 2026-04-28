package uds.osc.retrieve.client;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class HttpRetrieveAsyncClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRetrieveAsyncClientFactory.class);

    private final ConcurrentHashMap<DeviceKey, SSLContext> sslContextCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DeviceKey, TlsStrategy> tlsStrategyCache = new ConcurrentHashMap<>();
    private volatile CloseableHttpAsyncClient httpClient;
    private volatile PoolingAsyncClientConnectionManager connectionManager;
    private volatile boolean initialized = false;

    public synchronized void init() {
        if (initialized) {
            LOGGER.warn("HttpRetrieveAsyncClientFactory already initialized");
            return;
        }

        DeviceAwareConnMgrBuilder cmBuilder = new DeviceAwareConnMgrBuilder(tlsStrategyCache);
        cmBuilder.setMaxConnTotal(3000)
                .setMaxConnPerRoute(3000)
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.LAX)
                .setConnPoolPolicy(PoolReusePolicy.LIFO)
                .setConnectionTimeToLive(TimeValue.ofSeconds(25))
                .setValidateAfterInactivity(TimeValue.ofSeconds(10));

        connectionManager = cmBuilder.build();

        httpClient = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setSoTimeout(30, TimeUnit.SECONDS)
                        .setRcvBufSize(64 * 1024)
                        .setSndBufSize(4 * 1024)
                        .setSelectInterval(TimeValue.ofMilliseconds(100))
                        .setIoThreadCount(64)
                        .build())
                .setKeepAliveStrategy((response, context) -> TimeValue.ofSeconds(20))
                .disableAutomaticRetries()
                .setUserTokenHandler((route, context) -> {
                    if (context instanceof HttpClientContext) {
                        String deviceId = (String) context.getAttribute(DeviceAwareConnectionOperator.CTX_DEVICE_ID);
                        Boolean verifyCert = (Boolean) context.getAttribute(DeviceAwareConnectionOperator.CTX_VERIFY_CERT);
                        if (deviceId != null) {
                            return new DeviceKey(deviceId, verifyCert != null && verifyCert);
                        }
                    }
                    LOGGER.warn("No device context found in UserTokenHandler, connection state isolation disabled");
                    return null;
                })
                .build();

        httpClient.start();
        initialized = true;
        LOGGER.info("HttpRetrieveAsyncClientFactory initialized with shared IOReactor");
    }

    public HttpRetrieveAsyncClient getClient(String deviceId, boolean isVerifyCert) {
        CloseableHttpAsyncClient client = httpClient;
        if (client == null || !initialized) {
            throw new IllegalStateException("Factory not initialized. Call init() first.");
        }

        DeviceKey key = new DeviceKey(deviceId, isVerifyCert);

        // Create SSLContext outside of computeIfAbsent to avoid side-effects in lambda (C2)
        if (!sslContextCache.containsKey(key)) {
            SSLContext newCtx;
            if (isVerifyCert) {
                LOGGER.info("Creating device SSLContext for deviceId={}, verifyCert=true", deviceId);
                newCtx = SSLContextFactory.createDeviceSSLContext(deviceId);
            } else {
                LOGGER.info("Creating trustAll SSLContext for deviceId={}", deviceId);
                newCtx = SSLContextFactory.createTrustAllSSLContext();
            }
            sslContextCache.putIfAbsent(key, newCtx);
        }

        if (!tlsStrategyCache.containsKey(key)) {
            SSLContext sslContext = sslContextCache.get(key);
            tlsStrategyCache.putIfAbsent(key, new BasicClientTlsStrategy(sslContext));
        }

        return new HttpRetrieveAsyncClient(deviceId, isVerifyCert, client);
    }

    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        LOGGER.info("Shutting down HttpRetrieveAsyncClientFactory");
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (Exception e) {
            LOGGER.error("Error closing httpClient", e);
        }
        // httpClient.close() already closes the connection manager (I2 fix)
        sslContextCache.clear();
        tlsStrategyCache.clear();
        httpClient = null;
        connectionManager = null;
        initialized = false;
    }
}
