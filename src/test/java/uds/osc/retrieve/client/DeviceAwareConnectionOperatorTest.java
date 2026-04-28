package uds.osc.retrieve.client;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class DeviceAwareConnectionOperatorTest {

    @Test
    void resolveTlsStrategy_nullContext_returnsFallback() {
        ConcurrentHashMap<DeviceKey, TlsStrategy> cache = new ConcurrentHashMap<>();
        DeviceAwareConnectionOperator operator = new DeviceAwareConnectionOperator(cache, null, null);

        TlsStrategy strategy = operator.resolveTlsStrategy(null);
        assertNotNull(strategy);
    }

    @Test
    void resolveTlsStrategy_noDeviceId_returnsFallback() {
        ConcurrentHashMap<DeviceKey, TlsStrategy> cache = new ConcurrentHashMap<>();
        DeviceAwareConnectionOperator operator = new DeviceAwareConnectionOperator(cache, null, null);

        HttpClientContext context = HttpClientContext.create();
        TlsStrategy strategy = operator.resolveTlsStrategy(context);
        assertNotNull(strategy);
    }

    @Test
    void resolveTlsStrategy_cachedDevice_returnsCachedStrategy() throws Exception {
        ConcurrentHashMap<DeviceKey, TlsStrategy> cache = new ConcurrentHashMap<>();
        TlsStrategy expected = new BasicClientTlsStrategy(SSLContextFactory.createTrustAllSSLContext());
        cache.put(new DeviceKey("dev1", true), expected);

        DeviceAwareConnectionOperator operator = new DeviceAwareConnectionOperator(cache, null, null);

        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(DeviceAwareConnectionOperator.CTX_DEVICE_ID, "dev1");
        context.setAttribute(DeviceAwareConnectionOperator.CTX_VERIFY_CERT, true);

        TlsStrategy actual = operator.resolveTlsStrategy(context);
        assertSame(expected, actual);
    }

    @Test
    void resolveTlsStrategy_uncachedDevice_returnsFallback() throws Exception {
        ConcurrentHashMap<DeviceKey, TlsStrategy> cache = new ConcurrentHashMap<>();
        TlsStrategy cachedForOther = new BasicClientTlsStrategy(SSLContextFactory.createTrustAllSSLContext());
        cache.put(new DeviceKey("dev1", true), cachedForOther);

        DeviceAwareConnectionOperator operator = new DeviceAwareConnectionOperator(cache, null, null);

        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(DeviceAwareConnectionOperator.CTX_DEVICE_ID, "dev2");
        context.setAttribute(DeviceAwareConnectionOperator.CTX_VERIFY_CERT, true);

        TlsStrategy actual = operator.resolveTlsStrategy(context);
        assertNotNull(actual);
        assertNotSame(cachedForOther, actual);
    }

    @Test
    void resolveTlsStrategy_sameDeviceDifferentVerifyCert_returnsDifferentStrategy() throws Exception {
        ConcurrentHashMap<DeviceKey, TlsStrategy> cache = new ConcurrentHashMap<>();
        TlsStrategy strategyTrue = new BasicClientTlsStrategy(SSLContextFactory.createTrustAllSSLContext());
        TlsStrategy strategyFalse = new BasicClientTlsStrategy(SSLContextFactory.createTrustAllSSLContext());
        cache.put(new DeviceKey("dev1", true), strategyTrue);
        cache.put(new DeviceKey("dev1", false), strategyFalse);

        DeviceAwareConnectionOperator operator = new DeviceAwareConnectionOperator(cache, null, null);

        HttpClientContext ctxTrue = HttpClientContext.create();
        ctxTrue.setAttribute(DeviceAwareConnectionOperator.CTX_DEVICE_ID, "dev1");
        ctxTrue.setAttribute(DeviceAwareConnectionOperator.CTX_VERIFY_CERT, true);

        HttpClientContext ctxFalse = HttpClientContext.create();
        ctxFalse.setAttribute(DeviceAwareConnectionOperator.CTX_DEVICE_ID, "dev1");
        ctxFalse.setAttribute(DeviceAwareConnectionOperator.CTX_VERIFY_CERT, false);

        assertSame(strategyTrue, operator.resolveTlsStrategy(ctxTrue));
        assertSame(strategyFalse, operator.resolveTlsStrategy(ctxFalse));
    }
}
