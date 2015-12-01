package name.antonsmirnov.discovery;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes service
 */
public class ServicePublisher {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private List<ServiceInfo> services = new CopyOnWriteArrayList<ServiceInfo>();

    public interface Listener {
        void onPublishStarted();
        void onPublishFinished();
        boolean acceptServiceRequest(String host, String type, Mode mode); // return true to accept request
        void onNoServiceFound(String host, String type, Mode mode); // no service by type found in registered services
        void onServiceResponseSent(String requestHost, ServiceInfo serviceInfo);
        void onPublishError(Exception e);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Listener getListener() {
        return listener;
    }

    private String multicastGroup;
    private int multicastPort;

    public ServicePublisher(String multicastGroup, int multicastPort, Listener listener) {
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.listener = listener;
    }

    public void registerService(ServiceInfo serviceInfo) {
        services.add(serviceInfo);
    }

    public void unregisterService(ServiceInfo serviceInfo) {
        services.remove(serviceInfo);
    }

    /**
     * Thread that listens for connection
     */
    private class ListenerThread extends Thread {

        private Logger logger = LoggerFactory.getLogger(getClass());

        private AtomicBoolean shouldExit = new AtomicBoolean(false);
        private MulticastSocket serverSocket;
        private InetAddress group = null;

        public static final int BUFFER_SIZE = 1024; // 1 Kb by default
        private byte[] buffer;

        public ListenerThread(int bufferSize) {
            logger.trace("Set input buffer size={}", bufferSize);
            buffer = new byte[bufferSize];
        }

        public void signalExit() {
            shouldExit.set(true);

            try {
                serverSocket.leaveGroup(group);
                serverSocket.close();
                logger.trace("Server socket closed");
            } catch (IOException e) {
                logger.error("Failed to close server socket gratefully", e);
            }
        }

        @Override
        public void run() {
            logger.trace("Started");

            try {
                logger.trace("Opening socket for UDP requests in group={}, port={}", multicastGroup, multicastPort);

                serverSocket = new MulticastSocket(multicastPort);
                serverSocket.setSoTimeout(1000); // 1s

                group = InetAddress.getByName(multicastGroup);
                serverSocket.joinGroup(group);

                // event 'publish started'
                logger.debug("Publish started");
                listener.onPublishStarted();
            } catch (Exception e) {
                logger.error("Failed to open datagram socket", e);

                // event 'error'
                listener.onPublishError(e);

                // event 'public finished' (error)
                logger.debug("Publish finished");
                listener.onPublishFinished();
                return;
            }

            try {
                while (!shouldExit.get()) {
                    try {
                        // receive request
                        DatagramPacket datagramPacket = receiveDatagramPacket();
                        byte[] requestBytes = extractRequestPacket(datagramPacket);

                        // parse request
                        Dto.ServiceRequest request = Dto.ServiceRequest.parseFrom(requestBytes);
                        logger.debug("Request received:\n{}", request);

                        // ask callback to accept request or not
                        String fromHost = datagramPacket.getAddress().getHostAddress();
                        Mode responseMode = (request.getMode() == Dto.ServiceRequest.Mode.TCP ? Mode.TCP : Mode.UDP);
                        if (listener.acceptServiceRequest(fromHost, request.getType(), responseMode)) {
                            logger.trace("Request from {} accepted", fromHost);
                        } else {
                            logger.warn("Request from {} REJECTED", fromHost);
                            continue;
                        }

                        // compare current service type and requested one
                        List<ServiceInfo> foundServices = findServices(request.getType());
                        if (foundServices.size() > 0) {
                            logger.trace("{} service(s) found", foundServices.size());
                        } else {
                            logger.trace("No published services of type {} found", request.getType());
                            listener.onNoServiceFound(fromHost, request.getType(), responseMode);
                        }

                        // build response
                        for (ServiceInfo eachServiceInfo : foundServices) {
                            Dto.ServiceResponse response = buildResponse(eachServiceInfo);
                            logger.debug("Response build:\n{}", response);

                            // send response
                            sendResponse(datagramPacket, request, response);
                            listener.onServiceResponseSent(datagramPacket.getAddress().getHostAddress(), eachServiceInfo);
                        }
                    } catch (Exception e) {
                        if (shouldExit.get())
                            return;

                        logger.error("Error", e);
                        listener.onPublishError(e);
                    }
                }

            } finally {
                // event 'publish finished' (success)
                logger.debug("Publish finished");
                listener.onPublishFinished();
            }
        }

