package io.github.mustafagulturk.c2map.track;

/**
 * Constant-velocity Kalman filter in the east/north plane, state {@code [x, y, vx, vy]}, position
 * measurements only. Process noise is the continuous white-acceleration model with spectral
 * density {@code q} (m²/s³), same as radar-target-tracker's KalmanFilter. Plain arrays keep it
 * allocation free.
 */
final class CvKalmanFilter {

    /** State x, y, vx, vy. */
    final double[] x = new double[4];
    /** Row-major 4x4 covariance. */
    final double[] p = new double[16];
    private final double q;

    CvKalmanFilter(double east, double north, double[] r, double velocityVariance, double q) {
        this.q = q;
        x[0] = east;
        x[1] = north;
        p[0] = r[0];
        p[1] = r[1];
        p[4] = r[2];
        p[5] = r[3];
        p[10] = velocityVariance;
        p[15] = velocityVariance;
    }

    void predict(double dt) {
        if (dt <= 0) {
            return;
        }
        x[0] += dt * x[2];
        x[1] += dt * x[3];
        // P = F P F^T with F = [I dt*I; 0 I]
        double[] f = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = p[i * 4 + j];
                if (i < 2) {
                    v += dt * p[(i + 2) * 4 + j];
                }
                f[i * 4 + j] = v;
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 2; j++) {
                f[i * 4 + j] += dt * f[i * 4 + j + 2];
            }
        }
        System.arraycopy(f, 0, p, 0, 16);
        double dt2 = dt * dt;
        double pp = q * dt2 * dt / 3.0;
        double pv = q * dt2 / 2.0;
        double vv = q * dt;
        p[0] += pp;
        p[5] += pp;
        p[2] += pv;
        p[8] += pv;
        p[7] += pv;
        p[13] += pv;
        p[10] += vv;
        p[15] += vv;
    }

    /** Squared Mahalanobis distance of measurement {@code (zx, zy)} with 2x2 noise {@code r}. */
    double mahalanobisSq(double zx, double zy, double[] r) {
        double s00 = p[0] + r[0];
        double s01 = p[1] + r[1];
        double s10 = p[4] + r[2];
        double s11 = p[5] + r[3];
        double det = s00 * s11 - s01 * s10;
        if (!(det > 0)) {
            return Double.MAX_VALUE;
        }
        double dx = zx - x[0];
        double dy = zy - x[1];
        return (dx * (s11 * dx - s01 * dy) + dy * (-s10 * dx + s00 * dy)) / det;
    }

    /** Joseph-form update; returns false if the innovation covariance is singular. */
    boolean update(double zx, double zy, double[] r) {
        double s00 = p[0] + r[0];
        double s01 = p[1] + r[1];
        double s10 = p[4] + r[2];
        double s11 = p[5] + r[3];
        double det = s00 * s11 - s01 * s10;
        if (!(det > 0)) {
            return false;
        }
        double i00 = s11 / det;
        double i01 = -s01 / det;
        double i10 = -s10 / det;
        double i11 = s00 / det;
        // K = P H^T S^-1 (4x2); P H^T is the first two columns of P.
        double[] k = new double[8];
        for (int i = 0; i < 4; i++) {
            double a = p[i * 4];
            double b = p[i * 4 + 1];
            k[i * 2] = a * i00 + b * i10;
            k[i * 2 + 1] = a * i01 + b * i11;
        }
        double dx = zx - x[0];
        double dy = zy - x[1];
        for (int i = 0; i < 4; i++) {
            x[i] += k[i * 2] * dx + k[i * 2 + 1] * dy;
        }
        // A = I - K H (4x4)
        double[] a = new double[16];
        for (int i = 0; i < 4; i++) {
            a[i * 4 + i] = 1.0;
            a[i * 4] -= k[i * 2];
            a[i * 4 + 1] -= k[i * 2 + 1];
        }
        // P = A P A^T + K R K^T
        double[] ap = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int m = 0; m < 4; m++) {
                    s += a[i * 4 + m] * p[m * 4 + j];
                }
                ap[i * 4 + j] = s;
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int m = 0; m < 4; m++) {
                    s += ap[i * 4 + m] * a[j * 4 + m];
                }
                double k0 = k[i * 2];
                double k1 = k[i * 2 + 1];
                double l0 = k[j * 2];
                double l1 = k[j * 2 + 1];
                s += k0 * (r[0] * l0 + r[1] * l1) + k1 * (r[2] * l0 + r[3] * l1);
                p[i * 4 + j] = s;
            }
        }
        return true;
    }

    double positionSigma() {
        return Math.sqrt(Math.max(p[0], p[5]));
    }
}
