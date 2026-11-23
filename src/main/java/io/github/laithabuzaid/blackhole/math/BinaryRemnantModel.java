package io.github.laithabuzaid.blackhole.math;

/**
 * Numerical-Relativity-calibrated remnant mass, final spin, radiated energy/angular momentum,
 * and Kerr Quasi-Normal Mode (QNM) spectroscopy for unequal-mass, aligned/anti-aligned spinning
 * binary black hole coalescences.
 *
 * <h3>Published Fitting Formulas &amp; Calibration Domains</h3>
 * <ul>
 *   <li><b>Final Dimensionless Spin {@code a_f}:</b> Barausse &amp; Rezzolla (2009),
 *       <i>ApJ</i> 704, L40 ({@code arXiv:0904.2577}, Eq. 6–10), calibrated over
 *       {@code q in (0, 1]} and aligned/anti-aligned spins {@code chi_1, chi_2 in [-0.99, +0.99]}
 *       with exact Schwarzschild test-particle ISCO limit {@code 2*sqrt(3)} as {@code nu -> 0}.</li>
 *   <li><b>Radiated Mass-Energy {@code E_rad / M = 1 - M_f / M}:</b> Barausse, Morozova &amp; Rezzolla (2012),
 *       <i>ApJ</i> 758, 63 ({@code arXiv:1206.3803}, Eq. 18–19), combining exact Bardeen–Press–Teukolsky
 *       (1972) Kerr ISCO binding energy {@code 1 - E_ISCO(a_tilde)} in the extreme mass-ratio limit
 *       with NR-calibrated equal-mass coefficients {@code p0 = 0.04827, p1 = 0.01707}.</li>
 *   <li><b>Kerr Fundamental QNM Modes {@code (l, m, n=0)}:</b> Berti, Cardoso &amp; Will (2006),
 *       <i>PRD</i> 73, 064030 ({@code gr-qc/0512160}, Table VIII) for {@code (2,2,0)}, {@code (2,1,0)},
 *       {@code (3,3,0)}, and {@code (4,4,0)}, valid for {@code |a_f| in [0, 0.998]}.</li>
 * </ul>
 */
public final class BinaryRemnantModel {

    /** Mode identifier for supported fundamental (n = 0) Kerr QNM multipoles. */
    public enum QnmMode {
        M22(2, 2, 1.5251, -1.1568, 0.1292,  0.7000, 1.4187, -0.4990),
        M21(2, 1, 0.6000, -0.2339, 0.4175, -0.3000, 2.3561, -0.2277),
        M33(3, 3, 1.8956, -1.3043, 0.1818,  0.9000, 2.3430, -0.4810),
        M44(4, 4, 2.3000, -1.5056, 0.2244,  1.1929, 3.1191, -0.4825);

        public final int l;
        public final int m;
        public final double f1, f2, f3;
        public final double q1, q2, q3;

        QnmMode(int l, int m, double f1, double f2, double f3, double q1, double q2, double q3) {
            this.l = l;
            this.m = m;
            this.f1 = f1;
            this.f2 = f2;
            this.f3 = f3;
            this.q1 = q1;
            this.q2 = q2;
            this.q3 = q3;
        }
    }

    // Barausse & Rezzolla (2009), ApJ 704, L40 Table / Eq. (10)
    private static final double BR09_S4 = -0.1229;
    private static final double BR09_S5 =  0.4537;
    private static final double BR09_T0 = -2.8904;
    private static final double BR09_T2 = -3.5171;
    private static final double BR09_T3 =  2.5763;

    // Barausse, Morozova & Rezzolla (2012), ApJ 758, 63 Eq. (18)-(19)
    private static final double BMR12_P0 = 0.04827;
    private static final double BMR12_P1 = 0.01707;

    /** Immutable computed remnant properties in geometric units ({@code G = c = M_tot = 1}). */
    public record RemnantProperties(
            double finalMassFrac,         // M_f / M_tot
            double radiatedEnergyFrac,    // E_rad / M_tot = 1 - M_f / M_tot
            double finalSpinSigned,       // Signed a_f along L_hat (negative if anti-aligned spin flips remnant)
            double finalSpinMag,          // |a_f| in [0, 0.998]
            double finalAngMom,           // J_f / M_tot^2 = a_f * (M_f / M_tot)^2
            double omega220,              // M_tot * omega_220
            double gamma220,              // M_tot * gamma_220 = 1 / tau_220
            double tau220,                // tau_220 / M_tot
            double omega210,
            double gamma210,
            double omega330,
            double gamma330,
            double omega440,
            double gamma440
    ) {
        public double omegaFor(QnmMode mode) {
            return switch (mode) {
                case M22 -> omega220;
                case M21 -> omega210;
                case M33 -> omega330;
                case M44 -> omega440;
            };
        }

        public double gammaFor(QnmMode mode) {
            return switch (mode) {
                case M22 -> gamma220;
                case M21 -> gamma210;
                case M33 -> gamma330;
                case M44 -> gamma440;
            };
        }

        public double tauFor(QnmMode mode) {
            return 1.0 / Math.max(1e-6, gammaFor(mode));
        }
    }

    private BinaryRemnantModel() {
    }

