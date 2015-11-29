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

    /**
     * Listener for found services
     */
    public interface Listener {
        void onDiscoveryStarted();
        void onServiceDiscovered(Service service);
        void onDiscoveryFinished();
        void onDiscoveryError(Exception e);
    }

    private int responsePort;

    public int getResponsePort() {
        return responsePort;
    }

    public void setResponsePort(int port) {
        this.responsePort = port;
    }

    private Mode mode = Mode.UDP;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    private int responseTimeoutMillis = 1000;

    public int getResponseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    private InetAddress inetAddress;

    public void setInetAddress(InetAddress inetAddress) {
        this.inetAddress = inetAddress;
    }

    public void setResponseTimeoutMillis(int timeoutMillis) {
        this.responseTimeoutMillis = timeoutMillis;
    }

    private Listener listener;
    private String type;

    /**
     * Base abstract class for listener thread
     */
    private abstract class ListenerThread extends Thread {

        protected Logger logger = LoggerFactory.getLogger(getClass());
        protected AtomicBoolean shouldExit = new AtomicBoolean(false);

        private CountDownLatch threadStartedLatch = new CountDownLatch(1);
        private CountDownLatch socketOpenedLatch = new CountDownLatch(1);
        private CountDownLatch threadFinishedLatch = new CountDownLatch(1);

        protected abstract void closeSocket() throws Exception;

        public void signalExit() {
            logger.trace("Signal exiting");
            shouldExit.set(true);
            try {
                closeSocket();
            } catch (Exception e) {
                logger.error("Close socket error", e);
            }
        }

        public void waitForThreadStarted() {
            try {
                threadStartedLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void waitForSocketOpened() {
            try {
                socketOpenedLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void waitForThreadFinished() {
            try {
                threadFinishedLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        protected abstract void openSocket() throws Exception;
        protected abstract void receiveAndHandleResponse() throws Exception;

        @Override
        public void run() {
            logger.info("Discovery started");
            listener.onDiscoveryStarted();
            threadStartedLatch.countDown(); // signal thread started

            try {
                try {
                    openSocket();
                    socketOpenedLatch.countDown();
                } catch (Exception e) {
                    logger.error("Failed to open socket", e);
                    listener.onDiscoveryError(e);
                    return;
                }

                while (!shouldExit.get()) {
                    try {
                        receiveAndHandleResponse();
                    } catch (Exception e) {
                        if (shouldExit.get())
                            return;

                        logger.error("Handle response error", e);

                        if (listener != null)
                            listener.onDiscoveryError(e);
                    }
                }
            } finally {
                logger.info("Discovery finished");
                listener.onDiscoveryFinished();
                threadFinishedLatch.countDown();
            }
        }
    }

    /**
     * Listens to TCP responses from Publishers
     */
    private class TcpListenerThread extends ListenerThread {

        private ServerSocket serverSocket;

        @Override
        protected void closeSocket() throws Exception {
            serverSocket.close();
            logger.trace("Server socket closed");
        }

        @Override
        protected void openSocket() throws Exception {
            logger.trace("Opening socket for TCP responses on port {}", responsePort);
            serverSocket = ServerSocketFactory.getDefault().createServerSocket(responsePort, 0, inetAddress);
        }

        @Override
        protected void receiveAndHandleResponse() throws Exception {
            logger.trace("Waiting for new connection to {} ...", serverSocket);
            Socket socket = serverSocket.accept(); // blocks thread until client is connected

            logger.trace("New connection accepted: {}", socket);
            new TcpClientThread(socket).start();
        }
    }

    /**
     * Listens to UDP response from Publishers
     */
    private class UdpListenerThread extends ListenerThread {
        private Logger logger = LoggerFactory.getLogger(getClass());

        private MulticastSocket serverSocket;
        private InetAddress group = null;

        public static final int BUFFER_SIZE = 1024; // 1 Kb by default
        private byte[] buffer;

        public UdpListenerThread(int bufferSize) {
            logger.trace("Set input buffer size={}", bufferSize);
            buffer = new byte[bufferSize];
        }

        @Override
        protected void openSocket() throws Exception {
            logger.trace("Opening socket for UDP responses in group={}, port={}", multicastGroup, responsePort);

            serverSocket = new MulticastSocket(responsePort);
            serverSocket.setSoTimeout(1000); // 1s

            group = InetAddress.getByName(multicastGroup);
            serverSocket.joinGroup(group);
        }

        @Override
        protected void closeSocket() throws Exception {
            serverSocket.leaveGroup(group);
            serverSocket.close();
            logger.trace("Server socket closed");
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

        private byte[] extractRequestPacket(DatagramPacket datagramPacket) {
            byte[] requestBytes = new byte[datagramPacket.getLength()];
            System.arraycopy(datagramPacket.getData(), 0, requestBytes, 0, datagramPacket.getLength());

            logger.trace("Packet received ({} bytes):\n{}", requestBytes.length, requestBytes);

            return requestBytes;
        }

        @Override
        protected void receiveAndHandleResponse() throws Exception {
            // receive response
            DatagramPacket datagramPacket = receiveDatagramPacket();
            byte[] requestBytes = extractRequestPacket(datagramPacket);
            String fromHost = datagramPacket.getAddress().getHostAddress();

            // parse response
            Dto.ServiceResponse response = Dto.ServiceResponse.parseFrom(requestBytes);
            logger.trace("Response received:\n{}", response);

            ServiceInfo serviceInfo = new ServiceInfo(
                response.getPort(),
                response.getType(),
                response.getTitle(),
                response.hasPayload() ? response.getPayload().toByteArray() : null);
            Service service = new Service(fromHost, serviceInfo);

            // event 'service discovered'
            logger.info("Service found on {}:{} of type \"{}\" with title \"{}\" and payload \"{}\"",
                fromHost,
                response.getPort(),
                response.getType(),
                response.getTitle(),
                response.hasPayload() ? response.getPayload().toByteArray() : null);
            listener.onServiceDiscovered(service);
        }
    }

    /**
     * Handle client response
     */
    private class TcpClientThread extends Thread {
        private Logger logger = LoggerFactory.getLogger(getClass());
        private Socket socket;

        public TcpClientThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                // parse response
                Dto.ServiceResponse response = Dto.ServiceResponse.parseFrom(socket.getInputStream());
                String fromHost = socket.getInetAddress().getHostAddress();
                ServiceInfo serviceInfo = new ServiceInfo(
                    response.getPort(),
                    response.getType(),
                    response.getTitle(),
                    response.hasPayload() ? response.getPayload().toByteArray() : null);
                logger.trace("Response received from {}:\n{}", socket, response);
                Service service = new Service(fromHost, serviceInfo);

                logger.trace("Close client socket");
                socket.close();

                // event 'service discovered'
                logger.info("Service found on {}:{} of type \"{}\" with title \"{}\" and payload \"{}\"",
                    fromHost,
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

    private void stopDiscovery() {
        stopListening();
        isDiscovering = false;
    }

    private TimerThread timerThread;

    private ListenerThread listenerThread;

    private boolean isDiscovering;

    public boolean isDiscovering() {
        return isDiscovering;
    }

    private String multicastGroup;
    private int multicastPort;

    public ServiceLocator(String multicastGroup, int multicastPort, int responsePort, Listener listener) {
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.responsePort = responsePort;
        this.listener = listener;
    }

    /**
     * Start descovering services
     * @param type service type
     */
    public void discover(String type) {
        if (isDiscovering)
            return;

        logger.info("Start discovering services of type \"{}\"", type);
        this.type = type;

        // event 'started'
        isDiscovering = true;

        // start listening for responses
        startListening();

        listenerThread.waitForSocketOpened();
        // can't send requests before started to wait for responses
        // otherwise we can have response before started to receive it

        try {
            sendRequest();
        } catch (IOException e) {
            logger.error("Failed to send request", e);
            listener.onDiscoveryError(e);

            stopDiscovery();
            return;
        }

        // started discovering, start timeout timer
        startTimer();
    }

    private void sendRequest() throws IOException {
        Dto.ServiceRequest request = Dto.ServiceRequest
            .newBuilder()
            .setType(type)
            .setPort(responsePort)
            .setMode(mode == Mode.TCP
                ? Dto.ServiceRequest.Mode.TCP
                : Dto.ServiceRequest.Mode.UDP)
            .build();

        logger.debug("Build request:\n{}", request);

        // send over UDP multicast
        MulticastSocket clientSocket = new MulticastSocket();
        InetAddress group = InetAddress.getByName(multicastGroup);
        byte[] packet = request.toByteArray();

        logger.trace("Sending UDP packet to group {}:{}:\n{} ({} bytes)", multicastGroup, multicastPort, packet, packet.length);
        DatagramPacket datagramPacket = new DatagramPacket(packet, packet.length, group, multicastPort);
        clientSocket.send(datagramPacket);
        clientSocket.close();
        logger.trace("Packet sent");
    }

    private TimerThread.Listener timerListener = new TimerThread.Listener() {
        @Override
        public void onTimerStarted(TimerThread t) {
            // not needed
        }

        @Override
        public void onTimerFinished(TimerThread t, boolean timeOut) {
            timerThread = null;

            if (timeOut) {
                logger.trace("Discovery timeout");
                stopDiscovery();
            }
        }
    };

    private void startTimer() {
        timerThread = new TimerThread(responseTimeoutMillis, timerListener);
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
        listenerThread = (mode == Mode.TCP
            ? new TcpListenerThread()
            : new UdpListenerThread(UdpListenerThread.BUFFER_SIZE));
        listenerThread.start();
    }

    /**
     * Timer thread just to wait for time
     */
    public static class TimerThread extends Thread {

        /**
         * Listener
         */
        public interface Listener {
            void onTimerStarted(TimerThread timerThread);
            void onTimerFinished(TimerThread timerThread, boolean timeOut);
        }

        private int timeoutMillis;

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        private CountDownLatch latch = new CountDownLatch(1);
        private Listener listener;

        public TimerThread(int timeoutMillis, Listener listener) {
            this.timeoutMillis = timeoutMillis;
            this.listener = listener;
        }

        public void signalExit() {
            latch.countDown();
        }

        @Override
        public void run() {
            try {
                listener.onTimerStarted(this);

                // wait until signaled exit or timout happens
                boolean signaledExit = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);

                listener.onTimerFinished(this, !signaledExit);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
