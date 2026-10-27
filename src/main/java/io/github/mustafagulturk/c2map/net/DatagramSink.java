package io.github.mustafagulturk.c2map.net;

/**
 * Receives raw datagrams from the network or from a recording. Live UDP, the recorder and the
 * replay player all speak this interface, so replayed data goes through exactly the same decoding
 * and tracking code as live data.
 */
@FunctionalInterface
public interface DatagramSink {

    /**
     * @param channel which feed the bytes came from
     * @param data    datagram payload; the receiver must not keep or modify the array after returning
     * @param timeMs  arrival time (epoch ms for live data, original arrival time for replay)
     */
    void accept(Channel channel, byte[] data, long timeMs);
}
