package io.github.laithabuzaid.blackhole.math;

/**
 * Single source of truth for exact Kerr-Schild spacetime geometry in geometric units {@code G = c = M = 1}.
 *
 * <p><b>Coordinate &amp; Spin Conventions (matching {@code kerr_blackhole.frag}):</b>
 * <ul>
 *   <li>Right-handed Cartesian Kerr-Schild coordinates {@code (t, x, y, z)} with symmetry axis {@code y}.</li>
 *   <li>Oblate spheroidal radius {@code r} satisfies {@code r^4 - (R^2 - a^2)r^2 - a^2 y^2 = 0}.</li>
 *   <li>Ingoing null covector {@code l_mu = (1, l_x, l_y, l_z)} with
 *       {@code l = ((r x + a z)/(r^2 + a^2), y/r, (r z - a x)/(r^2 + a^2))}, {@code |l| = 1}.</li>
 *   <li>Metric {@code g_{mu nu} = eta_{mu nu} + 2 H l_mu l_nu}, inverse
 *       {@code g^{mu nu} = eta^{mu nu} - 2 H l^mu l^nu}, where {@code H = r^3 / (r^4 + a^2 y^2)} and
 *       {@code l^mu = (-1, l)}.</li>
 *   <li>With {@code phi = atan2(z, x)}, {@code g_{t phi} = -2 a r / Sigma < 0}, so ZAMO frame-dragging
 *       {@code omega_ZAMO > 0} runs along {@code +phi = (-z, 0, x)} (spin angular momentum vector
 *       {@code J = (0, -a, 0)}).</li>
 * </ul>
 */
public final class KerrMetric {

    public static final double SCHWARZSCHILD_SHADOW_RADIUS = 3.0 * Math.sqrt(3.0);

    private KerrMetric() {
    }

