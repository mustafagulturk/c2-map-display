package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.track.RadarSite;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line options of the operator display.
 *
 * @param site             radar position and instrumented range
 * @param plotPort         UDP port for RDR1 plots (radar_simulator default 5005)
 * @param trackPort        UDP port for TRK1 tracks
 * @param zoom             initial map zoom
 * @param zonesFile        where restricted zones are stored
 * @param recordingsDir    default directory for recordings
 * @param replayFile       recording to start playing immediately, or null
 * @param replaySpeed      initial replay speed factor
 * @param benchSeconds     if > 0, measure frame times for this long, write them to benchOut and exit
 * @param benchOut         file the benchmark result line is appended to
 * @param screenshotDir    if set, save window snapshots there (README images) and exit
 * @param screenshotDelayS seconds to wait before the first snapshot
 * @param screenshotFrames number of snapshots (more than one makes a GIF frame sequence)
 */
public record AppConfig(
        RadarSite site,
        int plotPort,
        int trackPort,
        double zoom,
        Path zonesFile,
        Path recordingsDir,
        Path replayFile,
        double replaySpeed,
        double benchSeconds,
        Path benchOut,
        Path screenshotDir,
        double screenshotDelayS,
        int screenshotFrames) {

    static final Path HOME = Path.of(System.getProperty("user.home"), ".c2map");

    public static final String USAGE = """
            Usage: c2-map-display [options]
              --site <lat,lon>        radar position             (default 38.42,27.14 Izmir)
              --range-km <km>         radar instrumented range   (default 40)
              --plot-port <n>         RDR1 plot UDP port         (default 5005)
              --track-port <n>        TRK1 track UDP port        (default 5006)
              --zoom <z>              initial zoom               (default 9.5)
              --zones <file>          restricted zone file       (default ~/.c2map/zones.tsv)
              --recordings <dir>      recording directory        (default ~/.c2map/recordings)
              --replay <file>         start replaying a recording
              --speed <x>             replay speed: 0.5, 1, 2, 4, 8, 16 (default 1)
              --bench <s>             measure frame times for s seconds, then exit
              --bench-out <file>      append benchmark result    (default build/bench.txt)
              --screenshot <dir>      save window snapshots, then exit
              --screenshot-delay <s>  delay before first snapshot (default 8)
              --frames <n>            number of snapshots, 10 per second (default 1)
            Map tiles are read from ~/.c2map/tiles (override with -Dc2map.tiles=<dir>).
            """;

    public static AppConfig parse(List<String> args) {
        GeoPoint sitePos = RadarSite.DEFAULT.position();
        double rangeKm = RadarSite.DEFAULT.maxRangeM() / 1000.0;
        int plotPort = 5005;
        int trackPort = 5006;
        double zoom = 9.5;
        Path zones = HOME.resolve("zones.tsv");
        Path recordings = HOME.resolve("recordings");
        Path replay = null;
        double speed = 1;
        double bench = 0;
        Path benchOut = Path.of("build", "bench.txt");
        Path shots = null;
        double shotDelay = 8;
        int frames = 1;

        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i);
            if (opt.equals("--help") || opt.equals("-h")) {
                throw new IllegalArgumentException(USAGE);
            }
            if (!opt.startsWith("--") || i + 1 >= args.size()) {
                throw new IllegalArgumentException("unknown or incomplete option: " + opt + "\n" + USAGE);
            }
            String value = args.get(++i);
            switch (opt) {
                case "--site" -> sitePos = parsePoint(value);
                case "--range-km" -> rangeKm = Double.parseDouble(value);
                case "--plot-port" -> plotPort = Integer.parseInt(value);
                case "--track-port" -> trackPort = Integer.parseInt(value);
                case "--zoom" -> zoom = Double.parseDouble(value);
                case "--zones" -> zones = Path.of(value);
                case "--recordings" -> recordings = Path.of(value);
                case "--replay" -> replay = Path.of(value);
                case "--speed" -> speed = Double.parseDouble(value);
                case "--bench" -> bench = Double.parseDouble(value);
                case "--bench-out" -> benchOut = Path.of(value);
                case "--screenshot" -> shots = Path.of(value);
                case "--screenshot-delay" -> shotDelay = Double.parseDouble(value);
                case "--frames" -> frames = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: " + opt + "\n" + USAGE);
            }
        }
        if (rangeKm <= 0 || frames < 1 || speed < 0.5 || speed > 16 || plotPort <= 0 || trackPort <= 0 || plotPort > 65535 || trackPort > 65535) {
            throw new IllegalArgumentException("invalid option value\n" + USAGE);
        }
        return new AppConfig(new RadarSite(sitePos, rangeKm * 1000.0), plotPort, trackPort, zoom, zones,
                recordings, replay, speed, bench, benchOut, shots, shotDelay, frames);
    }

    static GeoPoint parsePoint(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected <lat,lon>: " + text);
        }
        return new GeoPoint(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
    }
}
