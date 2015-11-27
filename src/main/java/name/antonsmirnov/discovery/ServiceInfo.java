package name.antonsmirnov.discovery;

/**
 * Service information
 */
public class ServiceInfo {

    private int port;
    private String type;
    private String title;
    private byte[] payload;

    public int getPort() {
        return port;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public byte[] getPayload() {
        return payload;
    }

    public ServiceInfo(int port, String type, String title, byte[] payload) {
        this.port = port;
        this.type = type;

        // optional
        this.title = title;
        this.payload = payload;
    }

    public ServiceInfo(int port, String type, String title) {
        this(port, type, title, null);
    }

    public ServiceInfo(int port, String type) {
        this(port, type, null, null);
    }
}
