package io.github.mustafagulturk.c2map.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UdpReceiverTest {

    private record Received(Channel channel, byte[] data, long timeMs) {
    }

    @Test
    void forwardsLoopbackDatagrams() throws Exception {
        LinkedBlockingQueue<Received> queue = new LinkedBlockingQueue<>();
        long before = System.currentTimeMillis();
        try (UdpReceiver rx = UdpReceiver.startLoopback(Channel.TRACKS, 0,
                (c, d, t) -> queue.add(new Received(c, d, t)));
                DatagramSocket tx = new DatagramSocket()) {
            assertTrue(rx.localPort() > 0);
            InetAddress lo = InetAddress.getLoopbackAddress();
            for (int i = 1; i <= 3; i++) {
                byte[] payload = new byte[i * 100];
                payload[0] = (byte) i;
                tx.send(new DatagramPacket(payload, payload.length, lo, rx.localPort()));
            }
            for (int i = 1; i <= 3; i++) {
                Received r = queue.poll(5, TimeUnit.SECONDS);
                assertNotNull(r, "datagram " + i);
                assertEquals(Channel.TRACKS, r.channel());
                assertEquals(i * 100, r.data().length);
                assertEquals(i, r.data()[0]);
                assertTrue(r.timeMs() >= before);
            }
            assertEquals(3, rx.received());
            assertEquals(600, rx.bytes());
        }
    }

    @Test
    void survivesSinkExceptionsAndClosesCleanly() throws Exception {
        LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        UdpReceiver rx = UdpReceiver.startLoopback(Channel.RADAR_PLOTS, 0, (c, d, t) -> {
            if (d[0] == 0) {
                throw new IllegalStateException("boom");
            }
            queue.add(d);
        });
        try (DatagramSocket tx = new DatagramSocket()) {
            InetAddress lo = InetAddress.getLoopbackAddress();
            tx.send(new DatagramPacket(new byte[] {0}, 1, lo, rx.localPort()));
            tx.send(new DatagramPacket(new byte[] {1, 2}, 2, lo, rx.localPort()));
            assertArrayEquals(new byte[] {1, 2}, queue.poll(5, TimeUnit.SECONDS));
            assertEquals(1, rx.sinkErrors());
        }
        int port = rx.localPort();
        rx.close();
        assertFalse(rx.isOpen());
        rx.close(); // idempotent
        // The port is free again.
        try (UdpReceiver again = UdpReceiver.startLoopback(Channel.RADAR_PLOTS, port, (c, d, t) -> { })) {
            assertTrue(again.isOpen());
        }
    }
}
