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
            HttpClientContext clientContext = context != null
                    ? HttpClientContext.adapt(context)
                    : HttpClientContext.create();
            clientContext.setAttribute(CTX_DEVICE_ID, deviceId);
            clientContext.setAttribute(CTX_VERIFY_CERT, verifyCert);
            effectiveContext = clientContext;
        }
        return delegate.execute(target, requestProducer, responseConsumer,
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
