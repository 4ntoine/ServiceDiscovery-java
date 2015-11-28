package name.antonsmirnov.discovery;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes service
 */
public class ServicePublisher {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private ServiceInfo serviceInfo;

    public interface Listener {
        void onPublishStarted();
        void onPublishFinished();
        boolean onServiceRequestReceived(String host, String type); // return true to accept request
        void onServiceRequestRejected(String host, String type, String requestType); // rejected by not equal service types
        void onServiceResponseSent(String host);
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

    public ServicePublisher(String multicastGroup, int multicastPort, ServiceInfo serviceInfo, Listener listener) {
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.serviceInfo = serviceInfo;
        this.listener = listener;
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
                logger.trace("Opening socket, group={}, port={}", multicastGroup, multicastPort);

                serverSocket = new MulticastSocket(multicastPort);
                serverSocket.setSoTimeout(1000); // 1s

                group = InetAddress.getByName(multicastGroup);
                serverSocket.joinGroup(group);

                // event 'publish started'
                logger.info("Publish started");
                listener.onPublishStarted();
            } catch (Exception e) {
                logger.error("Failed to open datagram socket", e);

                // event 'error'
                listener.onPublishError(e);

                // event 'public finished' (error)
                logger.info("Publish finished");
                listener.onPublishFinished();
                return;
            }

            while (!shouldExit.get()) {
                try {
                    // receive request
                    DatagramPacket datagramPacket = receiveDatagramPacket();
                    byte[] requestBytes = extractRequestPacket(datagramPacket);

                    // parse request
                    Dto.ServiceRequest request = Dto.ServiceRequest.parseFrom(requestBytes);
                    logger.trace("Request received:\n{}", request);

                    // ask callback to accept request or not
                    String fromHost = datagramPacket.getAddress().getHostAddress();
                    if (listener.onServiceRequestReceived(fromHost, request.getType())) {
                        logger.trace("Request from {} accepted", fromHost);
                    } else {
                        logger.trace("Request rejected in callback");
                        continue;
                    }

                    // compare current service type and requested one
                    if (!request.getType().equalsIgnoreCase(serviceInfo.getType())) {
                        logger.trace("Request service type {}, but published {}", request.getType(), serviceInfo.getType());

                        // event 'rejected: different service types'
                        listener.onServiceRequestRejected(fromHost, serviceInfo.getType(), request.getType());
                        continue;
                    }

                    // build response
                    Dto.ServiceResponse response = buildResponse();
                    logger.trace("Response build:\n{}", response);

                    // send response
                    sendResponse(datagramPacket, request, response);
                    listener.onServiceResponseSent(datagramPacket.getAddress().getHostAddress());
                } catch (Exception e) {
                    if (shouldExit.get())
                        return;

                    logger.error("Error", e);
                    listener.onPublishError(e);
                }
            }

            // event 'publish finished' (success)
            logger.info("Publish finished");
            listener.onPublishFinished();
        }

        private void sendResponse(DatagramPacket datagramPacket, Dto.ServiceRequest request, Dto.ServiceResponse response) throws IOException {
            Socket socket = new Socket(datagramPacket.getAddress(), request.getPort()); // response port is set in request
            socket.getOutputStream().write(response.toByteArray());
            socket.close();

            logger.trace("Response sent");
        }

        private Dto.ServiceResponse buildResponse() {
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

        logger.info("Starting");
        startListening();
        started = true;
    }

    public void stop() {
        if (!started)
            return;

        logger.info("Stopping");
        stopListening();
        started = false;
    }


}
