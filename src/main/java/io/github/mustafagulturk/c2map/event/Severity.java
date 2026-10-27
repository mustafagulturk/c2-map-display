package io.github.mustafagulturk.c2map.event;

/** How urgently an operator must look at an {@link Event}. Ordered from least to most urgent. */
public enum Severity {
    INFO("Bilgi"),
    WARNING("Uyarı"),
    ALARM("Alarm");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /** Operator-facing (Turkish) name. */
    public String label() {
        return label;
    }
}
