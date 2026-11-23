package io.github.laithabuzaid.blackhole.math;

/**
 * Physical gravitational-wave multipolar polarization synthesis and ground-based detector
 * response transformation (strictly separated from normalized visual oscilloscope scaling).
 *
 * <h3>Physical Transformations Implemented</h3>
 * <ol>
 *   <li><b>Spin-Weighted Spherical Harmonics {@code -2Y_{lm}(iota, 0)}</b> (Goldberg et al. 1967;
 *       Kidder 2008, <i>PRD</i> 77, 044016) combining {@code +m} and {@code -m} conjugates via
 *       equatorial reflection symmetry {@code h_{l,-m} = (-1)^l h_{lm}^*} for non-precessing binaries
 *       across {@code (2,2), (2,1), (3,3), (4,4)}.</li>
 *   <li><b>Source-to-Detector Redshift &amp; SI Distance Scaling:</b>
 *       {@code M_det = (1 + z) M_src}, {@code h_0 = (G M_det / c^2) / D_L},
 *       {@code f_det = (M omega_GW) / (2*pi * (G M_det / c^3))}.</li>
 *   <li><b>Interferometer Antenna Response {@code (F_+, F_x)}</b> (Jaranowski, Królak &amp; Schutz 1998,
 *       <i>PRD</i> 58, 063001): {@code h_det(t) = F_+(theta, phi, psi) h_+(t) + F_x(theta, phi, psi) h_x(t)}.</li>
 * </ol>
 */
public final class GravitationalWaveDetector {

    // Exact CODATA / IAU geometric-to-SI conversion constants
    public static final double G_MSUN_OVER_C2_METERS = 1476.625038;
    public static final double G_MSUN_OVER_C3_SECONDS = 4.925490947e-6;
    public static final double METERS_PER_MPC = 3.08567758149e22;

    private static final double C_Y22 = Math.sqrt(5.0 / (64.0 * Math.PI));
    private static final double C_Y21 = Math.sqrt(5.0 / (16.0 * Math.PI));
    private static final double C_Y33 = Math.sqrt(21.0 / (128.0 * Math.PI));
    private static final double C_Y44 = (3.0 / 16.0) * Math.sqrt(7.0 / Math.PI);

    /** Zero-allocation mutable output container for physical &amp; visual GW strain readouts. */
    public static final class DetectorOutput {
        /** Dimensionless geometric intrinsic polarizations {@code (R/M) h_+(iota)} and {@code (R/M) h_x(iota)}. */
        public double geomHPlus;
        public double geomHCross;
        public double geomAmplitude;
        /** True physical SI polarizations {@code h_+(t)} and {@code h_x(t)} at luminosity distance {@code D_L}. */
        public double physHPlus;
        public double physHCross;
        /** True physical detector-projected strain {@code h_det(t) = F_+ h_+ + F_x h_x} (dimensionless ~1e-21). */
        public double detectorStrain;
        /** Antenna pattern factors {@code F_+, F_x in [-1, 1]}. */
        public double fPlus;
        public double fCross;
        /** Strain prefactor {@code h_0 = G M_det / (c^2 D_L)}. */
        public double strainScaleH0;
        /** Detector-frame dominant (2,2) GW frequency in Hz (redshifted by 1 + z). */
        public double gwFreqDetHz;
        /** Source-frame dominant (2,2) GW frequency in Hz. */
        public double gwFreqSrcHz;
        /** Higher-mode fractional power contribution {@code sum_{lm!=22} |h_{lm}|^2 / sum_{all} |h_{lm}|^2}. */
        public double higherModePowerFrac;
    }

    private GravitationalWaveDetector() {
    }

    /** Computes exact spin-weighted spherical harmonic {@code -2Y_{lm}(iota, 0)} (real for phi=0). */
    public static double spinWeightedY(int l, int m, double iotaRad) {
        double c = Math.cos(iotaRad);
        double s = Math.sin(iotaRad);
        double omc = 1.0 - c;
        double opc = 1.0 + c;
        if (l == 2 && m == 2)  return C_Y22 * opc * opc;
        if (l == 2 && m == -2) return C_Y22 * omc * omc;
        if (l == 2 && m == 1)  return C_Y21 * s * opc;
        if (l == 2 && m == -1) return C_Y21 * s * omc;
        if (l == 3 && m == 3)  return -C_Y33 * s * opc * opc;
        if (l == 3 && m == -3) return  C_Y33 * s * omc * omc;
        if (l == 4 && m == 4)  return C_Y44 * s * s * opc * opc;
        if (l == 4 && m == -4) return C_Y44 * s * s * omc * omc;
        return 0.0;
    }

    /** Evaluates LIGO/Virgo antenna pattern {@code F_+(theta, phi, psi)}. */
    public static double antennaFPlus(double thetaRad, double phiRad, double psiRad) {
        double ct = Math.cos(thetaRad);
        double c2p = Math.cos(2.0 * phiRad);
        double s2p = Math.sin(2.0 * phiRad);
        double c2psi = Math.cos(2.0 * psiRad);
        double s2psi = Math.sin(2.0 * psiRad);
        return 0.5 * (1.0 + ct * ct) * c2p * c2psi - ct * s2p * s2psi;
    }

