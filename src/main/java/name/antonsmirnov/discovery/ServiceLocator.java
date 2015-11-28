package name.antonsmirnov.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Searches services
 */
public class ServiceLocator {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private int port = 4447;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    private int timeoutMs = 1000;

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Listener for found services
     */
    public interface Listener {
        void onDiscoveryStarted();
        void onServiceDiscovered(Service service);
        void onDiscoveryFinished();
        void onDiscoveryError(Exception e);
    }

    private Listener listener;
    private String type;

    /**
     * Listens to requests
     */
    private class ListenerThread extends Thread {

        private Logger logger = LoggerFactory.getLogger(getClass());

        private ServerSocket serverSocket;
        private AtomicBoolean shouldExit = new AtomicBoolean(false);
        private CountDownLatch latch = new CountDownLatch(1);

        private void signalExit() {
            logger.trace("Signal exiting");
            shouldExit.set(true);
            try {
                serverSocket.close();
                logger.trace("Server socket closed");
            } catch (IOException e) {
                logger.error("Close socket error", e);
            }
        }

        public void waitForStarted() {
            try {
                latch.await();
            } catch (InterruptedException e) {
            }
        }

        @Override
        public void run() {
            latch.countDown(); // signal that thread is started
            logger.trace("Started");

            logger.trace("Opening socket for responses on port {}", port);
            try {
                serverSocket = ServerSocketFactory.getDefault().createServerSocket(port, 0, null);
            } catch (IOException e) {
                logger.error("Failed to open socket", e);
                listener.onDiscoveryError(e);
                return;
            }

            while (!shouldExit.get()) {
                try {
                    logger.trace("Waiting for new client ...");
                    Socket socket = serverSocket.accept(); // blocks thread until client is connected

                    logger.trace("New client accepted: {}", socket);
                    new ClientThread(socket).start();
                } catch (IOException e) {
                    if (!shouldExit.get() && listener != null)
                        listener.onDiscoveryError(e);
                }
            }


        }
    }

    /**
     * Handle client response
     */
    private class ClientThread extends Thread {
        private Logger logger = LoggerFactory.getLogger(getClass());
        private Socket socket;

        public ClientThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                // parse response
                Dto.ServiceResponse response = Dto.ServiceResponse.parseFrom(socket.getInputStream());
                ServiceInfo serviceInfo = new ServiceInfo(
                        response.getPort(),
                        response.getType(),
                        response.getTitle(),
                        response.hasPayload() ? response.getPayload().toByteArray() : null);
                logger.trace("Response received from {}:\n{}", socket, response);
                Service service = new Service(socket.getInetAddress().getHostAddress(), serviceInfo);

                logger.trace("Close client socket");
                socket.close();

                // event 'service discovered'
                logger.info("Service found on {}:{} of type \"{}\" with title \"{}\" and payload \"{}\"",
                        socket.getInetAddress().getHostAddress(),
                        response.getPort(),
                        response.getType(),
                        response.getTitle(),
                        response.hasPayload() ? response.getPayload().toByteArray() : null);
                listener.onServiceDiscovered(service);

            } catch (Exception e) {
                listener.onDiscoveryError(e);
            }
        }
    }

    /**
     * Thread just to wait for time
     */
    private class TimerThread extends Thread {
        private int timeoutMs;
        private CountDownLatch latch = new CountDownLatch(1);

        public TimerThread(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public void signalExit() {
            latch.countDown();
        }

        @Override
        public void run() {
            try {
                boolean signaledExit = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                timerThread = null;
                if (!signaledExit) {
                    onTimeOut();
                }
            } catch (InterruptedException e) {
            }
        }
    }

    private void stopDiscovery() {
        stopListening();
        listener.onDiscoveryFinished();
        isDiscovering = false;
    }

    private void onTimeOut() {
        logger.trace("Discovery timeout");
        stopDiscovery();
    }

    private TimerThread timerThread;

    private ListenerThread listenerThread;

    private boolean isDiscovering;

    public boolean isDiscovering() {
        return isDiscovering;
    }

    private String multicastGroup;
    private int multicastPort;

    public ServiceLocator(String multicastGroup, int multicastPort) {
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
    }

    public boolean discoverServices(String type, Listener listener) {
        if (isDiscovering)
            return false;

        logger.info("Start discovering services of type \"{}\"", type);

        this.listener = listener;
        this.type = type;

        // event 'started'
        this.listener.onDiscoveryStarted();
        isDiscovering = true;

        // start listening for responses
        startListening();
        listenerThread.waitForStarted(); // can't send requests before started to wait for responses

        try {
            sendRequest();
        } catch (IOException e) {
            logger.error("Error", e);

            listener.onDiscoveryError(e);
            stopListening();

            listener.onDiscoveryFinished();
        }
        startTimer();
        return true;
    }

    private void sendRequest() throws IOException {
        Dto.ServiceRequest request = Dto.ServiceRequest
                .newBuilder()
                .setType(type)
                .setPort(port)
                .build();

        logger.trace("Build request:\n{}", request);

        // send over UDP multicast
        MulticastSocket clientSocket = new MulticastSocket();
        InetAddress group = InetAddress.getByName(multicastGroup);
        byte[] packet = request.toByteArray();

        logger.trace("Sending packet to {}:{}:\n{}", multicastGroup, multicastPort, packet);
        DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, group, multicastPort);
        clientSocket.send(datagramPacket);
        clientSocket.close();
    }

    private void startTimer() {
        timerThread = new TimerThread(timeoutMs);
        timerThread.start();
    }

    public boolean cancelDiscovery() {
        if (!isDiscovering)
            return false;

        logger.info("Cancel discovering");

        stopDiscovery();
        timerThread.signalExit();
        return true;
    }

    public void stopListening() {
        if (listenerThread == null)
            return;

        listenerThread.signalExit();
        listenerThread = null;
    }

    private void startListening() {
        if (listenerThread != null)
            return;

        logger.trace("Starting listener thread");
        listenerThread = new ListenerThread();
        listenerThread.start();
    }


}
