package io.github.mustafagulturk.c2map.net;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.PlotTracker;
import io.github.mustafagulturk.c2map.track.RadarSite;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import io.github.mustafagulturk.c2map.track.TrackStore;
import io.github.mustafagulturk.c2map.track.TrackUpdate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decodes datagrams per {@link Channel} and feeds the track picture: RDR1 plots go through the
 * {@link PlotTracker} (confirmed tracks are published to the {@link TrackStore} as RADAR tracks),
 * TRK1 messages go straight into the store as LINK tracks.
 *
 * <p>Safe to call from several receiver threads at once; radar batches are serialised internally
 * because the tracker is single-threaded. Times passed to the store are the datagram arrival times
 * ({@code timeMs}), so live and replayed data behave the same.
 */
public final class IngestDispatcher implements DatagramSink {

    private static final Logger LOG = Logger.getLogger(IngestDispatcher.class.getName());

    /**
     * Counters for one channel.
     *
     * @param datagrams    datagrams received
     * @param items        plots (RADAR_PLOTS) or track entries (TRACKS) decoded
     * @param errors       datagrams rejected as malformed or that failed in processing
     * @param lastSequence sequence number of the last good datagram, or -1
     * @param lostPackets  datagrams missing according to sequence gaps
     */
    public record ChannelStats(long datagrams, long items, long errors, long lastSequence, long lostPackets) {
    }

    private static final class Counters {
        final AtomicLong datagrams = new AtomicLong();
        final AtomicLong items = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong lost = new AtomicLong();
        long lastSequence = -1;

        /** Large RDR1 sectors are split over datagrams sharing a sequence, so only a jump of more than one is loss. */
        synchronized void sequence(long seq) {
            if (lastSequence >= 0 && seq > lastSequence + 1) {
                lost.addAndGet(seq - lastSequence - 1);
            }
            lastSequence = seq; // a jump backwards means the sender restarted
        }

        synchronized ChannelStats stats() {
            return new ChannelStats(datagrams.get(), items.get(), errors.get(), lastSequence, lost.get());
        }

        synchronized void reset() {
            datagrams.set(0);
            items.set(0);
            errors.set(0);
            lost.set(0);
            lastSequence = -1;
        }
    }

    private final RadarSite site;
    private final PlotTracker tracker;
    private final TrackStore store;
    private final Map<Channel, Counters> counters = new EnumMap<>(Channel.class);
    private final Object radarLock = new Object();
    private volatile Consumer<PlotBatch> plotListener;

    public IngestDispatcher(RadarSite site, PlotTracker tracker, TrackStore store) {
        this.site = Objects.requireNonNull(site, "site");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.store = Objects.requireNonNull(store, "store");
        for (Channel c : Channel.values()) {
            counters.put(c, new Counters());
        }
    }

    public RadarSite site() {
        return site;
    }

    public TrackStore store() {
        return store;
    }

    /**
     * Optional observer of every decoded RDR1 batch (e.g. a raw-plot layer). Called on the ingest
     * thread; {@code null} removes it.
     */
    public void setPlotListener(Consumer<PlotBatch> listener) {
        this.plotListener = listener;
    }

    @Override
    public void accept(Channel channel, byte[] data, long timeMs) {
        Counters c = counters.get(channel);
        c.datagrams.incrementAndGet();
        try {
            switch (channel) {
                case RADAR_PLOTS -> acceptPlots(c, data, timeMs);
                case TRACKS -> acceptTracks(c, data, timeMs);
                default -> c.errors.incrementAndGet();
            }
        } catch (RuntimeException e) {
            c.errors.incrementAndGet();
            LOG.log(Level.WARNING, "failed to process " + channel + " datagram", e);
        }
    }

    private void acceptPlots(Counters c, byte[] data, long timeMs) {
        Optional<PlotBatch> decoded = RadarPlotCodec.decode(data);
        if (decoded.isEmpty()) {
            c.errors.incrementAndGet();
            return;
        }
        PlotBatch batch = decoded.get();
        c.items.addAndGet(batch.plots().size());
        c.sequence(batch.sequence());
        Consumer<PlotBatch> pl = plotListener;
        if (pl != null) {
            pl.accept(batch);
        }
        PlotTracker.Result result;
        synchronized (radarLock) {
            result = tracker.process(batch);
            List<TrackUpdate> updates = new ArrayList<>(result.updated().size());
            for (PlotTracker.TrackState t : result.updated()) {
                GeoPoint pos = site.fromEastNorth(t.east(), t.north());
                updates.add(new TrackUpdate(TrackSnapshot.Source.RADAR, t.id(), pos, t.speedMps(),
                        t.courseDeg(), Identity.UNKNOWN));
            }
            List<String> removed = new ArrayList<>(result.deleted().size());
            for (int id : result.deleted()) {
                removed.add(TrackStore.key(TrackSnapshot.Source.RADAR, id));
            }
            // Inside the radar lock so store updates keep the tracker's order.
            store.apply(updates, removed, timeMs);
        }
    }

    private void acceptTracks(Counters c, byte[] data, long timeMs) {
        Optional<TrackMessage> decoded = TrackMessageCodec.decode(data);
        if (decoded.isEmpty()) {
            c.errors.incrementAndGet();
            return;
        }
        TrackMessage msg = decoded.get();
        c.items.addAndGet(msg.tracks().size());
        c.sequence(msg.sequence());
        List<TrackUpdate> updates = new ArrayList<>(msg.tracks().size());
        List<String> removed = new ArrayList<>();
        for (LinkTrack t : msg.tracks()) {
            if (t.ended()) {
                removed.add(TrackStore.key(TrackSnapshot.Source.LINK, t.id()));
            } else {
                updates.add(new TrackUpdate(TrackSnapshot.Source.LINK, t.id(), new GeoPoint(t.lat(), t.lon()),
                        t.speedMps(), t.courseDeg(), t.identity()));
            }
        }
        store.apply(updates, removed, timeMs);
    }

    public ChannelStats stats(Channel channel) {
        return counters.get(channel).stats();
    }

    /**
     * Forgets tracker state and sequence counters (e.g. before a replay starts). Radar tracks the
     * tracker held are removed from the store; the store itself is not cleared.
     */
    public void reset() {
        synchronized (radarLock) {
            List<String> removed = new ArrayList<>();
            for (int id : tracker.reset()) {
                removed.add(TrackStore.key(TrackSnapshot.Source.RADAR, id));
            }
            store.apply(List.of(), removed, store.latestTimeMs());
        }
        for (Counters c : counters.values()) {
            c.reset();
        }
    }
}