    /** Evaluates LIGO/Virgo antenna pattern {@code F_x(theta, phi, psi)}. */
    public static double antennaFCross(double thetaRad, double phiRad, double psiRad) {
        double ct = Math.cos(thetaRad);
        double c2p = Math.cos(2.0 * phiRad);
        double s2p = Math.sin(2.0 * phiRad);
        double c2psi = Math.cos(2.0 * psiRad);
        double s2psi = Math.sin(2.0 * psiRad);
        return 0.5 * (1.0 + ct * ct) * c2p * s2psi + ct * s2p * c2psi;
    }

    /** Converts geometric angular frequency {@code M * omega_GW} to physical frequency in Hz for mass {@code massSolar}. */
    public static double geomOmegaToHz(double omegaGeom, double massSolar) {
        double mSafe = Math.max(0.5, massSolar);
        return omegaGeom / (2.0 * Math.PI * G_MSUN_OVER_C3_SECONDS * mSafe);
    }

    /** Computes physical strain prefactor {@code h_0 = G M_det / (c^2 D_L)}. */
    public static double strainPrefactorH0(double detectorMassSolar, double luminosityDistMpc) {
        double dMeters = Math.max(1.0, luminosityDistMpc) * METERS_PER_MPC;
        return (Math.max(0.5, detectorMassSolar) * G_MSUN_OVER_C2_METERS) / dMeters;
    }

    /**
     * Synthesizes multipolar {@code h_+(t)}, {@code h_x(t)}, and detector-projected {@code h_det(t)}
     * from instantaneous mode amplitudes {@code |R h_{lm} / M|} and phases {@code Phi_{lm}}.
     *
     * <p>Convention: {@code h_{lm} = A_{lm} exp(-i Phi_{lm})}, with {@code h_{l,-m} = (-1)^l h_{lm}^*}.
     * Therefore:
     * {@code Re(h_+ - i h_x) = h_+ = sum [Y_{l,m} + (-1)^l Y_{l,-m}] A_{lm} cos(Phi_{lm})},
     * {@code -Im(h_+ - i h_x) = h_x = sum [Y_{l,m} - (-1)^l Y_{l,-m}] A_{lm} sin(Phi_{lm})}.</p>
     */
    public static void synthesize(
            BinaryParameters params,
            double omega22Geom,
            double a22, double phi22,
            double a21, double phi21,
            double a33, double phi33,
            double a44, double phi44,
            DetectorOutput out
    ) {
        double iota = Math.toRadians(params.observerIncDeg);

        // Combine (+m) and (-m) using equatorial reflection symmetry h_{l,-m} = (-1)^l h_{lm}^*
        double y22p = spinWeightedY(2, 2, iota);
        double y22m = spinWeightedY(2, -2, iota);
        double y21p = spinWeightedY(2, 1, iota);
        double y21m = spinWeightedY(2, -1, iota);
        double y33p = spinWeightedY(3, 3, iota);
        double y33m = spinWeightedY(3, -3, iota);
        double y44p = spinWeightedY(4, 4, iota);
        double y44m = spinWeightedY(4, -4, iota);

        double hp = (y22p + y22m) * a22 * Math.cos(phi22)
                  + (y21p + y21m) * a21 * Math.cos(phi21)
                  + (y33p - y33m) * a33 * Math.cos(phi33)
                  + (y44p + y44m) * a44 * Math.cos(phi44);

        double hx = (y22p - y22m) * a22 * Math.sin(phi22)
                  + (y21p - y21m) * a21 * Math.sin(phi21)
                  + (y33p + y33m) * a33 * Math.sin(phi33)
                  + (y44p - y44m) * a44 * Math.sin(phi44);

        double h0 = strainPrefactorH0(params.detectorMassSolar, params.luminosityDistMpc);
        double fp = antennaFPlus(params.skyThetaRad, params.skyPhiRad, params.polarizationPsiRad);
        double fx = antennaFCross(params.skyThetaRad, params.skyPhiRad, params.polarizationPsiRad);

        double p22 = a22 * a22;
        double pHm = a21 * a21 + a33 * a33 + a44 * a44;
        double pTot = p22 + pHm;

        out.geomHPlus = hp;
        out.geomHCross = hx;
        out.geomAmplitude = Math.hypot(hp, hx);
        out.strainScaleH0 = h0;
        out.physHPlus = h0 * hp;
        out.physHCross = h0 * hx;
        out.fPlus = fp;
        out.fCross = fx;
        out.detectorStrain = fp * out.physHPlus + fx * out.physHCross;
        out.gwFreqSrcHz = geomOmegaToHz(omega22Geom, params.totalMassSolar);
        out.gwFreqDetHz = geomOmegaToHz(omega22Geom, params.detectorMassSolar);
        out.higherModePowerFrac = (pTot > 1e-18) ? (pHm / pTot) : 0.0;
    }
}
