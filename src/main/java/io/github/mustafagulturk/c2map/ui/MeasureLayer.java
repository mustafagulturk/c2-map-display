package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.map.MapLayer;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.measure.Measurement;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/** Draws the {@link MeasureTool} ruler: dashed line, end markers and a range/bearing label. */
public final class MeasureLayer implements MapLayer {

    private static final Color LINE = Color.web("#ffd54f");
    private static final Color HALO = Color.rgb(0, 0, 0, 0.6);
    private static final Color BOX = Color.rgb(20, 24, 28, 0.85);
    private static final Font FONT = Font.font("System", FontWeight.BOLD, 12);
    private static final double PAD = 5;

    private final MeasureTool tool;

    public MeasureLayer(MeasureTool tool) {
        this.tool = Objects.requireNonNull(tool);
    }

    @Override
    public void draw(GraphicsContext g, MapViewport viewport, long nowMs) {
        Measurement m = tool.measurement();
        if (m == null) {
            return;
        }
        Point2D a = viewport.toScreen(m.from());
        Point2D b = viewport.toScreen(m.to());
        g.save();
        try {
            g.setLineDashes(8, 6);
            g.setLineWidth(4);
            g.setStroke(HALO);
            g.strokeLine(a.getX(), a.getY(), b.getX(), b.getY());
            g.setLineWidth(2);
            g.setStroke(LINE);
            g.strokeLine(a.getX(), a.getY(), b.getX(), b.getY());
            g.setLineDashes();

            marker(g, a, false);
            marker(g, b, true);

            if (a.distance(b) >= 2) {
                label(g, m, a.midpoint(b));
            }
        } finally {
            g.restore();
        }
    }

    private static void marker(GraphicsContext g, Point2D p, boolean end) {
        double r = 5;
        g.setLineWidth(2);
        g.setStroke(HALO);
        g.strokeOval(p.getX() - r - 1, p.getY() - r - 1, 2 * r + 2, 2 * r + 2);
        if (end) {
            g.setFill(LINE);
            g.fillOval(p.getX() - r, p.getY() - r, 2 * r, 2 * r);
        } else {
            g.setStroke(LINE);
            g.strokeOval(p.getX() - r, p.getY() - r, 2 * r, 2 * r);
            g.strokeLine(p.getX() - r - 3, p.getY(), p.getX() + r + 3, p.getY());
            g.strokeLine(p.getX(), p.getY() - r - 3, p.getX(), p.getY() + r + 3);
        }
    }

    private static void label(GraphicsContext g, Measurement m, Point2D at) {
        String line1 = m.distanceText();
        String line2 = m.bearingText();
        double w = Math.max(textWidth(line1), textWidth(line2)) + 2 * PAD;
        double lineH = 15;
        double h = 2 * lineH + 2 * PAD;
        double x = at.getX() + 10;
        double y = at.getY() - h - 6;
        g.setFill(BOX);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setStroke(LINE);
        g.setLineWidth(1);
        g.strokeRoundRect(x, y, w, h, 6, 6);
        g.setFont(FONT);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFill(Color.WHITE);
        g.fillText(line1, x + PAD, y + PAD);
        g.setFill(LINE);
        g.fillText(line2, x + PAD, y + PAD + lineH);
    }

    private static double textWidth(String s) {
        Text t = new Text(s);
        t.setFont(FONT);
        return t.getLayoutBounds().getWidth();
    }
}
