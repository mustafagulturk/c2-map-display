package io.github.mustafagulturk.c2map.track;

/** Standard identity of a track, as used for symbology (MIL-STD-2525 / APP-6 colours). */
public enum Identity {
    FRIEND("Friend"),
    HOSTILE("Hostile"),
    UNKNOWN("Unknown");

    private final String label;

    Identity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Wire code used by the TRK1 message and recordings. */
    public byte code() {
        return (byte) ordinal();
    }

    public static Identity fromCode(int code) {
        Identity[] values = values();
        return code >= 0 && code < values.length ? values[code] : UNKNOWN;
    }
}
