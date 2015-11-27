package name.antonsmirnov.discovery;

/**
 * Found service information
 */
public class Service {

    private String host;
    private ServiceInfo serviceInfo;

    public String getHost() {
        return host;
    }

    public ServiceInfo getServiceInfo() {
        return serviceInfo;
    }

    public Service(String host, ServiceInfo serviceInfo) {
        this.host = host;
        this.serviceInfo = serviceInfo;
    }
}
