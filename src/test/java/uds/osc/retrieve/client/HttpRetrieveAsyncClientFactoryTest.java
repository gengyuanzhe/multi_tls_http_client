package uds.osc.retrieve.client;

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

        HttpRetrieveAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client);
        assertEquals("dev1", client.getDeviceId());
        assertFalse(client.isVerifyCert());
    }

    @Test
    void getClient_sameKey_returnsSameHttpClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        HttpRetrieveAsyncClient a = factory.getClient("dev1", false);
        HttpRetrieveAsyncClient b = factory.getClient("dev1", false);
        assertNotNull(a);
        assertNotNull(b);
        assertSame(a.getHttpClient(), b.getHttpClient());
    }

    @Test
    void getClient_differentDevices_sameHttpClient() {
        factory = new HttpRetrieveAsyncClientFactory();
        factory.init();

        HttpRetrieveAsyncClient dev1 = factory.getClient("dev1", false);
        HttpRetrieveAsyncClient dev2 = factory.getClient("dev2", false);
        assertSame(dev1.getHttpClient(), dev2.getHttpClient());
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
        HttpRetrieveAsyncClient client = factory.getClient("dev1", false);
        assertNotNull(client.getHttpClient());
        factory.shutdown();
        IOReactorStatus status = client.getHttpClient().getStatus();
        assertTrue(status == IOReactorStatus.SHUT_DOWN || status == IOReactorStatus.SHUTTING_DOWN,
                "Expected SHUT_DOWN or SHUTTING_DOWN but was " + status);
    }
}
