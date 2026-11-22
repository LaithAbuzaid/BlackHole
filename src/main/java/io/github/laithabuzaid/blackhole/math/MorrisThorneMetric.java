package io.github.laithabuzaid.blackhole.math;

/**
 * Exact Morris-Thorne / Dneg ("Interstellar", James et al. 2015, Am. J. Phys. 83, 486)
 * traversable wormhole metric:
 * <pre>
 *   ds^2 = -dt^2 + dl^2 + r(l)^2 (dtheta^2 + sin^2(theta) dphi^2)
 * </pre>
 *
 * <p><b>Physical assumptions &amp; exotic matter:</b>
 * Zero tidal acceleration ({@code g_tt = -1}, zero gravitational redshift) and flare-out at the
 * throat {@code l = 0} ({@code r''(l) > 0} for {@code |l| > a}) violate the Null Energy Condition
 * (NEC), requiring negative radial energy density (exotic matter) concentrated around {@code |l| <= a + O(M_w)}.</p>
 */
public final class MorrisThorneMetric {

    private MorrisThorneMetric() {
    }

    /** Circumferential embedding radius {@code r(l)}. */
    public static double radius(double l, double rho, double halfLengthA, double lensingWidthM) {
        double x = Math.max(0.0, (2.0 * (Math.abs(l) - halfLengthA)) / (Math.PI * lensingWidthM));
        return rho + lensingWidthM * (x * Math.atan(x) - 0.5 * Math.log(1.0 + x * x));
    }

    /** First derivative {@code dr/dl = (2/pi) atan(x) sgn(l)} (continuous {@code C^1} everywhere). */
    public static double dRadiusDl(double l, double halfLengthA, double lensingWidthM) {
        if (Math.abs(l) <= halfLengthA) {
            return 0.0;
        }
        double x = (2.0 * (Math.abs(l) - halfLengthA)) / (Math.PI * lensingWidthM);
        return (2.0 / Math.PI) * Math.atan(x) * Math.signum(l);
    }

    /** Second derivative {@code d^2r/dl^2} (discontinuous jump of {@code 4/(pi^2 M_w)} at {@code |l| = a}). */
    public static double d2RadiusDl2(double l, double halfLengthA, double lensingWidthM) {
        if (Math.abs(l) <= halfLengthA) {
            return 0.0;
        }
        double x = (2.0 * (Math.abs(l) - halfLengthA)) / (Math.PI * lensingWidthM);
        return (4.0 / (Math.PI * Math.PI * lensingWidthM)) / (1.0 + x * x);
    }

    /**
     * Exact null-geodesic ODE for state {@code [l, v_l, phi]} with conserved impact parameter
     * {@code H = r^2 dphi/dlambda}:
     * {@code dl/dlambda = v_l}, {@code dv_l/dlambda = (H^2 / r^3) dr/dl}, {@code dphi/dlambda = H / r^2}.
     */
    public static OdeSystem nullGeodesicOde(double rho, double halfLengthA, double lensingWidthM, double impactH) {
        double h2 = impactH * impactH;
        return (lambda, y, dydl) -> {
            double l = y[0];
            double vl = y[1];
            double r = radius(l, rho, halfLengthA, lensingWidthM);
            double dr = dRadiusDl(l, halfLengthA, lensingWidthM);
            double invR2 = 1.0 / (r * r);
            dydl[0] = vl;
            dydl[1] = (h2 * invR2 / r) * dr;
            dydl[2] = impactH * invR2;
        };
    }

    /** Null geodesic constraint residual {@code v_l^2 + H^2 / r(l)^2 - 1} (identically 0 along exact null rays). */
    public static double nullResidual(double l, double vl, double rho, double halfLengthA, double lensingWidthM, double impactH) {
        double r = radius(l, rho, halfLengthA, lensingWidthM);
        return vl * vl + (impactH * impactH) / (r * r) - 1.0;
    }

    /**
     * Exact analytical total azimuthal angle {@code Delta phi} for a classic Ellis drainhole wormhole
     * ({@code r(l) = sqrt(l^2 + rho^2)}) for a ray passing through the throat ({@code H < rho}) from
     * {@code l = +infty} to {@code l = -infty}:
     * {@code Delta phi(H) = 2 * K(H / rho)} (complete elliptic integral of the first kind via AGM).
     */
    public static double ellisAnalyticalThroughAngle(double impactH, double rho) {
        double k = Math.abs(impactH) / rho;
        if (k >= 1.0) {
            throw new IllegalArgumentException("Through-throat ray requires |H| < rho");
        }
        // Complete elliptic integral K(k) = pi / (2 * AGM(1, sqrt(1 - k^2)))
        double a = 1.0;
        double b = Math.sqrt(1.0 - k * k);
        for (int i = 0; i < 20; i++) {
            double nextA = 0.5 * (a + b);
            double nextB = Math.sqrt(a * b);
            a = nextA;
            b = nextB;
            if (Math.abs(a - b) < 1e-15) break;
        }
        double ellipK = Math.PI / (2.0 * a);
        return 2.0 * k * ellipK;
    }

    /** Ellis drainhole null geodesic ODE ({@code r(l) = sqrt(l^2 + rho^2)}) for analytical convergence tests. */
    public static OdeSystem ellisGeodesicOde(double rho, double impactH) {
        double h2 = impactH * impactH;
        double rho2 = rho * rho;
        return (lambda, y, dydl) -> {
            double l = y[0];
            double vl = y[1];
            double r2 = l * l + rho2;
            dydl[0] = vl;
            dydl[1] = (h2 * l) / (r2 * r2);
            dydl[2] = impactH / r2;
        };
    }

    /**
     * Camera-independent spherical exit direction on {@code S^2}:
     * {@code p_hat(phi) = cos(phi) * eR + sin(phi) * ePhi}.
     * Valid identically in both Universe A ({@code l > 0}) and Universe B ({@code l < 0}) without
     * introducing a camera-dependent Householder reflection.
     */
    public static void sphericalExitDirection(
            double phi,
            double eRx, double eRy, double eRz,
            double ePhix, double ePhiy, double ePhiz,
            double[] outDir3
    ) {
        double c = Math.cos(phi);
        double s = Math.sin(phi);
        outDir3[0] = c * eRx + s * ePhix;
        outDir3[1] = c * eRy + s * ePhiy;
        outDir3[2] = c * eRz + s * ePhiz;
    }
}
