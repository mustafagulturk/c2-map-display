package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.event.Event;
import io.github.mustafagulturk.c2map.event.EventLog;
import io.github.mustafagulturk.c2map.event.Severity;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.map.MapView;
import io.github.mustafagulturk.c2map.map.TileStore;
import io.github.mustafagulturk.c2map.net.Channel;
import io.github.mustafagulturk.c2map.net.DatagramSink;
import io.github.mustafagulturk.c2map.net.IngestDispatcher;
import io.github.mustafagulturk.c2map.net.UdpReceiver;
import io.github.mustafagulturk.c2map.record.Player;
import io.github.mustafagulturk.c2map.record.Recorder;
import io.github.mustafagulturk.c2map.record.RecordingInfo;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.PlotTracker;
import io.github.mustafagulturk.c2map.track.TrackListener;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import io.github.mustafagulturk.c2map.track.TrackStore;
import io.github.mustafagulturk.c2map.zone.RestrictedZone;
import io.github.mustafagulturk.c2map.zone.ZoneFile;
import io.github.mustafagulturk.c2map.zone.ZoneMonitor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * Operator display: wires the UDP feeds, tracker, zones, recorder and replay player to the map
 * and the side panels.
 *
 * <pre>
 *  UDP 5005 (RDR1) ─┐                    ┌─ PlotTracker ─┐
 *                   ├─ LiveGate ─ Recorder ─ IngestDispatcher ┤               ├─ TrackStore ─ ZoneMonitor ─ EventLog
 *  UDP 5006 (TRK1) ─┘          Player ─────┘                └───────────────┘       │
 *                                                                          TrackLayer (map, 60 Hz)
 * </pre>
 */
public class C2MapApp extends Application {

    private static final List<Double> SPEEDS = List.of(0.5, 1.0, 2.0, 4.0, 8.0, 16.0);

