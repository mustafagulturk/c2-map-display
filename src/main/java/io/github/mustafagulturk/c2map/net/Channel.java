package io.github.mustafagulturk.c2map.net;

/** Logical input feeds. The code is stored in recordings, so do not reorder. */
public enum Channel {
    /** RDR1 plot datagrams from radar_simulator (radar-target-tracker). */
    RADAR_PLOTS,
    /** TRK1 track messages (data-link style, carries identity). */
    TRACKS;

    public static Channel fromCode(int code) {
        Channel[] values = values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("unknown channel " + code);
        }
        return values[code];
    }
}