    /** Outer event horizon {@code r_+ = 1 + sqrt(1 - a^2)} (M = 1). */
    public static double outerHorizonRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        return 1.0 + Math.sqrt(Math.max(0.0, 1.0 - ac * ac));
    }

    /** Inner Cauchy horizon {@code r_- = 1 - sqrt(1 - a^2)} (M = 1). */
    public static double innerHorizonRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        return 1.0 - Math.sqrt(Math.max(0.0, 1.0 - ac * ac));
    }

    /** Outer ergosphere radius {@code r_ergo(theta) = 1 + sqrt(1 - a^2 cos^2(theta))} (M = 1). */
    public static double ergosphereRadius(double a, double cosTheta) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        double c2 = cosTheta * cosTheta;
        return 1.0 + Math.sqrt(Math.max(0.0, 1.0 - ac * ac * c2));
    }

    /** Exact Bardeen-Press-Teukolsky (1972) prograde ISCO radius in Boyer-Lindquist / Kerr-Schild {@code r}. */
    public static double progradeIscoRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        double oneMinusA2 = Math.max(0.0, 1.0 - ac * ac);
        double z1 = 1.0 + Math.cbrt(oneMinusA2) * (Math.cbrt(1.0 + ac) + Math.cbrt(1.0 - ac));
        double z2 = Math.sqrt(3.0 * ac * ac + z1 * z1);
        return 3.0 + z2 - Math.sqrt(Math.max(0.0, (3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
    }

    /** Exact Bardeen-Press-Teukolsky (1972) retrograde ISCO radius. */
    public static double retrogradeIscoRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        double oneMinusA2 = Math.max(0.0, 1.0 - ac * ac);
        double z1 = 1.0 + Math.cbrt(oneMinusA2) * (Math.cbrt(1.0 + ac) + Math.cbrt(1.0 - ac));
        double z2 = Math.sqrt(3.0 * ac * ac + z1 * z1);
        return 3.0 + z2 + Math.sqrt(Math.max(0.0, (3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
    }

    /** Exact equatorial prograde photon sphere radius {@code r_{ph+} = 2[1 + cos((2/3) acos(-a))]}. */
    public static double progradePhotonSphereRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        return 2.0 * (1.0 + Math.cos((2.0 / 3.0) * Math.acos(-ac)));
    }

    /** Exact equatorial retrograde photon sphere radius {@code r_{ph-} = 2[1 + cos((2/3) acos(+a))]}. */
    public static double retrogradePhotonSphereRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        return 2.0 * (1.0 + Math.cos((2.0 / 3.0) * Math.acos(ac)));
    }

    /** Exact polar spherical photon orbit radius (L_z = 0): solves {@code r^3 - 3r^2 + a^2 r + a^2 = 0}. */
    public static double polarPhotonOrbitRadius(double a) {
        double ac = Math.max(0.0, Math.min(0.999999, Math.abs(a)));
        // Exact trigonometric root in [1, 3]
        return 1.0 + 2.0 * Math.sqrt(1.0 - (ac * ac) / 3.0)
                * Math.cos((1.0 / 3.0) * Math.acos((1.0 - ac * ac) / Math.pow(1.0 - (ac * ac) / 3.0, 1.5)));
    }

    /**
     * Exact Bardeen (1973) critical shadow boundary point {@code (alpha, beta)} for a spherical photon orbit
     * at radius {@code rp} viewed by a distant observer at polar inclination {@code inclinationRad}
     * (measured from the spin axis; {@code pi/2} = equatorial edge-on).
     *
     * @param outAlphaBeta 2-element array receiving {@code [alpha, +beta]} if valid
     * @return {@code true} if {@code rp} lies on the visible shadow boundary at this inclination.
     */
    public static boolean bardeenShadowPoint(double a, double inclinationRad, double rp, double[] outAlphaBeta) {
        double sinI = Math.sin(inclinationRad);
        double cosI = Math.cos(inclinationRad);
        if (Math.abs(a) < 1e-7) {
            outAlphaBeta[0] = 0.0;
            outAlphaBeta[1] = SCHWARZSCHILD_SHADOW_RADIUS;
            return true;
        }
        double denom = a * (rp - 1.0);
        if (Math.abs(denom) < 1e-12) {
            return false;
        }
        double xi = -(rp * rp * (rp - 3.0) + a * a * (rp + 1.0)) / denom;
        double eta = -(rp * rp * rp * (rp * (rp - 3.0) * (rp - 3.0) - 4.0 * a * a)) / (denom * denom);
        if (Math.abs(sinI) < 1e-7) {
            // Exact polar view: circular shadow with radius sqrt(eta + a^2) at polar photon orbit
            double r2 = eta + a * a;
            if (r2 < 0.0) return false;
            outAlphaBeta[0] = 0.0;
            outAlphaBeta[1] = Math.sqrt(r2);
            return true;
        }
        double alpha = -xi / sinI;
        double cotI = cosI / sinI;
        double betaSq = eta + a * a * cosI * cosI - xi * xi * cotI * cotI;
        if (betaSq < -1e-9) {
            return false;
        }
        outAlphaBeta[0] = alpha;
        outAlphaBeta[1] = Math.sqrt(Math.max(0.0, betaSq));
        return true;
    }

    /**
     * Computes exact horizontal shadow half-width and centroid shift {@code (halfWidth, centroidAlpha, verticalHalfHeight)}
     * for an observer at polar inclination {@code inclinationRad}.
     */
    public static void shadowExtent(double a, double inclinationRad, double[] outExtent) {
        double ac = Math.max(0.0, Math.min(0.999, Math.abs(a)));
        if (ac < 1e-5) {
            outExtent[0] = SCHWARZSCHILD_SHADOW_RADIUS;
            outExtent[1] = 0.0;
            outExtent[2] = SCHWARZSCHILD_SHADOW_RADIUS;
            return;
        }
        double sinI = Math.abs(Math.sin(inclinationRad));
        if (sinI < 1e-3) {
            double rPol = polarPhotonOrbitRadius(ac);
            double[] pt = new double[2];
            bardeenShadowPoint(ac, 0.0, rPol, pt);
            outExtent[0] = pt[1];
            outExtent[1] = 0.0;
            outExtent[2] = pt[1];
            return;
        }
        double rMin = progradePhotonSphereRadius(ac);
        double rMax = retrogradePhotonSphereRadius(ac);
        double minAlpha = Double.POSITIVE_INFINITY;
        double maxAlpha = Double.NEGATIVE_INFINITY;
        double maxBeta = 0.0;
        double[] pt = new double[2];
        int samples = 256;
        for (int i = 0; i <= samples; i++) {
            double rp = rMin + (rMax - rMin) * (i / (double) samples);
            if (bardeenShadowPoint(ac, inclinationRad, rp, pt)) {
                if (pt[0] < minAlpha) minAlpha = pt[0];
                if (pt[0] > maxAlpha) maxAlpha = pt[0];
                if (pt[1] > maxBeta) maxBeta = pt[1];
            }
        }
        if (minAlpha < maxAlpha) {
            outExtent[0] = 0.5 * (maxAlpha - minAlpha);
            outExtent[1] = 0.5 * (maxAlpha + minAlpha);
            outExtent[2] = maxBeta;
        } else {
            outExtent[0] = SCHWARZSCHILD_SHADOW_RADIUS;
            outExtent[1] = 0.0;
            outExtent[2] = SCHWARZSCHILD_SHADOW_RADIUS;
        }
    }

    /** Exact Kerr oblate spheroidal radius {@code r(x, y, z; a)}. */
    public static double kerrRadius(double x, double y, double z, double a) {
        double rSq = x * x + y * y + z * z;
        double a2 = a * a;
        if (a2 < 1e-16) {
            return Math.sqrt(Math.max(1e-16, rSq));
        }
        double b = rSq - a2;
        double disc = Math.sqrt(b * b + 4.0 * a2 * y * y);
        return Math.sqrt(Math.max(1e-16, 0.5 * (b + disc)));
    }

    /** Evaluates Kerr-Schild {@code H}, {@code l = (lx, ly, lz)}, and {@code r} into {@code out5 = [r, H, lx, ly, lz]}. */
    public static void metricScalarAndNullVector(double x, double y, double z, double a, double[] out5) {
        double r = kerrRadius(x, y, z, a);
        double r2 = r * r;
        double a2 = a * a;
        double denomH = r2 * r2 + a2 * y * y;
        double h = (r2 * r) / Math.max(1e-18, denomH);
        double invR2A2 = 1.0 / (r2 + a2);
        out5[0] = r;
        out5[1] = h;
        out5[2] = (r * x + a * z) * invR2A2;
        out5[3] = y / r;
        out5[4] = (r * z - a * x) * invR2A2;
    }

    /**
     * Fills 4x4 covariant metric {@code g_{mu nu}} and contravariant inverse {@code g^{mu nu}}
     * in row-major order {@code [t, x, y, z]} (indices {@code 0..3}).
     */
    public static void metricAndInverse(double x, double y, double z, double a, double[][] gCov, double[][] gInv) {
        double[] hl = new double[5];
        metricScalarAndNullVector(x, y, z, a, hl);
        double twoH = 2.0 * hl[1];
        double[] lCov = {1.0, hl[2], hl[3], hl[4]};
        double[] lCon = {-1.0, hl[2], hl[3], hl[4]};
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                double eta = (mu == nu) ? (mu == 0 ? -1.0 : 1.0) : 0.0;
                gCov[mu][nu] = eta + twoH * lCov[mu] * lCov[nu];
                gInv[mu][nu] = eta - twoH * lCon[mu] * lCon[nu];
            }
        }
    }

    /**
     * Null geodesic Hamiltonian {@code H_ham = 0.5 * g^{mu nu} p_mu p_nu = 0.5 * (-pt^2 + |p|^2 - 2 H q^2)}
     * where {@code q = -pt + l . p}. Must remain 0 along any null geodesic.
     */
    public static double hamiltonian(double x, double y, double z, double pt, double px, double py, double pz, double a) {
        double[] hl = new double[5];
        metricScalarAndNullVector(x, y, z, a, hl);
        double q = -pt + hl[2] * px + hl[3] * py + hl[4] * pz;
        return 0.5 * (-pt * pt + px * px + py * py + pz * pz - 2.0 * hl[1] * q * q);
    }

    /**
     * Exact analytic Hamiltonian derivatives {@code (dx/dlambda, dp/dlambda)} for state
     * {@code y = [x, y, z, px, py, pz]} with conserved {@code pt}.
     */
    public static void hamiltonianDerivatives(double[] state, double pt, double a, double[] dydl) {
        double x = state[0], y = state[1], z = state[2];
        double px = state[3], py = state[4], pz = state[5];

        double r = kerrRadius(x, y, z, a);
        double invR = 1.0 / r;
        double r2 = r * r;
        double r3 = r2 * r;
        double r4 = r2 * r2;
        double a2 = a * a;
        double y2 = y * y;
        double r2a2 = r2 + a2;
        double invR2a2 = 1.0 / r2a2;

        double denomH = r4 + a2 * y2;
        double invDenomH = 1.0 / Math.max(1e-18, denomH);
        double h = r3 * invDenomH;

        double lx = (r * x + a * z) * invR2a2;
        double ly = y * invR;
        double lz = (r * z - a * x) * invR2a2;

        double q = -pt + lx * px + ly * py + lz * pz;
        double twoHq = 2.0 * h * q;

        // dx/dlambda = p - 2 H q l
        dydl[0] = px - twoHq * lx;
        dydl[1] = py - twoHq * ly;
        dydl[2] = pz - twoHq * lz;

        // grad r = (r x, (r^2 + a^2) y / r, r z) / (2 r^2 - R^2 + a^2)
        double gradRDen = Math.max(1e-18, 2.0 * r2 - (x * x + y2 + z * z) + a2);
        double invGradRDen = 1.0 / gradRDen;
        double grx = (r * x) * invGradRDen;
        double gry = (r2a2 * y * invR) * invGradRDen;
        double grz = (r * z) * invGradRDen;

        // grad H = dH/dr * grad r + (0, dH/dy, 0)
        double dHdr = (3.0 * r2 * denomH - 4.0 * r3 * r3) * invDenomH * invDenomH;
        double dHdy = (-2.0 * a2 * y * r3) * invDenomH * invDenomH;
        double ghx = dHdr * grx;
        double ghy = dHdr * gry + dHdy;
        double ghz = dHdr * grz;

        // grad(l . p) = partial_direct(l . p) + d(l . p)/dr * grad r
        double dDirX = (r * px - a * pz) * invR2a2;
        double dDirY = py * invR;
        double dDirZ = (a * px + r * pz) * invR2a2;

        double invR2a2Sq = invR2a2 * invR2a2;
        double dlxdr = (x * (a2 - r2) - 2.0 * a * r * z) * invR2a2Sq;
        double dlydr = -y * invR * invR;
        double dlzdr = (z * (a2 - r2) + 2.0 * a * r * x) * invR2a2Sq;
        double dLpDr = dlxdr * px + dlydr * py + dlzdr * pz;

        double glpx = dDirX + dLpDr * grx;
        double glpy = dDirY + dLpDr * gry;
        double glpz = dDirZ + dLpDr * grz;

        // dp/dlambda = q^2 grad H + 2 H q grad(l . p)
        double q2 = q * q;
        dydl[3] = q2 * ghx + twoHq * glpx;
        dydl[4] = q2 * ghy + twoHq * glpy;
        dydl[5] = q2 * ghz + twoHq * glpz;
    }

    /** Creates a reusable 6-component null-geodesic {@link OdeSystem} for fixed {@code (a, pt)}. */
    public static OdeSystem nullGeodesicOde(double a, double pt) {
        return (lambda, state, dydl) -> hamiltonianDerivatives(state, pt, a, dydl);
    }

    /** Conserved axial angular momentum {@code L_y = p_phi = -z p_x + x p_z} (positive for prograde orbits). */
    public static double conservedLy(double x, double z, double px, double pz) {
        return -z * px + x * pz;
    }

    /**
     * Exact singularity-free Carter constant {@code Q} for a null geodesic in Cartesian Kerr-Schild coordinates.
     * Smooth everywhere including the symmetry poles {@code theta = 0, pi} and equator {@code theta = pi/2}.
     */
    public static double carterConstant(
            double x, double y, double z,
            double pt, double px, double py, double pz,
            double a
    ) {
        double r = kerrRadius(x, y, z, a);
        double cosT = y / r;
        double cos2 = cosT * cosT;
        double sin2 = Math.max(0.0, 1.0 - cos2);
        double r2 = r * r;
        return (r2 + a * a) * cos2 * (px * px + pz * pz)
                - 2.0 * r * cosT * (x * px + z * pz) * py
                + r2 * sin2 * py * py
                - a * a * cos2 * pt * pt;
    }

    /**
     * Exact Kerr-Schild static-shell orthonormal observer initial null covector {@code (pt, px, py, pz)}
     * for local unit ray direction {@code (dx, dy, dz)}. Valid outside the ergosphere ({@code 2H < 1}).
     */
    public static double initStaticShellRay(
            double x, double y, double z,
            double dx, double dy, double dz,
            double a,
            double[] outState6
    ) {
        double[] hl = new double[5];
        metricScalarAndNullVector(x, y, z, a, hl);
        double twoH = 2.0 * hl[1];
        double alpha = Math.sqrt(Math.max(1e-8, 1.0 - twoH));
        double dl = dx * hl[2] + dy * hl[3] + dz * hl[4];
        double perpX = dx - dl * hl[2];
        double perpY = dy - dl * hl[3];
        double perpZ = dz - dl * hl[4];
        double pParallel = (dl - twoH) / (alpha * alpha);
        outState6[0] = x;
        outState6[1] = y;
        outState6[2] = z;
        outState6[3] = perpX / alpha + pParallel * hl[2];
        outState6[4] = perpY / alpha + pParallel * hl[3];
        outState6[5] = perpZ / alpha + pParallel * hl[4];
        return 1.0; // pt = 1, omega_obs = 1 / alpha
    }

    /**
     * Exact {@code E = 1, L_y = 0, Q = 0} Doran zero-angular-momentum free-fall observer 4-velocity
     * {@code u^mu = [u^t, u^x, u^y, u^z]} in Cartesian ingoing Kerr-Schild coordinates.
     * Smooth and regular across both outer ({@code r_+}) and inner ({@code r_-}) horizons.
     */
    public static void doranFourVelocity(double x, double y, double z, double a, double[] outU4) {
        double r = kerrRadius(x, y, z, a);
        double r2 = r * r;
        double a2 = a * a;
        double cosT = y / r;
        double sigma = r2 + a2 * cosT * cosT;
        double r2a2 = r2 + a2;
        double betaD = Math.sqrt(Math.max(0.0, (2.0 * r) / r2a2));
        double onePlusBeta = 1.0 + betaD;

        // Contravariant ingoing Kerr-Schild components (exact analytic cancellation of Delta):
        // u^r = -(r^2 + a^2) / Sigma * betaD
        // u^t_KS = 1 + 2 r / (Sigma * (1 + betaD))
        // u^phi_KS = -a * betaD / (Sigma * (1 + betaD))
        double ur = -(r2a2 / sigma) * betaD;
        double ut = 1.0 + (2.0 * r) / (sigma * onePlusBeta);
        double uphi = -(a * betaD) / (sigma * onePlusBeta);

        // Pushforward from (r, theta=const, phi_KS) to Cartesian (x, y, z):
        // x + i z = (r + i a) sin(theta) exp(i phi_KS)
        // => d(x,y,z)/dr = l = ((r x + a z)/(r^2+a^2), y/r, (r z - a x)/(r^2+a^2))
        // => d(x,y,z)/dphi_KS = (-z, 0, +x)
        double invR2a2 = 1.0 / r2a2;
        double lx = (r * x + a * z) * invR2a2;
        double ly = y / r;
        double lz = (r * z - a * x) * invR2a2;
        outU4[0] = ut;
        outU4[1] = ur * lx - uphi * z;
        outU4[2] = ur * ly;
        outU4[3] = ur * lz + uphi * x;
    }

    /** Exact Doran proper-time radial infall rate {@code |dr/dtau| = ((r^2+a^2)/Sigma) * sqrt(2r/(r^2+a^2))}. */
    public static double doranRadialInfallSpeed(double r, double cosTheta, double a) {
        double r2 = r * r;
        double a2 = a * a;
        double sigma = Math.max(1e-8, r2 + a2 * cosTheta * cosTheta);
        double r2a2 = r2 + a2;
        return (r2a2 / sigma) * Math.sqrt(Math.max(0.0, (2.0 * r) / r2a2));
    }

    /** Exact Boyer-Lindquist coordinate time rate {@code dt_BL/dtau} for an E=1, L_z=0 Doran infaller (valid for r > r_+). */
    public static double doranDtBlDtau(double r, double cosTheta, double a) {
        double r2 = r * r;
        double a2 = a * a;
        double sin2 = Math.max(0.0, 1.0 - cosTheta * cosTheta);
        double sigma = Math.max(1e-8, r2 + a2 * cosTheta * cosTheta);
        double delta = Math.max(1e-6, r2 - 2.0 * r + a2);
        double r2a2 = r2 + a2;
        return (r2a2 * r2a2 - a2 * delta * sin2) / (sigma * delta);
    }

    /** Exact ZAMO frame-dragging angular velocity {@code omega_ZAMO = 2 a r / ((r^2+a^2)^2 - a^2 Delta sin^2(theta))}. */
    public static double zamoOmega(double r, double cosTheta, double a) {
        double r2 = r * r;
        double a2 = a * a;
        double sin2 = Math.max(0.0, 1.0 - cosTheta * cosTheta);
        double delta = r2 - 2.0 * r + a2;
        double r2a2 = r2 + a2;
        double denom = Math.max(1e-8, r2a2 * r2a2 - a2 * delta * sin2);
        return (2.0 * a * r) / denom;
    }
}
