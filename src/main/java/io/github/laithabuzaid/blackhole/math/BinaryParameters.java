package io.github.laithabuzaid.blackhole.math;

/**
 * Validated physical parameter set for a non-precessing (aligned/anti-aligned spin)
 * Binary Black Hole (BBH) coalescence and gravitational-wave observation.
 *
 * <p>Strictly separates intrinsic source-frame binary parameters ({@code M1, M2, chi1, chi2, r0, e0})
 * from extrinsic orientation/detector parameters ({@code orbitalInclinationDeg, observerInclinationDeg,
 * luminosityDistanceMpc, detectorPreset, skyTheta, skyPhi, polarizationPsi}).</p>
 *
 * <p>Cosmological redshift {@code z(D_L)} uses flat {@code Lambda-CDM} (Planck 2018:
 * {@code H0 = 67.4 km/s/Mpc}, {@code Omega_m = 0.315}, {@code Omega_Lambda = 0.685}) so
 * detector-frame total mass {@code M_det = (1 + z) * (M1 + M2)} is physically consistent.</p>
 */
public final class BinaryParameters {

    /** Selectable ground-based GW interferometer presets for antenna pattern evaluation. */
    public enum DetectorPreset {
        OPTIMAL("OPTIMAL (F+=1)", 0.0, 0.0, 0.0),
        LIGO_HANFORD("LIGO H1", 0.58, 0.32, 0.41),
        LIGO_LIVINGSTON("LIGO L1", 0.64, -0.48, 1.12),
        VIRGO("VIRGO V1", 0.88, 0.72, 0.25);

        private final String label;
        public final double defaultThetaRad;
        public final double defaultPhiRad;
        public final double defaultPsiRad;

        DetectorPreset(String label, double thetaRad, double phiRad, double psiRad) {
            this.label = label;
            this.defaultThetaRad = thetaRad;
            this.defaultPhiRad = phiRad;
            this.defaultPsiRad = psiRad;
        }

        public String label() {
            return label;
        }

