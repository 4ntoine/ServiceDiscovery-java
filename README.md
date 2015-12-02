# ServiceDiscovery-java

## Intro

It's a simple service discovery protocol (like Zeroconf or WS-Discovery) and framework implemented in pure Java.
Services are available to publish their :
* `int port` (required)
* `string type` (required)
* `string title` (optional)
* `byte[] payload` (optional)

## Usage

### Publish service info

    // const
    private static final String SERVICE_TYPE = "MyServiceType1";
    private static final int SERVICE_PORT = 1205;
    private static final String SERVICE_TITLE = "Hello world";
    public static final byte[] SERVICE_PAYLOAD = new byte[]{1, 2, 3};
    
    private static final String MULTICAST_GROUP = "239.255.255.240";
    private static final int MULTICAST_PORT = 4470;

    // publish
    publisher = new ServicePublisher(MULTICAST_GROUP, MULTICAST_PORT, new ServicePublisher.Listener() {
            @Override
            public void onPublishStarted() {

            }

            @Override
            public void onPublishFinished() {

            }

            @Override
            public boolean acceptServiceRequest(String host, String type, Mode mode) {
                return true; // accept requests from all hosts
            }

            @Override
            public void onNoServiceFound(String host, String type, Mode mode) {

            }

            @Override
            public void onServiceResponseSent(String requestHost, ServiceInfo serviceInfo) {

            }

            @Override
            public void onPublishError(Exception e) {

            }
        });
        publisher.registerService(new ServiceInfo(SERVICE_PORT, SERVICE_TYPE, SERVICE_TITLE, SERVICE_PAYLOAD));
        publisher.start();
    
### Discover services

    private static final int RESPONSE_PORT = MULTICAST_PORT + 1;
    locator = new ServiceLocator(MULTICAST_GROUP, MULTICAST_PORT, RESPONSE_PORT, new ServiceLocator.Listener() {
        @Override
        public void onDiscoveryStarted() {
            
        }

        @Override
        public void onServiceDiscovered(ServiceInfo serviceInfo, String host) {

        }

        @Override
        public void onDiscoveryFinished() {

        }

        @Override
        public void onDiscoveryError(Exception e) {

        }
    });
    locator.setMode(Mode.UDP); // response mode (or Mode.TCP)
    locator.discoverServices(SERVICE_TYPE);

## How it works

1. Publisher listens for UDP milticast requests from Locator.
2. Locator starts listening for response (UDP or TCP, depends on locator `mode`) and sends `ServiceRequest` with required fields:
  * `string type` // service type to discover
  * `Mode mode` // TCP or UDP response mode
  * `int port` // either TCP or UDP port for response
3. Publisher receives request, accepts or rejects it (in listener `boolean onServiceRequestReceived()` or comparing requested and actual service type) and sends `ServiceResponse` over TCP directly to requester host and port in request (if TCP mode reponse was in request) or UDP multicast response (same UDP group but port in request).
4. Locator receives response and notifies service is found.
    
## Testing

See `DiscoveryTestCase` for more information. Average discovery time in my home network is about 60 ms.

## How to build

It's built with Maven:

> mvn clean install

or

> mvn clean install -DskipTests=true

to build without testing

## License
Free for non-commercial usage, contact for commercial usage.

## Author
Anton Smirnov

dev [at] antonsmirnov [dot] name

2015
