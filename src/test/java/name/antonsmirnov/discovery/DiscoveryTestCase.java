package name.antonsmirnov.discovery;

import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
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
    // use any 'unassigned' multicast group
    // f.e. 239.255.255.250 is reserved by SSDP and later WS-Discovery (https://en.wikipedia.org/wiki/WS-Discovery)
    private static final String MULTICAST_GROUP = "239.255.255.240";
    private static final int MULTICAST_PORT = 4470;
    private static final int RESPONSE_PORT = MULTICAST_PORT + 1;

    // test logging
    private static final String LOG_LEVEL = "TRACE"; // "INFO" or "DEBUG" for less information

    private ServiceInfo foundServiceInfo;

    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SS");

    private String getCurrentTime() {
        return timeFormat.format(new Date());
    }

    @Override
    protected void setUp() throws Exception {
        setUpLogging();

        // publisher
        ServiceInfo serviceInfo = new ServiceInfo(SERVICE_PORT, SERVICE_TYPE, SERVICE_TITLE, SERVICE_PAYLOAD);
        publisher = new ServicePublisher(MULTICAST_GROUP, MULTICAST_PORT, serviceInfo, this);

        // locator
        locator = new ServiceLocator(MULTICAST_GROUP, MULTICAST_PORT, RESPONSE_PORT, this);
        locator.setMode(Mode.UDP);
    }

    private void setUpLogging() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, LOG_LEVEL);
        logger = LoggerFactory.getLogger("Test");
    }

    private static final int SECONDS = 1000;

    private long discoveryStarted;

    // main test to publish service and discover it
    public void testPublishAndDiscover() throws InterruptedException {
        publisher.start();

        // make sure no service found before
        foundServiceInfo = null;

        int discoveryTimeOut = 3 * SECONDS;
        locator.setResponseTimeoutMillis(discoveryTimeOut);

        Thread.sleep(1 * SECONDS);
        locator.discover(SERVICE_TYPE);
        discoveryStarted = System.currentTimeMillis();

        // waiting for response for 60 s max
        boolean serviceFound = latch.await(10, TimeUnit.SECONDS);
        publisher.stop();

        assertTrue(serviceFound);

        logger.info("Service discovered in {} ms", (serviceDiscovered - discoveryStarted));

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

    // discovery listener

    @Override
    public void onDiscoveryStarted() {
        logger.info("Discovery listener: discovery started on {}", getCurrentTime());
    }

    private Long serviceDiscovered;

    @Override
    public synchronized void onServiceDiscovered(Service service) {
        // we need to get the first discovered service time
        if (serviceDiscovered == null)
            serviceDiscovered = System.currentTimeMillis();

        logger.info("Discovery listener: service found on {}:{} of type \"{}\" with title \"{}\" and payload \"{}\" on {}",
                service.getHost(),
                service.getServiceInfo().getPort(),
                service.getServiceInfo().getType(),
                service.getServiceInfo().getTitle(),
                service.getServiceInfo().getPayload(),
            getCurrentTime());

        foundServiceInfo = service.getServiceInfo(); // remember for assertions
    }

    @Override
    public void onDiscoveryFinished() {
        logger.info("Discovery listener: discovery finished on {}", getCurrentTime());
        latch.countDown(); // signal we can stop test
    }

    @Override
    public void onDiscoveryError(Exception e) {
        logger.error("Discovery listener: error", e);
    }

    // publish listener

    @Override
    public void onPublishStarted() {
        logger.info("Publish listener: publish started on {}", getCurrentTime());
    }

    @Override
    public void onPublishFinished() {
        logger.info("Publish listener: publish finished on {}", getCurrentTime());
    }

    @Override
    public boolean onServiceRequestReceived(String host, String type, Mode mode) {
        logger.info("Publish listener: accept request from {} in mode {}", host, mode);
        return true; // accept all requests
    }

    @Override
    public void onServiceRequestRejected(String host, String type, String requestType) {
        logger.warn("Publish listener: not equal service types from {} (requested \"{}\", but actual is \"{}\")",
                host, requestType, type);
    }

    @Override
    public void onServiceResponseSent(String requestHost) {
        logger.info("Publish listener: sent response to {}", requestHost);
    }

    @Override
    public void onPublishError(Exception e) {
        logger.error("Publish listener: error", e);
    }
}