        private void sendResponse(DatagramPacket datagramPacket, Dto.ServiceRequest request, Dto.ServiceResponse response) throws IOException {
            if (request.getMode() == Dto.ServiceRequest.Mode.TCP) {
                sendResponseTcp(datagramPacket, request.getPort(), response);
            } else {
                sendResponseUdp(request.getPort(), response);
            }

            logger.trace("Response sent");
        }

        private void sendResponseTcp(DatagramPacket datagramPacket, int port, Dto.ServiceResponse response) throws IOException {
            byte[] packet = response.toByteArray();
            logger.trace("Sending TCP response to {}:{}\n{}\n{}", datagramPacket.getAddress(), port, response, packet);

            Socket socket = SocketFactory.getDefault().createSocket(datagramPacket.getAddress(), port); // response port is set in request
            socket.getOutputStream().write(packet);
            socket.close();
        }

        private void sendResponseUdp(int port, Dto.ServiceResponse response) throws IOException {
            byte[] packet = response.toByteArray();
            logger.trace("Sending UDP response to group {}:{}:\n{}\n{}", multicastGroup, port, response, packet);

            MulticastSocket socket = new MulticastSocket();
            InetAddress group = InetAddress.getByName(multicastGroup);
            DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, group, port);
            socket.send(datagramPacket);
            socket.close();
        }

        private Dto.ServiceResponse buildResponse(ServiceInfo serviceInfo) {
            Dto.ServiceResponse.Builder builder = Dto.ServiceResponse
                    .newBuilder()
                    .setPort(serviceInfo.getPort())
                    .setType(serviceInfo.getType());

            // optional fields
            if (serviceInfo.getTitle() != null)
                builder.setTitle(serviceInfo.getTitle());

            if (serviceInfo.getPayload() != null)
                builder.setPayload(ByteString.copyFrom(serviceInfo.getPayload()));

            // TODO : possible improvement:
            // precache ByteString created from serviceInfo.getPayload() in order not to create it each time

            return builder.build();
        }

        private byte[] extractRequestPacket(DatagramPacket datagramPacket) {
            byte[] requestBytes = new byte[datagramPacket.getLength()];
            System.arraycopy(datagramPacket.getData(), 0, requestBytes, 0, datagramPacket.getLength());

            logger.trace("Packet received ({} bytes):\n{}", requestBytes.length, requestBytes);

            return requestBytes;
        }

        private DatagramPacket receiveDatagramPacket() throws IOException {
            DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
            logger.trace("Waiting for packet ...");

            while (!shouldExit.get()) {
                try {
                    serverSocket.receive(datagramPacket); // blocks thread
                    break;
                } catch (SocketTimeoutException e) {
                    // it's ok
                }
            }
            return datagramPacket;
        }
    }

    private List<ServiceInfo> findServices(String type) {
        List<ServiceInfo> result = new ArrayList<ServiceInfo>();

        for (ServiceInfo eachService : services)
            if (eachService.getType().equalsIgnoreCase(type))
                result.add(eachService);

        return result;
    }

    private ListenerThread listenerThread;

    private void startListening() {
        listenerThread = new ListenerThread(ListenerThread.BUFFER_SIZE);
        listenerThread.start();
    }

    private void stopListening() {
        listenerThread.signalExit();
        listenerThread = null;
    }

    private boolean started;

    public boolean isStarted() {
        return started;
    }

    public void start() {
        if (started)
            return;

        logger.trace("Starting");
        startListening();
        started = true;
    }

    public void stop() {
        if (!started)
            return;

        logger.debug("Stopping");
        stopListening();
        started = false;
    }


}
