package io.github.mustafagulturk.c2map.net;

import java.util.List;

/**
 * The plots of one antenna sector, as carried by one RDR1 datagram.
 *
 * @param sequence       unsigned 32-bit sector counter; split datagrams of one sector share it
 * @param timestampUs    sensor time at the end of the sector, microseconds
 * @param sectorStartRad start of the swept sector, radians clockwise from north
 * @param sectorEndRad   end of the swept sector, radians clockwise from north
 * @param plots          detections in this sector (targets and clutter)
 */
public record PlotBatch(long sequence, long timestampUs, double sectorStartRad, double sectorEndRad,
        List<Plot> plots) {

    public PlotBatch {
        plots = List.copyOf(plots);
    }
}
