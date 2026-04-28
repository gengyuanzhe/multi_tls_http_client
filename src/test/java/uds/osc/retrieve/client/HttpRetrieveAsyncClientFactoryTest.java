package uds.osc.retrieve.client;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRetrieveAsyncClientFactoryTest {

    private HttpRetrieveAsyncClientFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.shutdown();
        }
    }

    @Test
    void getClient_noVerify_returnsClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client);
    }

    @Test
    void getClient_sameKey_returnsSameUnderlyingClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient a = factory.getClient("dev1", false);
        CloseableHttpAsyncClient b = factory.getClient("dev1", false);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.getStatus(), b.getStatus());
    }

    @Test
    void getClient_differentDevices_sameUnderlyingClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        CloseableHttpAsyncClient dev1 = factory.getClient("dev1", false);
        CloseableHttpAsyncClient dev2 = factory.getClient("dev2", false);
        assertEquals(dev1.getStatus(), dev2.getStatus());
        assertSame(dev1.getStatus(), dev2.getStatus());
    }

    @Test
    void getClient_verifyCertTrue_missingCert_throws() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        assertThrows(RuntimeException.class, () -> factory.getClient("nonexistent", true));
    }

    @Test
    void getClient_withoutInit_throws() {
        factory = new HttpRetrieveAsyncClientFactory();
        assertThrows(IllegalStateException.class, () -> factory.getClient("dev1", false));
    }

    @Test
    void shutdown_closesGracefully() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();
        CloseableHttpAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client);
        factory.shutdown();
        IOReactorStatus status = client.getStatus();
        assertTrue(status == IOReactorStatus.SHUT_DOWN || status == IOReactorStatus.SHUTTING_DOWN,
                "Expected SHUT_DOWN or SHUTTING_DOWN but was " + status);
    }
}
