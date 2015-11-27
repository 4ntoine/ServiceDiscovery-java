package name.antonsmirnov.discovery;

import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test to discover services over network:
 *
 * 1. publisher is created
 * 2. locator is created
 * 3. locator discovers service published by publisher
 *
 */
public class DiscoveryTestCase extends TestCase implements ServicePublisher.Listener, ServiceLocator.Listener {

    private Logger logger;

    private ServicePublisher publisher;
    private ServiceLocator locator;
    private CountDownLatch latch = new CountDownLatch(1);

    // test service info
    private static final String SERVICE_TYPE = "MyServiceType1";
    private static final int SERVICE_PORT = 1204;
    private static final String SERVICE_TITLE = "Hello world";
    public static final byte[] SERVICE_PAYLOAD = new byte[]{1, 2, 3};

    // test network settings
    private static final String MULTICAST_GROUP = "239.255.255.250";
    private static final int MULTICAST_PORT = 4460;

    // test logging
    private static final String LOG_LEVEL = "TRACE"; // "INFO" for less information

    private ServiceInfo foundServiceInfo;

    @Override
    protected void setUp() throws Exception {
        setUpLogging();

        ServiceInfo serviceInfo = new ServiceInfo(SERVICE_PORT, SERVICE_TYPE, SERVICE_TITLE, SERVICE_PAYLOAD);
        publisher = new ServicePublisher(MULTICAST_GROUP, MULTICAST_PORT, serviceInfo);
        publisher.setListener(this);
        publisher.start();

        locator = new ServiceLocator(MULTICAST_GROUP, MULTICAST_PORT);
    }

    private void setUpLogging() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, LOG_LEVEL);
        logger = LoggerFactory.getLogger("Test");
    }

    private static final int SECONDS = 1000;

    // main test to discover service
    public void testDiscover() throws InterruptedException {
        foundServiceInfo = null;

        int discoveryTimeOut = 2 * SECONDS;
        locator.setTimeoutMs(discoveryTimeOut);

        Thread.sleep(3 * SECONDS);
        locator.discoverServices(SERVICE_TYPE, this);

        // waiting for 10 s max
        latch.await(10, TimeUnit.SECONDS);
        publisher.stop();

        assertNotNull(foundServiceInfo);
        assertEquals(SERVICE_PORT, foundServiceInfo.getPort());
        assertEquals(SERVICE_TYPE, foundServiceInfo.getType());
        assertEquals(SERVICE_TITLE, foundServiceInfo.getTitle());
        assertTrue(Arrays.equals(SERVICE_PAYLOAD, foundServiceInfo.getPayload()));
    }

    @Override
    protected void tearDown() throws Exception {
        if (publisher.isStarted())
            publisher.stop();
    }

    @Override
    public void onDiscoveryStarted() {
        logger.info("Listener: discovery started");
    }

    @Override
    public void onServiceDiscovered(Service service) {
        logger.info("Listener: service found on {}:{} of type \"{}\" with title \"{}\" and payload \"{}\"",
                service.getHost(),
                service.getServiceInfo().getPort(),
                service.getServiceInfo().getType(),
                service.getServiceInfo().getTitle(),
                service.getServiceInfo().getPayload());

        foundServiceInfo = service.getServiceInfo(); // remember for assertions
    }

    @Override
    public void onDiscoveryFinished() {
        logger.info("Listener: discovery finished");
        latch.countDown(); // signal we can stop
    }

    @Override
    public void onError(Exception e) {
        logger.error("Listener: error happened", e);
    }
}
