package io.github.mustafagulturk.c2map.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives UDP datagrams for one {@link Channel} on a daemon thread and forwards each one to a
 * {@link DatagramSink}, stamped with {@link System#currentTimeMillis()}. Exceptions thrown by the
 * sink are logged and counted; they never stop the receiver.
 */
public final class UdpReceiver implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(UdpReceiver.class.getName());
    private static final int MAX_DATAGRAM = 65_535;
    private static final int RECEIVE_BUFFER_BYTES = 4 * 1024 * 1024;

    private final Channel channel;
    private final DatagramSink sink;
    private final DatagramSocket socket;
    private final Thread thread;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong sinkErrors = new AtomicLong();
    private volatile boolean closed;

    private UdpReceiver(Channel channel, InetSocketAddress bindAddress, DatagramSink sink) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sink = Objects.requireNonNull(sink, "sink");
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
            s.bind(bindAddress);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        this.socket = s;
        this.thread = new Thread(this::run, "udp-" + channel.name().toLowerCase(java.util.Locale.ROOT)
                + "-" + s.getLocalPort());
        thread.setDaemon(true);
    }

    /** Binds to {@code port} on all interfaces and starts receiving. Port 0 picks a free port. */
    public static UdpReceiver start(Channel channel, int port, DatagramSink sink) throws IOException {
        return start(channel, new InetSocketAddress(port), sink);
    }

    /** Binds to {@code bindAddress} and starts receiving. */
    public static UdpReceiver start(Channel channel, InetSocketAddress bindAddress, DatagramSink sink)
            throws IOException {
        UdpReceiver r = new UdpReceiver(channel, bindAddress, sink);
        r.thread.start();
        return r;
    }

    /** Binds to the loopback interface only. */
    public static UdpReceiver startLoopback(Channel channel, int port, DatagramSink sink) throws IOException {
        return start(channel, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), sink);
    }

    private void run() {
        byte[] buf = new byte[MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
            } catch (SocketException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "UDP receive failed on " + channel, e);
                }
                break;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "UDP receive failed on " + channel, e);
                continue;
            }
            long now = System.currentTimeMillis();
            int len = packet.getLength();
            received.incrementAndGet();
            bytes.addAndGet(len);
            try {
                sink.accept(channel, Arrays.copyOf(buf, len), now);
            } catch (RuntimeException e) {
                sinkErrors.incrementAndGet();
                LOG.log(Level.WARNING, "datagram handler failed on " + channel, e);
            }
        }
    }

    public Channel channel() {
        return channel;
    }

    /** The bound local port (useful when started with port 0). */
    public int localPort() {
        return socket.getLocalPort();
    }

    public long received() {
        return received.get();
    }

    public long bytes() {
        return bytes.get();
    }

    /** Number of datagrams whose sink threw an exception. */
    public long sinkErrors() {
        return sinkErrors.get();
    }

    public boolean isOpen() {
        return !closed;
    }

    /** Closes the socket and waits briefly for the receive thread to finish. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        socket.close();
        if (Thread.currentThread() != thread) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
