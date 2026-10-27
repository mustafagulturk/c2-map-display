package io.github.mustafagulturk.c2map.track;

/**
 * Observer of {@link TrackStore} changes. Callbacks run on the thread that changed the store
 * (normally an ingest thread; the UI thread for operator identity changes), outside the store's
 * lock, and must be quick.
 */
@FunctionalInterface
public interface TrackListener {

    /**
     * @param previous the track before this update, or {@code null} for a new track
     * @param current  the track after this update
     */
    void onTrackUpdated(TrackSnapshot previous, TrackSnapshot current);

    /** The track was removed (deleted by its source, timed out, or the store was cleared). */
    default void onTrackDropped(TrackSnapshot last) {
    }
}
