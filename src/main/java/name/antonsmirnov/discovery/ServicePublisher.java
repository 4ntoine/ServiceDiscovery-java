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
        void onError(Exception e);
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

    public ServicePublisher(String multicastGroup, int multicastPort, ServiceInfo serviceInfo) {
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.serviceInfo = serviceInfo;
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
            } catch (Exception e) {
                logger.error("Failed to open datagram socket", e);
                listener.onError(e);
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
                    if (!request.getType().equalsIgnoreCase(serviceInfo.getType())) {
                        logger.trace("Request service type {}, but published {}", request.getType(), serviceInfo.getType());
                        break;
                    }

                    // build response
                    Dto.ServiceResponse response = buildResponse();
                    logger.trace("Response build:\n{}", response);

                    // send response
                    sendResponse(datagramPacket, request, response);

                } catch (Exception e) {
                    if (shouldExit.get())
                        return;

                    logger.error("Error", e);
                    listener.onError(e);
                }
            }
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
