package io.github.mustafagulturk.c2map.net;

/**
 * One raw radar detection from an RDR1 datagram.
 *
 * @param rangeM      slant range from the radar, metres
 * @param azimuthRad  bearing, radians clockwise from north
 * @param amplitudeDb returned amplitude, dB (display only)
 */
public record Plot(double rangeM, double azimuthRad, double amplitudeDb) {
}
