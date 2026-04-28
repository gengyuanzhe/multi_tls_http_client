package uds.osc.retrieve.client;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.io.Closeable;
import java.io.IOException;

public class HttpRetrieveAsyncClient implements Closeable {

    private static final String CTX_DEVICE_ID = DeviceAwareConnectionOperator.CTX_DEVICE_ID;
    private static final String CTX_VERIFY_CERT = DeviceAwareConnectionOperator.CTX_VERIFY_CERT;

    private final String deviceId;
    private final boolean verifyCert;
    private final CloseableHttpAsyncClient httpClient;

    public HttpRetrieveAsyncClient(String deviceId, boolean verifyCert, CloseableHttpAsyncClient httpClient) {
        this.deviceId = deviceId;
        this.verifyCert = verifyCert;
        this.httpClient = httpClient;
    }

    public CloseableHttpAsyncClient getHttpClient() {
        return httpClient;
    }

    public <T> void execute(HttpRequest request, AsyncResponseConsumer<T> responseConsumer,
                            FutureCallback<T> callback) {
        HttpClientContext context = createHttpContext();
        AsyncRequestProducer requestProducer = new BasicRequestProducer(request, null);
        httpClient.execute(requestProducer, responseConsumer, context, callback);
    }

    public <T> void execute(HttpRequest request, AsyncEntityProducer entityProducer,
                            AsyncResponseConsumer<T> responseConsumer,
                            FutureCallback<T> callback) {
        HttpClientContext context = createHttpContext();
        AsyncRequestProducer requestProducer = new BasicRequestProducer(request, entityProducer);
        httpClient.execute(requestProducer, responseConsumer, context, callback);
    }

    public <T> void execute(AsyncRequestProducer requestProducer, AsyncResponseConsumer<T> responseConsumer,
                            FutureCallback<T> callback) {
        HttpClientContext context = createHttpContext();
        httpClient.execute(requestProducer, responseConsumer, context, callback);
    }

    public <T> void execute(AsyncRequestProducer requestProducer, AsyncResponseConsumer<T> responseConsumer,
                            HttpContext context, FutureCallback<T> callback) {
        injectDeviceContext(context);
        httpClient.execute(requestProducer, responseConsumer, context, callback);
    }

    private HttpClientContext createHttpContext() {
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(CTX_DEVICE_ID, deviceId);
        context.setAttribute(CTX_VERIFY_CERT, verifyCert);
        return context;
    }

    private void injectDeviceContext(HttpContext context) {
        if (context != null) {
            context.setAttribute(CTX_DEVICE_ID, deviceId);
            context.setAttribute(CTX_VERIFY_CERT, verifyCert);
        }
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isVerifyCert() {
        return verifyCert;
    }

    @Override
    public void close() throws IOException {
        // Lifecycle managed by HttpRetrieveAsyncClientFactory
    }
}