    private AppConfig config;
    private final EventLog eventLog = new EventLog();
    private final TrackStore store = new TrackStore();
    private IngestDispatcher dispatcher;
    private Recorder recorder;
    private Player player;
    private ZoneMonitor zoneMonitor;
    private final List<UdpReceiver> receivers = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "c2-io");
        t.setDaemon(true);
        return t;
    });

    /** True while a recording is being replayed; live datagrams are then ignored. */
    private volatile boolean replaying;

    private MapView map;
    private TrackLayer trackLayer;
    private ZoneEditor zoneEditor;
    private MeasureTool measureTool;
    private InfoPanel infoPanel;
    private final ObservableList<Event> events = FXCollections.observableArrayList();
    private final Label alarmBanner = new Label();
    private final Label status = new Label();
    private final Label modeLabel = new Label();
    private final Label replayLabel = new Label();
    private final ToggleButton recordButton = new ToggleButton("● Kayıt");
    private final Button playButton = new Button("Oynat");
    private final Button liveButton = new Button("Canlıya dön");
    private final ChoiceBox<Double> speedBox = new ChoiceBox<>(FXCollections.observableArrayList(SPEEDS));
    private Stage stage;

    @Override
    public void init() {
        config = AppConfig.parse(getParameters().getRaw());
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        dispatcher = new IngestDispatcher(config.site(), new PlotTracker(), store);
        recorder = new Recorder(dispatcher);
        player = new Player(dispatcher);
        zoneMonitor = new ZoneMonitor(eventLog);
        loadZones();

        store.addListener(new TrackListener() {
            @Override
            public void onTrackUpdated(TrackSnapshot previous, TrackSnapshot current) {
                zoneMonitor.onTrackUpdated(previous, current);
            }

            @Override
            public void onTrackDropped(TrackSnapshot last) {
                zoneMonitor.onTrackDropped(last);
            }
        });
        zoneMonitor.addZoneListener(zones -> io.execute(() -> saveZones(zones)));
        eventLog.addListener(e -> Platform.runLater(() -> onEvent(e)));
        eventLog.addAlarmSink(e -> Platform.runLater(() -> onAlarm(e)));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setCenter(buildMap());
        root.setTop(buildToolbar());
        root.setRight(buildSidePanel());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1440, 880);
        scene.getStylesheets().add(getClass().getResource("c2map.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setTitle("C2 Harita Ekranı · Komuta Kontrol");
        stage.setScene(scene);
        stage.show();

        startLiveFeeds();
        Timeline housekeeping = new Timeline(new KeyFrame(Duration.millis(250), e -> tick()));
        housekeeping.setCycleCount(Timeline.INDEFINITE);
        housekeeping.play();

        eventLog.log(Severity.INFO, String.format(Locale.ROOT,
                "Sistem başladı. Radar %s, plot UDP %d, iz UDP %d", config.site().position(),
                config.plotPort(), config.trackPort()), System.currentTimeMillis());
        if (config.replayFile() != null) {
            openRecording(config.replayFile());
        }
        if (config.benchSeconds() > 0) {
            runBenchmark();
        }
        if (config.screenshotDir() != null) {
            runScreenshots();
        }
    }

    // ---------------------------------------------------------------- layout

    private Region buildMap() {
        map = new MapView();
        map.setCenter(config.site().position());
        map.setZoom(config.zoom());
        map.setTimeSource(this::nowMs);
        Path credit = map.tileStore().root().resolve(TileStore.ATTRIBUTION_FILE);
        try {
            if (Files.isRegularFile(credit)) {
                map.setAttribution(Files.readString(credit).strip());
            }
        } catch (IOException e) {
            // Keep the default OpenStreetMap credit.
        }

        trackLayer = new TrackLayer(store::snapshot);
        zoneEditor = new ZoneEditor(map, map.canvas(), zoneMonitor, map::requestRepaint, map::setPanEnabled);
        measureTool = new MeasureTool(map, map.canvas(), map::requestRepaint, map::setPanEnabled);
        zoneEditor.setStatusSink(text -> Platform.runLater(() -> modeLabel.setText(text)));

        map.addLayer(new RadarSiteLayer(config.site()));
        map.addLayer(new ZoneLayer(zoneMonitor, zoneEditor));
        map.addLayer(trackLayer);
        map.addLayer(new MeasureLayer(measureTool));

        map.canvas().addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress()
                    || zoneEditor.isActive() || measureTool.isActive()) {
                return;
            }
            select(trackLayer.trackAt(map, e.getX(), e.getY()).map(TrackSnapshot::key).orElse(null));
        });

        alarmBanner.getStyleClass().add("alarm-banner");
        alarmBanner.setVisible(false);
        alarmBanner.setMaxWidth(Double.MAX_VALUE);
        Button ack = new Button("Onayla");
        ack.setOnAction(e -> acknowledgeAlarms());
        HBox bannerBox = new HBox(10, alarmBanner, ack);
        bannerBox.getStyleClass().add("alarm-box");
        bannerBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(alarmBanner, Priority.ALWAYS);
        bannerBox.visibleProperty().bind(alarmBanner.visibleProperty());
        bannerBox.setMaxHeight(Region.USE_PREF_SIZE);
        bannerBox.setPickOnBounds(false);

        StackPane stack = new StackPane(map, bannerBox);
        StackPane.setAlignment(bannerBox, Pos.TOP_CENTER);
        StackPane.setMargin(bannerBox, new Insets(10, 60, 0, 60));
        return stack;
    }

    private ToolBar buildToolbar() {
        ToggleGroup tools = new ToggleGroup();
        ToggleButton pan = new ToggleButton("Kaydır");
        ToggleButton measure = new ToggleButton("Mesafe / Kerteriz");
        ToggleButton zone = new ToggleButton("Yasak bölge çiz");
        pan.setToggleGroup(tools);
        measure.setToggleGroup(tools);
        zone.setToggleGroup(tools);
        pan.setSelected(true);
        pan.setTooltip(new Tooltip("Sol tuşla sürükle: kaydır, tekerlek: yakınlaştır, tıkla: hedef seç"));
        measure.setTooltip(new Tooltip("Basılı tutup sürükleyin ya da iki noktaya tıklayın. Esc: temizle"));
        zone.setTooltip(new Tooltip("Köşelere tıklayın, çift tıklama veya Enter ile kapatın. "
                + "Esc: iptal, sağ tık: son köşeyi / bölgeyi sil"));
        tools.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            // Turn the old tool off first, so it cannot re-enable panning under the new one.
            zoneEditor.setActive(false);
            measureTool.setActive(false);
            if (now == measure) {
                measureTool.setActive(true);
                modeLabel.setText("Ölçüm: başlangıç noktasına basın, sürükleyin.");
            } else if (now == zone) {
                zoneEditor.setActive(true);
            } else {
                modeLabel.setText("");
            }
            map.requestFocus();
        });

        Button clearZones = new Button("Bölgeleri sil");
        clearZones.setOnAction(e -> zoneMonitor.clear());

        CheckBox trails = new CheckBox("İz çizgileri");
        trails.setSelected(true);
        trails.selectedProperty().addListener((o, a, b) -> trackLayer.setShowTrails(b));
        CheckBox labels = new CheckBox("Etiketler");
        labels.setSelected(true);
        labels.selectedProperty().addListener((o, a, b) -> trackLayer.setShowLabels(b));

        recordButton.getStyleClass().add("record-button");
        recordButton.setTooltip(new Tooltip("Gelen tüm UDP verisini dosyaya yazar"));
        recordButton.setOnAction(e -> toggleRecording());
        Button open = new Button("Kaydı aç…");
        open.setOnAction(e -> chooseRecording());
        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlay());
        speedBox.setValue(SPEEDS.contains(config.replaySpeed()) ? config.replaySpeed() : 1.0);
        speedBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Double v) {
                return v == null ? "" : (v == Math.rint(v) ? String.valueOf(v.intValue()) : String.valueOf(v)) + "×";
            }

            @Override
            public Double fromString(String s) {
                return Double.valueOf(s.replace("×", ""));
            }
        });
        speedBox.valueProperty().addListener((o, a, b) -> player.setSpeed(b));
        liveButton.setDisable(true);
        liveButton.setOnAction(e -> returnToLive());
        replayLabel.getStyleClass().add("muted");

        return new ToolBar(pan, measure, zone, clearZones, new Separator(), trails, labels, new Separator(),
                recordButton, open, playButton, speedBox, liveButton, replayLabel);
    }

    private VBox buildSidePanel() {
        infoPanel = new InfoPanel(config.site(), (key, id) -> {
            store.setIdentity(key, id);
            eventLog.log(Severity.INFO, key + " operatör tarafından " + ZoneMonitor.identityText(id)
                    + " olarak sınıflandırıldı", nowMs());
            refreshInfo();
        }, () -> {
            TrackSnapshot t = infoPanel.shown();
            if (t != null) {
                map.setCenter(t.position());
            }
        });

        Label eventsTitle = new Label("Olay listesi");
        eventsTitle.getStyleClass().add("panel-title");
        ListView<Event> list = new ListView<>(events);
        list.setCellFactory(v -> new EventCell());
        list.setPlaceholder(new Label("Henüz olay yok"));
        list.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> {
            if (e != null && e.trackKey() != null) {
                select(e.trackKey());
                store.get(e.trackKey()).ifPresent(t -> map.setCenter(t.position()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox eventsBox = new VBox(6, eventsTitle, list);
        eventsBox.getStyleClass().add("panel");
        eventsBox.setPadding(new Insets(10));
        VBox.setVgrow(eventsBox, Priority.ALWAYS);

        VBox side = new VBox(8, infoPanel, eventsBox);
        side.setPrefWidth(380);
        side.setMinWidth(320);
        side.setPadding(new Insets(8));
        side.getStyleClass().add("side");
        return side;
    }

    private HBox buildStatusBar() {
        Label cursor = new Label();
        cursor.getStyleClass().add("mono");
        map.cursorPositionProperty().addListener((o, a, p) ->
                cursor.setText(p == null ? "" : MapView.formatPosition(p)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        status.getStyleClass().add("mono");
        modeLabel.getStyleClass().add("mode-label");
        HBox bar = new HBox(16, modeLabel, spacer, status, cursor);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ---------------------------------------------------------------- data flow

    private void startLiveFeeds() {
        DatagramSink liveGate = (channel, data, timeMs) -> {
            if (!replaying) {
                recorder.accept(channel, data, timeMs);
            }
        };
        try {
            receivers.add(UdpReceiver.start(Channel.RADAR_PLOTS, config.plotPort(), liveGate));
            receivers.add(UdpReceiver.start(Channel.TRACKS, config.trackPort(), liveGate));
        } catch (IOException | UncheckedIOException e) {
            eventLog.log(Severity.WARNING, "UDP portu açılamadı: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    /** Data clock: wall time when live, recorded time during replay. */
    private long nowMs() {
        return replaying ? player.currentDataTimeMs() : System.currentTimeMillis();
    }

    private void tick() {
        if (!replaying) {
            store.expire(System.currentTimeMillis());
        } else {
            store.expire(player.currentDataTimeMs());
            updateReplayLabel();
        }
        refreshInfo();
        if (recorder.isRecording()) {
            recordButton.setText(String.format(Locale.ROOT, "● Kayıt %ds",
                    (System.currentTimeMillis() - recorder.startedAtMs()) / 1000));
        }
        var plots = dispatcher.stats(Channel.RADAR_PLOTS);
        var links = dispatcher.stats(Channel.TRACKS);
        var fs = map.frameStats();
        status.setText(String.format(Locale.ROOT,
                "%s  ·  %d hedef  ·  RDR1 %d pkt, %d kayıp, %d hata  ·  TRK1 %d pkt  ·  %.0f FPS, çizim %.1f ms",
                replaying ? "TEKRAR" : "CANLI", store.size(), plots.datagrams(), plots.lostPackets(),
                plots.errors() + links.errors(), links.datagrams(), fs.fps(), fs.averageDrawMs()));
    }

    private void select(String key) {
        trackLayer.setSelected(key);
        refreshInfo();
        map.requestRepaint();
    }

    private void refreshInfo() {
        String key = trackLayer.selected();
        infoPanel.show(key == null ? null : store.get(key).orElse(null), zoneMonitor);
    }

    // ---------------------------------------------------------------- events and alarms

    private void onEvent(Event e) {
        events.add(0, e);
        if (events.size() > eventLog.capacity()) {
            events.remove(events.size() - 1);
        }
    }

    private void onAlarm(Event e) {
        alarmBanner.setText("⚠ ALARM  " + InfoPanel.TIME.format(java.time.Instant.ofEpochMilli(e.timeMs()))
                + "  " + e.message() + (eventLog.unacknowledgedAlarms() > 1
                        ? "   (+" + (eventLog.unacknowledgedAlarms() - 1) + " onaylanmamış)" : ""));
        alarmBanner.setVisible(true);
        if (!replaying && config.screenshotDir() == null && config.benchSeconds() <= 0) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private void acknowledgeAlarms() {
        eventLog.acknowledgeAlarms();
        alarmBanner.setVisible(false);
    }

    private void onKey(KeyEvent e) {
        if (zoneEditor.isActive()) {
            zoneEditor.handleKey(e);
        } else if (measureTool.isActive()) {
            measureTool.handleKey(e);
        } else if (e.getCode() == KeyCode.ESCAPE) {
            select(null);
        }
    }

    // ---------------------------------------------------------------- zones

    private void loadZones() {
        try {
            zoneMonitor.setZones(ZoneFile.load(config.zonesFile()));
        } catch (IOException e) {
            eventLog.log(Severity.WARNING, "Bölge dosyası okunamadı: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    private void saveZones(List<RestrictedZone> zones) {
        try {
            Files.createDirectories(config.zonesFile().toAbsolutePath().getParent());
            ZoneFile.save(config.zonesFile(), zones);
        } catch (IOException e) {
            eventLog.log(Severity.WARNING, "Bölgeler kaydedilemedi: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    // ---------------------------------------------------------------- record and replay

    private void toggleRecording() {
        try {
            if (recorder.isRecording()) {
                recorder.stop();
                recordButton.setText("● Kayıt");
                eventLog.log(Severity.INFO, String.format(Locale.ROOT, "Kayıt durdu: %s (%d paket)",
                        recorder.path().getFileName(), recorder.datagramCount()), System.currentTimeMillis());
            } else {
                Files.createDirectories(config.recordingsDir());
                Path file = config.recordingsDir().resolve("kayit-" + LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".c2rec");
                recorder.start(file);
                eventLog.log(Severity.INFO, "Kayıt başladı: " + file, System.currentTimeMillis());
            }
        } catch (IOException e) {
            recordButton.setSelected(false);
            eventLog.log(Severity.WARNING, "Kayıt hatası: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    private void chooseRecording() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Kayıt dosyası aç");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("C2 kayıtları", "*.c2rec"));
        if (Files.isDirectory(config.recordingsDir())) {
            chooser.setInitialDirectory(config.recordingsDir().toFile());
        }
        var file = chooser.showOpenDialog(stage);
        if (file != null) {
            openRecording(file.toPath());
        }
    }

    private void openRecording(Path file) {
        try {
            if (recorder.isRecording()) {
                recordButton.setSelected(false);
                toggleRecording();
            }
            player.stop();
            replaying = true;
            dispatcher.reset();
            store.clear();
            RecordingInfo info = player.open(file);
            player.setSpeed(speedBox.getValue());
            player.setOnFinished(() -> Platform.runLater(() -> {
                playButton.setText("Oynat");
                eventLog.log(Severity.INFO, "Tekrar oynatma bitti", player.currentDataTimeMs());
            }));
            player.play();
            playButton.setText("Duraklat");
            playButton.setDisable(false);
            liveButton.setDisable(false);
            eventLog.log(Severity.INFO, String.format(Locale.ROOT, "Tekrar oynatılıyor: %s (%d paket, %d s)",
                    file.getFileName(), info.datagramCount(), info.durationMs() / 1000), info.firstTimeMs());
        } catch (IOException e) {
            replaying = false;
            eventLog.log(Severity.WARNING, "Kayıt açılamadı: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    private void togglePlay() {
        if (player.state() == Player.State.PLAYING) {
            player.pause();
            playButton.setText("Oynat");
        } else {
            if (player.state() != Player.State.PAUSED) {
                dispatcher.reset();
                store.clear();
            }
            player.play();
            playButton.setText("Duraklat");
        }
    }

    private void returnToLive() {
        player.stop();
        dispatcher.reset();
        store.clear();
        replaying = false;
        playButton.setDisable(true);
        playButton.setText("Oynat");
        liveButton.setDisable(true);
        replayLabel.setText("");
        eventLog.log(Severity.INFO, "Canlı veriye dönüldü", System.currentTimeMillis());
    }

    private void updateReplayLabel() {
        long elapsed = player.elapsedMs() / 1000;
        long total = player.durationMs() / 1000;
        replayLabel.setText(String.format(Locale.ROOT, "%02d:%02d / %02d:%02d  %s", elapsed / 60, elapsed % 60,
                total / 60, total % 60, player.state() == Player.State.FINISHED ? "bitti" : ""));
    }

    // ---------------------------------------------------------------- README tooling

    /** Measures frame times with whatever data is flowing in and appends one result line to a file. */
    private void runBenchmark() {
        double warmup = 5;
        Timeline t = new Timeline(
                new KeyFrame(Duration.seconds(warmup), e -> map.frameStats().reset()),
                new KeyFrame(Duration.seconds(warmup + config.benchSeconds()), e -> {
                    var fs = map.frameStats();
                    Runtime rt = Runtime.getRuntime();
                    long visible = store.snapshot().stream()
                            .filter(s -> {
                                var p = map.toScreen(s.position());
                                return p.getX() >= 0 && p.getY() >= 0 && p.getX() <= map.width()
                                        && p.getY() <= map.height();
                            }).count();
                    String line = String.format(Locale.ROOT,
                            "tracks=%d visible=%d fps=%.1f drawAvgMs=%.2f drawP99Ms=%.2f drawMaxMs=%.2f "
                                    + "frames=%d heapMB=%d pipeline=%s%n",
                            store.size(), visible, fs.fps(), fs.averageDrawMs(), fs.p99DrawMs(), fs.maxDrawMs(),
                            fs.size(), (rt.totalMemory() - rt.freeMemory()) >> 20,
                            System.getProperty("prism.order", "default"));
                    try {
                        Path out = config.benchOut().toAbsolutePath();
                        Files.createDirectories(out.getParent());
                        Files.writeString(out, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    System.out.print(line);
                    Platform.exit();
                }));
        t.play();
    }

    /** Saves window snapshots at 10 per second; used to make the README screenshot and GIF. */
    private void runScreenshots() {
        Path dir = config.screenshotDir();
        int[] index = {0};
        Timeline t = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            try {
                Files.createDirectories(dir);
                var image = stage.getScene().snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        dir.resolve(String.format(Locale.ROOT, "frame-%04d.png", index[0])).toFile());
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            if (++index[0] >= config.screenshotFrames()) {
                Platform.exit();
            }
        }));
        t.setDelay(Duration.seconds(config.screenshotDelayS()));
        t.setCycleCount(config.screenshotFrames());
        t.play();
        Timeline pick = new Timeline(new KeyFrame(Duration.seconds(Math.max(0.5, config.screenshotDelayS() - 3)),
                e -> selectNearestTrack()));
        pick.play();
    }

    /** For README snapshots: selects the track nearest the radar so the info panel is filled in. */
    private void selectNearestTrack() {
        store.snapshot().stream()
                .min(java.util.Comparator.comparingDouble(
                        s -> Geodesy.distanceMeters(s.position(), config.site().position())))
                .ifPresent(s -> select(s.key()));
    }

    @Override
    public void stop() throws IOException {
        for (UdpReceiver r : receivers) {
            r.close();
        }
        player.stop();
        if (recorder.isRecording()) {
            recorder.stop();
        }
        io.shutdown();
        if (map != null) {
            map.dispose();
        }
    }

    /** Colour-coded row in the event list. */
    private static final class EventCell extends ListCell<Event> {
        @Override
        protected void updateItem(Event e, boolean empty) {
            super.updateItem(e, empty);
            getStyleClass().removeAll("ev-INFO", "ev-WARNING", "ev-ALARM");
            if (empty || e == null) {
                setText(null);
                return;
            }
            setText(InfoPanel.TIME.format(java.time.Instant.ofEpochMilli(e.timeMs())) + "  "
                    + e.severity().label().toUpperCase(Locale.forLanguageTag("tr")) + "  " + e.message());
            setWrapText(true);
            setPrefWidth(0);
            getStyleClass().add("ev-" + e.severity().name());
        }
    }

    public static void main(String[] args) {
        try {
            AppConfig.parse(List.of(args));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(e.getMessage().equals(AppConfig.USAGE) ? 0 : 2);
        }
        launch(args);
    }
}
