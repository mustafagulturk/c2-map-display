package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.measure.Measurement;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.RadarSite;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import io.github.mustafagulturk.c2map.zone.ZoneMonitor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BiConsumer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Details of the selected track, with buttons for the operator to set its identity. */
final class InfoPanel extends VBox {

    static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final RadarSite site;
    private final Label title = new Label("Hedef seçilmedi");
    private final Label hint = new Label("Bilgi için haritada bir hedefe tıklayın.");
    private final GridPane grid = new GridPane();
    private final Label identity = value();
    private final Label position = value();
    private final Label speed = value();
    private final Label course = value();
    private final Label fromRadar = value();
    private final Label zones = value();
    private final Label updated = value();
    private final Label trail = value();
    private final HBox buttons;
    private TrackSnapshot shown;

    InfoPanel(RadarSite site, BiConsumer<String, Identity> setIdentity, Runnable centerOn) {
        this.site = site;
        getStyleClass().add("panel");
        setSpacing(8);
        setPadding(new Insets(10));
        title.getStyleClass().add("panel-title");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        grid.setHgap(10);
        grid.setVgap(4);
        String[] names = {"Kimlik", "Konum", "Hız", "Rota", "Radardan", "Bölge", "Güncelleme", "İz"};
        Label[] values = {identity, position, speed, course, fromRadar, zones, updated, trail};
        for (int i = 0; i < names.length; i++) {
            Label name = new Label(names[i]);
            name.getStyleClass().add("muted");
            grid.addRow(i, name, values[i]);
        }

        Button friend = idButton("Dost", Identity.FRIEND, setIdentity, "btn-friend");
        Button hostile = idButton("Düşman", Identity.HOSTILE, setIdentity, "btn-hostile");
        Button unknown = idButton("Bilinmeyen", Identity.UNKNOWN, setIdentity, "btn-unknown");
        Button center = new Button("Ortala");
        center.setOnAction(e -> centerOn.run());
        buttons = new HBox(6, friend, hostile, unknown, center);
        for (var b : buttons.getChildren()) {
            HBox.setHgrow(b, Priority.ALWAYS);
            ((Button) b).setMaxWidth(Double.MAX_VALUE);
        }

        getChildren().addAll(title, hint, grid, buttons);
        show(null, null);
    }

    private Button idButton(String text, Identity id, BiConsumer<String, Identity> action, String style) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        b.setOnAction(e -> {
            if (shown != null) {
                action.accept(shown.key(), id);
            }
        });
        return b;
    }

    private static Label value() {
        Label l = new Label("-");
        l.getStyleClass().add("value");
        return l;
    }

    /** Shows {@code track}, or the empty state when it is null. {@code zoneMonitor} may be null. */
    void show(TrackSnapshot track, ZoneMonitor zoneMonitor) {
        shown = track;
        boolean has = track != null;
        grid.setVisible(has);
        grid.setManaged(has);
        buttons.setDisable(!has);
        hint.setVisible(!has);
        hint.setManaged(!has);
        if (!has) {
            title.setText("Hedef seçilmedi");
            return;
        }
        title.setText("Hedef " + track.key()
                + (track.source() == TrackSnapshot.Source.RADAR ? "  · radar izi" : "  · veri bağı"));
        identity.setText(ZoneMonitor.identityText(track.identity()));
        identity.getStyleClass().removeAll("id-FRIEND", "id-HOSTILE", "id-UNKNOWN");
        identity.getStyleClass().add("id-" + track.identity().name());
        GeoPoint p = track.position();
        position.setText(String.format(Locale.ROOT, "%.5f° K  %.5f° D", p.lat(), p.lon()));
        speed.setText(String.format(Locale.ROOT, "%.0f kt  (%.0f m/s)", track.speedMps() * 3600 / 1852,
                track.speedMps()));
        course.setText(Measurement.formatBearing(track.courseDeg()));
        Measurement m = new Measurement(site.position(), p);
        fromRadar.setText(m.distanceText() + " · " + Measurement.formatBearing(m.bearingDeg()));
        String zoneText = "-";
        if (zoneMonitor != null) {
            var inside = zoneMonitor.zones().stream()
                    .filter(z -> z.contains(p))
                    .map(z -> z.name())
                    .toList();
            if (!inside.isEmpty()) {
                zoneText = "YASAK BÖLGEDE: " + String.join(", ", inside);
            }
        }
        zones.setText(zoneText);
        zones.getStyleClass().remove("alarm-text");
        if (!zoneText.equals("-")) {
            zones.getStyleClass().add("alarm-text");
        }
        updated.setText(TIME.format(Instant.ofEpochMilli(track.updatedAtMs())));
        trail.setText(track.history().size() + " nokta");
    }

    TrackSnapshot shown() {
        return shown;
    }
}
