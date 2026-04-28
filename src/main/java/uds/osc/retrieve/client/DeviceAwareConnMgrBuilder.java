package uds.osc.retrieve.client;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom {@link PoolingAsyncClientConnectionManagerBuilder} that overrides
 * {@link #createConnectionOperator} to return a {@link DeviceAwareConnectionOperator}
 * instead of the default operator.
 * <p>
 * Since the parent class's {@code setSchemePortResolver} and {@code setDnsResolver}
 * methods are {@code final}, this builder provides alternative setters
 * ({@link #withSchemePortResolver} and {@link #withDnsResolver}) that store the
 * resolvers locally and apply them inside {@link #createConnectionOperator}.
 * The parent-class setters can still be used, but those values will only be
 * available as fallbacks inside {@code createConnectionOperator}.
 */
public class DeviceAwareConnMgrBuilder extends PoolingAsyncClientConnectionManagerBuilder {

    private final ConcurrentHashMap<DeviceKey, TlsStrategy> tlsStrategyCache;
    private SchemePortResolver schemePortResolver;
    private DnsResolver dnsResolver;

    public DeviceAwareConnMgrBuilder(final ConcurrentHashMap<DeviceKey, TlsStrategy> tlsStrategyCache) {
        this.tlsStrategyCache = tlsStrategyCache;
    }

    /**
     * Store a {@link SchemePortResolver} that will be forwarded to the
     * {@link DeviceAwareConnectionOperator}.
     * <p>
     * This exists because the parent's {@code setSchemePortResolver} is {@code final}
     * and cannot be overridden.
     */
    public DeviceAwareConnMgrBuilder withSchemePortResolver(final SchemePortResolver schemePortResolver) {
        this.schemePortResolver = schemePortResolver;
        return this;
    }

    /**
     * Store a {@link DnsResolver} that will be forwarded to the
     * {@link DeviceAwareConnectionOperator}.
     * <p>
     * This exists because the parent's {@code setDnsResolver} is {@code final}
     * and cannot be overridden.
     */
    public DeviceAwareConnMgrBuilder withDnsResolver(final DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    @Override
    protected AsyncClientConnectionOperator createConnectionOperator(
            final TlsStrategy tlsStrategy,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver) {
        // Prefer locally-set resolvers; fall back to the arguments supplied by the parent.
        final SchemePortResolver spr = this.schemePortResolver != null ? this.schemePortResolver : schemePortResolver;
        final DnsResolver dr = this.dnsResolver != null ? this.dnsResolver : dnsResolver;
        return new DeviceAwareConnectionOperator(tlsStrategyCache, spr, dr);
    }
}