        public DetectorPreset next() {
            DetectorPreset[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    // Parameter validation bounds
    public static final double MIN_COMP_MASS_SOLAR = 1.0;
    public static final double MAX_COMP_MASS_SOLAR = 250.0;
    public static final double MIN_MASS_RATIO = 0.01;
    public static final double MAX_MASS_RATIO = 1.00;
    public static final double MAX_SPIN_MAG = 0.98;
    public static final double MIN_INITIAL_SEP_M = 6.0;
    public static final double MAX_INITIAL_SEP_M = 20.0;
    public static final double MIN_ECCENTRICITY = 0.0;
    public static final double MAX_ECCENTRICITY = 0.35;
    public static final double MAX_ORBITAL_INC_DEG = 75.0;
    public static final double MIN_DISTANCE_MPC = 10.0;
    public static final double MAX_DISTANCE_MPC = 5000.0;

    // Planck 2018 flat Lambda-CDM constants
    private static final double H0_KM_S_MPC = 67.4;
    private static final double C_KM_S = 299792.458;
    private static final double DH_MPC = C_KM_S / H0_KM_S_MPC; // ~4447.96 Mpc
    private static final double OMEGA_M = 0.315;
    private static final double OMEGA_L = 0.685;

    public final double mass1Solar;
    public final double mass2Solar;
    public final double totalMassSolar;
    public final double massRatioQ;        // q = m2 / m1 <= 1
    public final double symMassRatioEta;   // eta = m1*m2 / (m1 + m2)^2 <= 0.25
    public final double m1Frac;            // X1 = m1 / M
    public final double m2Frac;            // X2 = m2 / M
    public final double chi1;              // Dimensionless aligned spin of primary [-0.98, +0.98]
    public final double chi2;              // Dimensionless aligned spin of secondary [-0.98, +0.98]
    public final double chiEff;            // Mass-weighted effective inspiral spin (m1*chi1 + m2*chi2)/M
    public final double initialSepM;       // Initial EOB separation r_0 / M
    public final double eccentricity;      // Initial orbital eccentricity e_0 [0, 0.35]
    public final double orbitalIncDeg;     // 3D orbital plane inclination in world frame [-75, +75 deg]
    public final double observerIncDeg;    // Line-of-sight inclination iota relative to L_hat [0, 180 deg]
    public final double luminosityDistMpc; // Source luminosity distance D_L [10, 5000 Mpc]
    public final double redshiftZ;         // Cosmological redshift z(D_L)
    public final double detectorMassSolar; // Redshifted detector-frame total mass (1 + z) * M_src
    public final DetectorPreset detectorPreset;
    public final double skyThetaRad;
    public final double skyPhiRad;
    public final double polarizationPsiRad;

    public BinaryParameters(
            double rawMass1Solar,
            double rawMass2Solar,
            double rawChi1,
            double rawChi2,
            double rawInitialSepM,
            double rawEccentricity,
            double rawOrbitalIncDeg,
            double rawObserverIncDeg,
            double rawLuminosityDistMpc,
            DetectorPreset detectorPreset
    ) {
        this(rawMass1Solar, rawMass2Solar, rawChi1, rawChi2, rawInitialSepM, rawEccentricity,
                rawOrbitalIncDeg, rawObserverIncDeg, rawLuminosityDistMpc,
                detectorPreset != null ? detectorPreset : DetectorPreset.OPTIMAL,
                (detectorPreset != null ? detectorPreset : DetectorPreset.OPTIMAL).defaultThetaRad,
                (detectorPreset != null ? detectorPreset : DetectorPreset.OPTIMAL).defaultPhiRad,
                (detectorPreset != null ? detectorPreset : DetectorPreset.OPTIMAL).defaultPsiRad);
    }

    public BinaryParameters(
            double rawMass1Solar,
            double rawMass2Solar,
            double rawChi1,
            double rawChi2,
            double rawInitialSepM,
            double rawEccentricity,
            double rawOrbitalIncDeg,
            double rawObserverIncDeg,
            double rawLuminosityDistMpc,
            DetectorPreset detectorPreset,
            double skyThetaRad,
            double skyPhiRad,
            double polarizationPsiRad
    ) {
        double mA = clampFinite(rawMass1Solar, MIN_COMP_MASS_SOLAR, MAX_COMP_MASS_SOLAR, 35.6);
        double mB = clampFinite(rawMass2Solar, MIN_COMP_MASS_SOLAR, MAX_COMP_MASS_SOLAR, 30.6);
        double cA = clampFinite(rawChi1, -MAX_SPIN_MAG, MAX_SPIN_MAG, 0.0);
        double cB = clampFinite(rawChi2, -MAX_SPIN_MAG, MAX_SPIN_MAG, 0.0);

        // Canonical primary ordering m1 >= m2 (preserving spin-to-body pairing if swapped)
        if (mA >= mB) {
            this.mass1Solar = mA;
            this.mass2Solar = mB;
            this.chi1 = cA;
            this.chi2 = cB;
        } else {
            this.mass1Solar = mB;
            this.mass2Solar = mA;
            this.chi1 = cB;
            this.chi2 = cA;
        }

        // Enforce minimum valid mass ratio q >= 0.01
        double qRaw = this.mass2Solar / this.mass1Solar;
        this.massRatioQ = Math.max(MIN_MASS_RATIO, Math.min(MAX_MASS_RATIO, qRaw));
        this.totalMassSolar = this.mass1Solar + this.mass2Solar;
        this.m1Frac = this.mass1Solar / this.totalMassSolar;
        this.m2Frac = this.mass2Solar / this.totalMassSolar;
        this.symMassRatioEta = this.m1Frac * this.m2Frac;
        this.chiEff = this.m1Frac * this.chi1 + this.m2Frac * this.chi2;

        this.initialSepM = clampFinite(rawInitialSepM, MIN_INITIAL_SEP_M, MAX_INITIAL_SEP_M, 10.0);
        this.eccentricity = clampFinite(rawEccentricity, MIN_ECCENTRICITY, MAX_ECCENTRICITY, 0.0);
        this.orbitalIncDeg = clampFinite(rawOrbitalIncDeg, -MAX_ORBITAL_INC_DEG, MAX_ORBITAL_INC_DEG, 0.0);
        this.observerIncDeg = clampFinite(rawObserverIncDeg, 0.0, 180.0, 30.0);
        this.luminosityDistMpc = clampFinite(rawLuminosityDistMpc, MIN_DISTANCE_MPC, MAX_DISTANCE_MPC, 440.0);
        this.redshiftZ = redshiftFromLuminosityDistanceMpc(this.luminosityDistMpc);
        this.detectorMassSolar = (1.0 + this.redshiftZ) * this.totalMassSolar;
        this.detectorPreset = (detectorPreset != null) ? detectorPreset : DetectorPreset.OPTIMAL;
        this.skyThetaRad = Double.isFinite(skyThetaRad) ? skyThetaRad : this.detectorPreset.defaultThetaRad;
        this.skyPhiRad = Double.isFinite(skyPhiRad) ? skyPhiRad : this.detectorPreset.defaultPhiRad;
        this.polarizationPsiRad = Double.isFinite(polarizationPsiRad) ? polarizationPsiRad : this.detectorPreset.defaultPsiRad;
    }

    /** Creates a quasi-circular parameter set from mass ratio {@code q} (backward-compatible helper). */
    public static BinaryParameters fromMassRatio(double q, double totalMassSolar, double chi1, double chi2) {
        double qc = Math.max(MIN_MASS_RATIO, Math.min(MAX_MASS_RATIO, q));
        double m1 = totalMassSolar / (1.0 + qc);
        double m2 = totalMassSolar - m1;
        return new BinaryParameters(m1, m2, chi1, chi2, 10.0, 0.0, 0.0, 30.0, 440.0, DetectorPreset.OPTIMAL);
    }

    /** GW150914 source-frame median parameters (Abbott et al. 2016 / GWTC-1). */
    public static BinaryParameters presetGw150914() {
        return new BinaryParameters(35.6, 30.6, 0.02, -0.04, 10.0, 0.0, 0.0, 150.0, 440.0, DetectorPreset.LIGO_HANFORD);
    }

    /** GW190412 asymmetric spinning binary (LVK 2020, PRD 102, 043015) with strong higher modes. */
    public static BinaryParameters presetGw190412() {
        return new BinaryParameters(30.1, 8.3, 0.44, 0.0, 10.0, 0.0, 0.0, 42.0, 740.0, DetectorPreset.LIGO_LIVINGSTON);
    }

    /** Equal-mass non-spinning canonical Numerical Relativity benchmark (SXS:BBH:0180). */
    public static BinaryParameters presetEqualNonSpinning() {
        return new BinaryParameters(32.5, 32.5, 0.0, 0.0, 10.0, 0.0, 0.0, 30.0, 440.0, DetectorPreset.OPTIMAL);
    }

    /** Equal-mass high aligned-spin binary demonstrating orbital hang-up and enhanced energy radiation. */
    public static BinaryParameters presetEqualAlignedSpin() {
        return new BinaryParameters(32.5, 32.5, 0.75, 0.75, 9.5, 0.0, 0.0, 30.0, 440.0, DetectorPreset.OPTIMAL);
    }

    /**
     * Computes luminosity distance {@code D_L(z)} in Mpc in flat Planck-2018 {@code Lambda-CDM}
     * via 32-point Gauss-Simpson quadrature.
     */
    public static double luminosityDistanceMpc(double z) {
        if (z <= 0.0) {
            return 0.0;
        }
        final int n = 32;
        double h = z / n;
        double sum = 0.0;
        for (int i = 0; i <= n; i++) {
            double zi = i * h;
            double zp1 = 1.0 + zi;
            double invE = 1.0 / Math.sqrt(OMEGA_M * zp1 * zp1 * zp1 + OMEGA_L);
            double w = (i == 0 || i == n) ? 1.0 : ((i & 1) == 1 ? 4.0 : 2.0);
            sum += w * invE;
        }
        double dc = DH_MPC * (h / 3.0) * sum;
        return (1.0 + z) * dc;
    }

    /** Inverts {@code D_L(z)} via Newton-Raphson to obtain cosmological redshift {@code z}. */
    public static double redshiftFromLuminosityDistanceMpc(double distMpc) {
        if (distMpc <= 0.0 || !Double.isFinite(distMpc)) {
            return 0.0;
        }
        double z = Math.min(2.0, distMpc / DH_MPC);
        for (int iter = 0; iter < 12; iter++) {
            double d = luminosityDistanceMpc(z);
            double err = d - distMpc;
            if (Math.abs(err) < 1e-6 * distMpc) {
                break;
            }
            double zp1 = 1.0 + z;
            double dDdz = d / zp1 + zp1 * DH_MPC / Math.sqrt(OMEGA_M * zp1 * zp1 * zp1 + OMEGA_L);
            z = Math.max(0.0, z - err / Math.max(1.0, dDdz));
        }
        return z;
    }

    private static double clampFinite(double v, double min, double max, double fallback) {
        if (!Double.isFinite(v)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, v));
    }
}