    /**
     * Exact Bardeen–Press–Teukolsky (1972) equatorial Kerr ISCO radius {@code r_ISCO(a)} for
     * signed spin {@code a in [-0.998, +0.998]} (positive = prograde, negative = retrograde).
     */
    public static double kerrIscoRadiusSigned(double aSigned) {
        double a = Math.max(-0.998, Math.min(0.998, aSigned));
        double absA = Math.abs(a);
        double a2 = absA * absA;
        double cbrt1mA2 = Math.cbrt(1.0 - a2);
        double z1 = 1.0 + cbrt1mA2 * (Math.cbrt(1.0 + absA) + Math.cbrt(1.0 - absA));
        double z2 = Math.sqrt(3.0 * a2 + z1 * z1);
        double term = Math.sqrt(Math.max(0.0, (3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
        return (a >= 0.0) ? (3.0 + z2 - term) : (3.0 + z2 + term);
    }

    /**
     * Exact Bardeen–Press–Teukolsky (1972) specific energy at Kerr equatorial ISCO:
     * {@code E_ISCO(a) = sqrt(1 - 2 / (3 * r_ISCO(a)))}.
     */
    public static double kerrIscoEnergy(double aSigned) {
        double rIsco = kerrIscoRadiusSigned(aSigned);
        return Math.sqrt(Math.max(0.0, 1.0 - 2.0 / (3.0 * rIsco)));
    }

    /**
     * Computes NR-calibrated remnant mass, final spin, radiated energy, final angular momentum,
     * and Berti–Cardoso–Will (2006) fundamental QNM frequencies and damping rates.
     *
     * @param m1Frac primary mass fraction {@code X1 = m1 / M >= m2 / M}
     * @param m2Frac secondary mass fraction {@code X2 = m2 / M}
     * @param chi1   primary aligned dimensionless spin {@code [-0.99, +0.99]}
     * @param chi2   secondary aligned dimensionless spin {@code [-0.99, +0.99]}
     */
    public static RemnantProperties compute(double m1Frac, double m2Frac, double chi1, double chi2) {
        // Canonical primary m1 >= m2
        double x1 = m1Frac;
        double x2 = m2Frac;
        double s1 = Math.max(-0.99, Math.min(0.99, chi1));
        double s2 = Math.max(-0.99, Math.min(0.99, chi2));
        if (x2 > x1) {
            double tm = x1; x1 = x2; x2 = tm;
            double ts = s1; s1 = s2; s2 = ts;
        }
        double total = Math.max(1e-12, x1 + x2);
        x1 /= total;
        x2 /= total;
        double q = Math.max(1e-6, x2 / x1);
        double nu = x1 * x2;
        double q2 = q * q;
        double opq2 = (1.0 + q) * (1.0 + q);

        // 1. Final dimensionless spin: Barausse & Rezzolla (2009) Eq. (6)-(10)
        double aBarBr = (s1 + q2 * s2) / (1.0 + q2);
        double lTilde = 2.0 * Math.sqrt(3.0)
                + BR09_T2 * nu
                + BR09_T3 * nu * nu
                + BR09_S4 * aBarBr * aBarBr
                + (BR09_S5 * nu + BR09_T0 + 2.0) * aBarBr;
        double afSigned = (s1 + s2 * q2 + lTilde * q) / opq2;
        afSigned = Math.max(-0.998, Math.min(0.998, afSigned));
        double afMag = Math.abs(afSigned);

        // 2. Radiated mass-energy: Barausse, Morozova & Rezzolla (2012) Eq. (18)-(19)
        double aTildeBmr = (s1 + q2 * s2) / opq2;
        double eIsco = kerrIscoEnergy(aTildeBmr);
        double eRadEqMassSpin = BMR12_P0 + 4.0 * BMR12_P1 * aTildeBmr * (aTildeBmr + 1.0);
        double eRadFrac = (1.0 - eIsco) * nu
                + 4.0 * nu * nu * (4.0 * eRadEqMassSpin + eIsco - 1.0);
        // Physical bounds: strictly positive and bounded below Hawking area-theorem ceiling
        eRadFrac = Math.max(1e-6, Math.min(0.18, eRadFrac));
        double mfFrac = 1.0 - eRadFrac;

        // Final dimensionless angular momentum J_f / M_tot^2 = a_f * (M_f / M_tot)^2
        double jFinal = afSigned * mfFrac * mfFrac;

        // 3. Berti, Cardoso & Will (2006) Table VIII Kerr QNMs (scaled to M_tot = 1 via 1 / M_f)
        double[] q22 = evalBcwQnm(QnmMode.M22, afMag, mfFrac);
        double[] q21 = evalBcwQnm(QnmMode.M21, afMag, mfFrac);
        double[] q33 = evalBcwQnm(QnmMode.M33, afMag, mfFrac);
        double[] q44 = evalBcwQnm(QnmMode.M44, afMag, mfFrac);

        return new RemnantProperties(
                mfFrac, eRadFrac, afSigned, afMag, jFinal,
                q22[0], q22[1], 1.0 / q22[1],
                q21[0], q21[1],
                q33[0], q33[1],
                q44[0], q44[1]
        );
    }

    /** Convenience overload from {@link BinaryParameters}. */
    public static RemnantProperties compute(BinaryParameters params) {
        return compute(params.m1Frac, params.m2Frac, params.chi1, params.chi2);
    }

    /**
     * Evaluates Berti–Cardoso–Will (2006) Table VIII fit for mode {@code (l, m, n=0)}:
     * returns {@code [M_tot * omega_lm0, M_tot * gamma_lm0]}.
     */
    public static double[] evalBcwQnm(QnmMode mode, double spinMag, double remnantMassFrac) {
        double j = Math.max(0.0, Math.min(0.998, Math.abs(spinMag)));
        double oneMinusJ = Math.max(1e-4, 1.0 - j);
        double mf = Math.max(0.5, remnantMassFrac);
        double mfOmega = mode.f1 + mode.f2 * Math.pow(oneMinusJ, mode.f3);
        double qualityQ = Math.max(0.5, mode.q1 + mode.q2 * Math.pow(oneMinusJ, mode.q3));
        double omega = mfOmega / mf;
        double gamma = omega / (2.0 * qualityQ);
        return new double[]{omega, gamma};
    }
}
