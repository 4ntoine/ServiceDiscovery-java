# ServiceDiscovery-java

## Intro

It's a simple service discovery framework like Bonjour implemented in pure Java.
Services are available to publish their :
* int port (required)
* string type (required)
* string title (optional)
* byte[] ayload (optional)

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
    publisher = new ServicePublisher(MULTICAST_GROUP, MULTICAST_PORT, serviceInfo);
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
        public void onError(Exception e) {

        }
    });
    
### Testing

See `DiscoveryTestCase` for more information

### How to build

It's built with Maven:
> mvn clean install

## Author
Anton Smirnov, dev@antonsmirnov.name
2015
