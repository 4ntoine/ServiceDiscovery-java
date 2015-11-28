# ServiceDiscovery-java

## Intro

It's a simple service discovery protocol like Bonjour and framework implemented in pure Java.
Services are available to publish their :
* int port (required)
* string type (required)
* string title (optional)
* byte[] payload (optional)

## Usage

### Publish service info

    // const
    private static final String SERVICE_TYPE = "MyServiceType1";
    private static final int SERVICE_PORT = 1205;
    private static final String SERVICE_TITLE = "Hello world";
    public static final byte[] SERVICE_PAYLOAD = new byte[]{1, 2, 3};
    
    private static final String MULTICAST_GROUP = "239.255.255.250";
    private static final int MULTICAST_PORT = 4460;

    // publish
    ServiceInfo serviceInfo = new ServiceInfo(SERVICE_PORT, SERVICE_TYPE, SERVICE_TITLE, SERVICE_PAYLOAD);
    publisher = new ServicePublisher(MULTICAST_GROUP, MULTICAST_PORT, serviceInfo, new ServicePublisher.Listener() {
            @Override
            public void onPublishStarted() {

            }

            @Override
            public void onPublishFinished() {

            }

            @Override
            public boolean onServiceRequestReceived(String host, String type) {
                return true; // accept requests from all hosts
            }

            @Override
            public void onServiceRequestRejected(String host, String type, String requestType) {

            }

            @Override
            public void onServiceResponseSent(String host) {

            }

            @Override
            public void onPublishError(Exception e) {

            }
        });
        publisher.start();
    
### Discover services

    locator = new ServiceLocator(MULTICAST_GROUP, MULTICAST_PORT);
    locator.discoverServices(SERVICE_TYPE, new ServiceLocator.Listener() {
        @Override
        public void onDiscoveryStarted() {
            
        }

        @Override
        public void onServiceDiscovered(Service service) {

        }

        @Override
        public void onDiscoveryFinished() {

        }

        @Override
        public void onDiscoveryError(Exception e) {

        }
    });

### How it works

1. Publisher listens for UDP milticast requests from Locator.
2. Locator starts listening for response and sends `ServiceRequest` with required service `type` and response `port` values.
3. Publisher receives request, accepts or rejects it (in listener `boolean onServiceRequestReceived()` or comparing requested and actual service type) and sends `ServiceResponse` over TCP directly to requester host and port.
4. Locator receives response and notifies service is found.
    
### Testing

See `DiscoveryTestCase` for more information

### How to build

It's built with Maven:
> mvn clean install

## Author
Anton Smirnov, dev@antonsmirnov.name
2015
