package uds.osc.retrieve.client;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.ComplexFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Custom {@link AsyncClientConnectionOperator} that provides per-device TLS strategies
 * by reading device identity from {@link HttpContext}.
 * <p>
 * This works around an Apache HC5 limitation: {@link TlsStrategy#upgrade} does NOT receive
 * HttpContext. By implementing the {@code AsyncClientConnectionOperator} interface directly
 * and using the methods that DO receive HttpContext, we can select the correct per-device
 * {@code TlsStrategy} before delegating to the strategy's own upgrade method.
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public class DeviceAwareConnectionOperator implements AsyncClientConnectionOperator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAwareConnectionOperator.class);

    /** HttpContext attribute key for the device identifier. Shared across all classes. */
    public static final String CTX_DEVICE_ID = "retrieve.deviceId";

    /** HttpContext attribute key for certificate verification flag. Shared across all classes. */
    public static final String CTX_VERIFY_CERT = "retrieve.verifyCert";

    private final ConcurrentHashMap<DeviceKey, TlsStrategy> tlsStrategyCache;
    private final TlsStrategy fallbackTlsStrategy;
    private final DnsResolver dnsResolver;
    private final SchemePortResolver schemePortResolver;

    public DeviceAwareConnectionOperator(
            final ConcurrentHashMap<DeviceKey, TlsStrategy> tlsStrategyCache,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver) {
        this.tlsStrategyCache = tlsStrategyCache;
        this.schemePortResolver = schemePortResolver != null
                ? schemePortResolver
                : DefaultSchemePortResolver.INSTANCE;
        this.dnsResolver = dnsResolver;
        this.fallbackTlsStrategy = new BasicClientTlsStrategy(
                SSLContextFactory.createTrustAllSSLContext());
    }

    // ---- AsyncClientConnectionOperator: abstract 6-arg connect (no HttpContext) ----

    @Override
    public Future<ManagedAsyncClientConnection> connect(
            final ConnectionInitiator initiator,
            final HttpHost host,
            final SocketAddress remoteAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final FutureCallback<ManagedAsyncClientConnection> callback) {
        // Delegate to the 8-arg overload with null endpoint and null context.
        // Without HttpContext the fallback trust-all TlsStrategy will be used.
        return connect(initiator, host, null, remoteAddress, connectTimeout,
                attachment, null, callback);
    }

    // ---- AsyncClientConnectionOperator: default 8-arg connect (with HttpContext) ----

    @Override
    public Future<ManagedAsyncClientConnection> connect(
            final ConnectionInitiator initiator,
            final HttpHost host,
            final NamedEndpoint endpoint,
            final SocketAddress remoteAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final HttpContext context,
            final FutureCallback<ManagedAsyncClientConnection> callback) {

        if (initiator == null) {
            throw new IllegalArgumentException("Connection initiator");
        }
        if (host == null) {
            throw new IllegalArgumentException("Host");
        }

        final ComplexFuture<ManagedAsyncClientConnection> future =
                new ComplexFuture<>(callback);

        final TlsStrategy tlsStrategy = resolveTlsStrategy(context);
        final NamedEndpoint endpointName = endpoint != null ? endpoint : host;
        final boolean needsTls = URIScheme.HTTPS.same(host.getSchemeName());

        LOGGER.debug("{} connecting to {}->{} ({})", endpointName, host, remoteAddress,
                connectTimeout);

        final SocketAddress localAddress = (remoteAddress instanceof InetSocketAddress)
                ? new InetSocketAddress(((InetSocketAddress) remoteAddress).getAddress(), 0)
                : null;

        final Future<IOSession> sessionFuture = initiator.connect(
                endpointName, remoteAddress, localAddress, connectTimeout, attachment,
                new FutureCallback<IOSession>() {
                    @Override
                    public void completed(final IOSession ioSession) {
                        final DefaultManagedAsyncClientConnectionShim conn =
                                new DefaultManagedAsyncClientConnectionShim(ioSession);
                        if (needsTls) {
                            final Timeout handshakeTimeout =
                                    resolveHandshakeTimeout(attachment, connectTimeout);
                            tlsStrategy.upgrade(conn, endpointName, attachment, handshakeTimeout,
                                    new FutureCallback<TransportSecurityLayer>() {
                                        @Override
                                        public void completed(final TransportSecurityLayer result) {
                                            future.completed(conn);
                                        }

                                        @Override
                                        public void failed(final Exception ex) {
                                            conn.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
                                            future.failed(ex);
                                        }

                                        @Override
                                        public void cancelled() {
                                            conn.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
                                            future.cancel(true);
                                        }
                                    });
                        } else {
                            future.completed(conn);
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        future.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        future.cancel(true);
                    }
                });

        future.setDependency(sessionFuture);
        return future;
    }

    // ---- AsyncClientConnectionOperator: abstract 3-arg upgrade (no HttpContext) ----

    @Override
    public void upgrade(
            final ManagedAsyncClientConnection connection,
            final HttpHost host,
            final Object attachment) {
        // Delegate to the 6-arg overload with null endpoint and null context.
        upgrade(connection, host, null, attachment, null, null);
    }

    // ---- AsyncClientConnectionOperator: default 6-arg upgrade (with HttpContext) ----

    @Override
    public void upgrade(
            final ManagedAsyncClientConnection connection,
            final HttpHost host,
            final NamedEndpoint endpoint,
            final Object attachment,
            final HttpContext context,
            final FutureCallback<ManagedAsyncClientConnection> callback) {

        final TlsStrategy tlsStrategy = resolveTlsStrategy(context);
        final NamedEndpoint endpointName = endpoint != null ? endpoint : host;

        tlsStrategy.upgrade(connection, endpointName, attachment, null,
                new FutureCallback<TransportSecurityLayer>() {
                    @Override
                    public void completed(final TransportSecurityLayer result) {
                        if (callback != null) {
                            callback.completed(connection);
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        if (callback != null) {
                            callback.failed(ex);
                        }
                    }

                    @Override
                    public void cancelled() {
                        if (callback != null) {
                            callback.cancelled();
                        }
                    }
                });
    }

    // ---- internal helpers ----

    /**
     * Resolves the appropriate {@link TlsStrategy} for the current request by reading
     * device identity from the HttpContext and looking up the cached strategy.
     * Falls back to a trust-all strategy when no device context is available.
     */
    TlsStrategy resolveTlsStrategy(final HttpContext context) {
        if (context == null) {
            LOGGER.warn("HttpContext is null, using trustAll fallback");
            return fallbackTlsStrategy;
        }
        final String deviceId = (String) context.getAttribute(CTX_DEVICE_ID);
        final Boolean verifyCert = (Boolean) context.getAttribute(CTX_VERIFY_CERT);
        if (deviceId == null) {
            LOGGER.warn("deviceId not found in HttpContext, using trustAll fallback");
            return fallbackTlsStrategy;
        }
        final DeviceKey key = new DeviceKey(deviceId, verifyCert != null && verifyCert);
        final TlsStrategy strategy = tlsStrategyCache.get(key);
        if (strategy == null) {
            LOGGER.warn("No TlsStrategy cached for deviceId={}, verifyCert={}, using trustAll fallback",
                    deviceId, verifyCert);
            return fallbackTlsStrategy;
        }
        return strategy;
    }

    private Timeout resolveHandshakeTimeout(final Object attachment, final Timeout fallback) {
        if (attachment instanceof org.apache.hc.client5.http.config.TlsConfig) {
            final Timeout t = ((org.apache.hc.client5.http.config.TlsConfig) attachment)
                    .getHandshakeTimeout();
            return t != null ? t : fallback;
        }
        return fallback;
    }

    /**
     * Thin shim that wraps an {@link IOSession} as a {@link ManagedAsyncClientConnection}.
     * <p>
     * We need this because {@code DefaultManagedAsyncClientConnection} in Apache HttpClient 5.5
     * is package-private and cannot be used from outside {@code org.apache.hc.client5.http.impl.nio}.
     * This class delegates all {@code TransportSecurityLayer} calls (TLS start/upgrade) to the
     * underlying IOSession, which itself implements {@code TransportSecurityLayer} in HC5's
     * reactor implementation.
     */
    static final class DefaultManagedAsyncClientConnectionShim implements ManagedAsyncClientConnection {

        private final IOSession ioSession;

        DefaultManagedAsyncClientConnectionShim(final IOSession ioSession) {
            this.ioSession = ioSession;
        }

        public String getId() {
            return ioSession.getId();
        }

        @Override
        public void close(final org.apache.hc.core5.io.CloseMode closeMode) {
            ioSession.close(closeMode);
        }

        @Override
        public void close() {
            ioSession.close();
        }

        @Override
        public boolean isOpen() {
            return ioSession.isOpen();
        }

        @Override
        public void setSocketTimeout(final Timeout timeout) {
            ioSession.setSocketTimeout(timeout);
        }

        @Override
        public Timeout getSocketTimeout() {
            return ioSession.getSocketTimeout();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return ioSession.getRemoteAddress();
        }

        @Override
        public SocketAddress getLocalAddress() {
            return ioSession.getLocalAddress();
        }

        @Override
        public org.apache.hc.core5.http.EndpointDetails getEndpointDetails() {
            return new org.apache.hc.core5.http.EndpointDetails(
                    getRemoteAddress(), getLocalAddress(), ioSession.getSocketTimeout()) {
                @Override
                public long getRequestCount() { return 0; }
                @Override
                public long getResponseCount() { return 0; }
                @Override
                public long getSentBytesCount() { return 0; }
                @Override
                public long getReceivedBytesCount() { return 0; }
            };
        }

        @Override
        public org.apache.hc.core5.http.ProtocolVersion getProtocolVersion() {
            return new org.apache.hc.core5.http.ProtocolVersion("HTTP", 1, 1);
        }

        @Override
        public javax.net.ssl.SSLSession getSSLSession() {
            final org.apache.hc.core5.reactor.ssl.TlsDetails details = getTlsDetails();
            return details != null ? details.getSSLSession() : null;
        }

        @Override
        public void startTls(
                final javax.net.ssl.SSLContext sslContext,
                final NamedEndpoint endpoint,
                final org.apache.hc.core5.reactor.ssl.SSLBufferMode sslBufferMode,
                final org.apache.hc.core5.reactor.ssl.SSLSessionInitializer initializer,
                final org.apache.hc.core5.reactor.ssl.SSLSessionVerifier verifier,
                final Timeout handshakeTimeout) {
            if (ioSession instanceof TransportSecurityLayer) {
                ((TransportSecurityLayer) ioSession).startTls(
                        sslContext, endpoint, sslBufferMode, initializer, verifier, handshakeTimeout);
            } else {
                throw new UnsupportedOperationException(
                        "TLS not supported on underlying session: " + ioSession.getClass());
            }
        }

        @Override
        public void startTls(
                final javax.net.ssl.SSLContext sslContext,
                final NamedEndpoint endpoint,
                final org.apache.hc.core5.reactor.ssl.SSLBufferMode sslBufferMode,
                final org.apache.hc.core5.reactor.ssl.SSLSessionInitializer initializer,
                final org.apache.hc.core5.reactor.ssl.SSLSessionVerifier verifier,
                final Timeout handshakeTimeout,
                final FutureCallback<TransportSecurityLayer> callback) {
            if (ioSession instanceof TransportSecurityLayer) {
                ((TransportSecurityLayer) ioSession).startTls(
                        sslContext, endpoint, sslBufferMode, initializer, verifier,
                        handshakeTimeout, callback);
            } else {
                throw new UnsupportedOperationException(
                        "TLS not supported on underlying session: " + ioSession.getClass());
            }
        }

        @Override
        public org.apache.hc.core5.reactor.ssl.TlsDetails getTlsDetails() {
            if (ioSession instanceof TransportSecurityLayer) {
                return ((TransportSecurityLayer) ioSession).getTlsDetails();
            }
            return null;
        }

        @Override
        public void submitCommand(
                final org.apache.hc.core5.reactor.Command command,
                final org.apache.hc.core5.reactor.Command.Priority priority) {
            ioSession.enqueue(command, priority);
        }

        @Override
        public void passivate() {
            // no-op
        }

        @Override
        public void activate() {
            // no-op
        }
    }
}
