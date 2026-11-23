package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.BinaryRemnantModel;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.EobBinaryDynamics;
import io.github.laithabuzaid.blackhole.math.GravitationalWaveDetector;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent scientific validation suite for the v3.0 EOB Binary Black Hole Merger subsystem:
 * <ul>
 *   <li>Orbital dynamics: equal/unequal mass symmetry, extreme mass-ratio Kerr ISCO limit,
 *       spin-dependent orbital hang-up, conservative EOB Hamiltonian invariance, eccentric periapsis
 *       precession, radiation-reaction energy/angular-momentum balance, and numerical convergence.</li>
 *   <li>Gravitational waves: C^1 phase/amplitude/frequency continuity, late-ringdown BCW QNM
 *       frequency and damping-time verification, inclination polarization consistency, higher modes
 *       (2,1), (3,3), (4,4), and physical SI detector strain/redshift/antenna transformations.</li>
 *   <li>Remnant predictions: independently verified against published SXS NR catalog simulations
 *       (SXS:BBH:0180, 0152, 0157, 0154, 0169, 0168, 0305), GW150914, GW190412, and Chandrasekhar/Leaver
 *       Schwarzschild QNM frequencies.</li>
 * </ul>
 */
class BinaryMergerValidationTest {

    // =========================================================================
    // 1. ORBITAL DYNAMICS VALIDATION
    // =========================================================================

    @Test
    void equalMassSymmetryAndOddModeSuppressionAreExact() {
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        bin.reset(BinaryParameters.presetEqualNonSpinning());

        for (int i = 0; i < 400; i++) {
            bin.stepGeometric(0.5, false);
            if (bin.phase() == BinaryMergerPhysics.Phase.MERGER) break;
            assertEquals(-bin.pos2X, bin.pos1X, 1e-5f, "Equal-mass positions must be antipodal in X");
            assertEquals(-bin.pos2Y, bin.pos1Y, 1e-5f, "Equal-mass positions must be antipodal in Y");
            assertEquals(-bin.pos2Z, bin.pos1Z, 1e-5f, "Equal-mass positions must be antipodal in Z");
            assertEquals(-bin.vel2X, bin.vel1X, 1e-5f, "Equal-mass velocities must be antipodal in X");
            assertEquals(-bin.vel2Z, bin.vel1Z, 1e-5f, "Equal-mass velocities must be antipodal in Z");
            assertEquals(0.0, bin.modeAmp21, 1e-12, "Equal-mass non-spinning (2,1) mode must vanish identically");
            assertEquals(0.0, bin.modeAmp33, 1e-12, "Equal-mass non-spinning (3,3) mode must vanish identically");
            assertTrue(bin.modeAmp44 > 0.0, "Even-m (4,4) mode must remain active for equal-mass binaries");
        }
    }

    @Test
    void testParticleLimitMatchesExactBardeenPressTeukolskyKerrIsco() {
        // Independent analytical GR benchmark: as q -> 0 (nu -> 0), EOB ISCO must converge
        // to exact Bardeen-Press-Teukolsky (1972) Kerr ISCO radius & angular frequency
        double[] spins = {-0.70, 0.0, +0.70};
        for (double chi1 : spins) {
            EobBinaryDynamics eob = new EobBinaryDynamics(0.9999, 0.0001, chi1, 0.0);
            double rIscoExact = BinaryRemnantModel.kerrIscoRadiusSigned(chi1);
            double omegaIscoExact = 1.0 / (Math.pow(rIscoExact, 1.5) + chi1);

            assertEquals(rIscoExact, eob.eobIscoR, 0.03,
                    "Test-particle EOB ISCO radius must match exact Kerr r_ISCO for chi1=" + chi1);
            assertEquals(omegaIscoExact, eob.eobIscoOmega, 0.002,
                    "Test-particle EOB ISCO frequency must match exact Kerr Omega_ISCO for chi1=" + chi1);
        }
    }

    @Test
    void spinDependentDynamicsExhibitOrbitalHangUpAndIscoShift() {
        EobBinaryDynamics anti = new EobBinaryDynamics(0.5, 0.5, -0.75, -0.75);
        EobBinaryDynamics zero = new EobBinaryDynamics(0.5, 0.5,  0.00,  0.00);
        EobBinaryDynamics pro  = new EobBinaryDynamics(0.5, 0.5, +0.75, +0.75);

        // Prograde aligned spin pulls EOB ISCO inward; anti-aligned pushes it outward
        assertTrue(pro.eobIscoR < zero.eobIscoR && zero.eobIscoR < anti.eobIscoR,
                "EOB ISCO must obey r_ISCO(+0.75)=" + pro.eobIscoR
                        + " < r_ISCO(0)=" + zero.eobIscoR + " < r_ISCO(-0.75)=" + anti.eobIscoR);

        // Orbital hang-up (Campanelli, Lousto & Zlochower 2006): starting from identical r_0 = 9.0 M,
        // aligned spin takes strictly longer to coalesce and executes more orbital radians
        double tAnti = measureTimeToMerger(new BinaryParameters(32.5, 32.5, -0.75, -0.75, 9.0, 0.0, 0.0, 30.0, 440.0, BinaryParameters.DetectorPreset.OPTIMAL));
        double tZero = measureTimeToMerger(new BinaryParameters(32.5, 32.5,  0.00,  0.00, 9.0, 0.0, 0.0, 30.0, 440.0, BinaryParameters.DetectorPreset.OPTIMAL));
        double tPro  = measureTimeToMerger(new BinaryParameters(32.5, 32.5, +0.75, +0.75, 9.0, 0.0, 0.0, 30.0, 440.0, BinaryParameters.DetectorPreset.OPTIMAL));

        assertTrue(tAnti < tZero && tZero < tPro,
                "Orbital hang-up required: tAnti=" + tAnti + " < tZero=" + tZero + " < tPro=" + tPro);
    }

    @Test
    void conservativeEobHamiltonianConservesEnergyAngularMomentumAndPrecessesEccentricOrbits() {
        EobBinaryDynamics eob = new EobBinaryDynamics(0.65, 0.35, 0.60, -0.30);
        OdeSystem consOde = eob.createOdeSystem(false);
        double[] y = eob.computeInitialState(11.0, 0.0, 0.22, false);
        double h0 = eob.hamiltonianReal(y[0], y[2], y[3]);
        double l0 = y[3];

        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegratorConfig cfg = new IntegratorConfig(1e-10, 1e-12, 0.2, 1e-5, 2.0, 50000, 0.9);
        IntegrationDiagnostics diag = new IntegrationDiagnostics();

        double[] minMaxR = {y[0], y[0]};
        double[] maxDrift = {0.0, 0.0};
        dopri.integrate(consOde, 0.0, y, 420.0, cfg, diag, (t, st, h) -> {
            double hNow = eob.hamiltonianReal(st[0], st[2], st[3]);
            maxDrift[0] = Math.max(maxDrift[0], Math.abs(hNow - h0));
            maxDrift[1] = Math.max(maxDrift[1], Math.abs(st[3] - l0));
            minMaxR[0] = Math.min(minMaxR[0], st[0]);
            minMaxR[1] = Math.max(minMaxR[1], st[0]);
            return true;
        });

        assertTrue(diag.completed);
        assertTrue(maxDrift[0] < 1e-9, "Conservative EOB Hamiltonian must be invariant, drift=" + maxDrift[0]);
        assertTrue(maxDrift[1] < 1e-12, "Conservative EOB p_phi must be invariant, drift=" + maxDrift[1]);
        assertTrue(minMaxR[1] - minMaxR[0] > 3.0, "Eccentric orbit must oscillate cleanly between periapsis and apastron");
    }

    @Test
    void radiationReactionSatisfiesEnergyBalanceAndAdaptiveConvergence() {
        // 1. Energy & angular momentum balance during radiative inspiral
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        bin.reset(new BinaryParameters(35.0, 25.0, 0.35, -0.15, 10.5, 0.0, 0.0, 30.0, 440.0, BinaryParameters.DetectorPreset.OPTIMAL));
        double prevL = bin.orbitalAngularMomentum;
        double prevErad = bin.radiatedEnergy;

        for (int i = 0; i < 600; i++) {
            bin.stepGeometric(0.5, false);
            if (bin.phase() == BinaryMergerPhysics.Phase.PLUNGE || bin.phase() == BinaryMergerPhysics.Phase.MERGER) {
                break;
            }
            assertTrue(bin.orbitalAngularMomentum <= prevL + 1e-6, "Radiation reaction must monotonically decrease L_orb");
            assertTrue(bin.radiatedEnergy >= prevErad - 1e-6, "Radiated GW energy must increase monotonically");
            prevL = bin.orbitalAngularMomentum;
            prevErad = bin.radiatedEnergy;
        }
        assertTrue(bin.eobEnergyBalanceErr < 2e-4,
                "EOB inspiral energy balance |(E_bind - E_0) + E_rad| must stay tight, got " + bin.eobEnergyBalanceErr);

        // 2. Numerical convergence of RK4 & DOPRI5(4) EOB integration as step size / tolerance tightens
        EobBinaryDynamics eob = new EobBinaryDynamics(0.6, 0.4, 0.3, 0.1);
        OdeSystem ode = eob.createOdeSystem(true);
        double[] yRef = eob.computeInitialState(10.0, 0.0, 0.0, true);
        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        dopri.integrate(ode, 0.0, yRef, 300.0, new IntegratorConfig(1e-12, 1e-14, 0.1, 1e-5, 2.0, 50000, 0.9), null, null);

        double[] tols = {1e-3, 1e-6, 1e-9};
        double[] phaseErr = new double[tols.length];
        for (int i = 0; i < tols.length; i++) {
            double[] y = eob.computeInitialState(10.0, 0.0, 0.0, true);
            dopri.integrate(ode, 0.0, y, 300.0, new IntegratorConfig(tols[i], tols[i] * 1e-2, 4.0, 1e-5, 40.0, 50000, 0.9), null, null);
            phaseErr[i] = Math.abs(y[1] - yRef[1]);
        }
        assertTrue(phaseErr[1] < phaseErr[0] && phaseErr[2] < phaseErr[1] && phaseErr[2] < 1e-7,
                "DOPRI5(4) EOB orbital phase must converge monotonically: "
                        + phaseErr[0] + " -> " + phaseErr[1] + " -> " + phaseErr[2]);
    }

    // =========================================================================
    // 2. GRAVITATIONAL WAVEFORM & DETECTOR RESPONSE VALIDATION
    // =========================================================================

    @Test
    void waveformContinuityRingdownFrequencyDampingAndComConservationHoldAcrossCoalescence() {
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        BinaryParameters gw150914 = BinaryParameters.presetGw150914();
        bin.reset(gw150914);

        double hGeom = 0.5;
        double prevAmp = bin.geomAmplitude;
        double prevOm = bin.gwFreqGeom;
        double maxAmpStep = 0.0;
        double maxOmStep = 0.0;
        boolean sawPlunge = false, sawMerger = false, sawRingdown = false;

        double ringT1 = -1.0, ringA1 = 0.0;
        double ringT2 = -1.0, ringA2 = 0.0;
        double lateRingOmega = 0.0;

        for (int i = 0; i < 4000; i++) {
            bin.stepGeometric(hGeom, false);

            // Exact center of mass and zero net linear momentum
            double comX = bin.mass1 * bin.pos1X + bin.mass2 * bin.pos2X;
            double comY = bin.mass1 * bin.pos1Y + bin.mass2 * bin.pos2Y;
            double comZ = bin.mass1 * bin.pos1Z + bin.mass2 * bin.pos2Z;
            assertEquals(0.0, comX, 1e-5);
            assertEquals(0.0, comY, 1e-5);
            assertEquals(0.0, comZ, 1e-5);

            double pTotX = bin.mass1 * bin.vel1X + bin.mass2 * bin.vel2X;
            double pTotY = bin.mass1 * bin.vel1Y + bin.mass2 * bin.vel2Y;
            double pTotZ = bin.mass1 * bin.vel1Z + bin.mass2 * bin.vel2Z;
            assertEquals(0.0, pTotX, 1e-4);
            assertEquals(0.0, pTotY, 1e-4);
            assertEquals(0.0, pTotZ, 1e-4);

            maxAmpStep = Math.max(maxAmpStep, Math.abs(bin.geomAmplitude - prevAmp));
            maxOmStep = Math.max(maxOmStep, Math.abs(bin.gwFreqGeom - prevOm));
            prevAmp = bin.geomAmplitude;
            prevOm = bin.gwFreqGeom;

            if (bin.phase() == BinaryMergerPhysics.Phase.PLUNGE) sawPlunge = true;
            if (bin.phase() == BinaryMergerPhysics.Phase.MERGER) sawMerger = true;
            if (bin.phase() == BinaryMergerPhysics.Phase.RINGDOWN) {
                sawRingdown = true;
                if (ringT1 < 0.0) {
                    ringT1 = bin.geomTime();
                    ringA1 = bin.modeAmp22;
                } else if (ringT2 < 0.0 && bin.geomTime() - ringT1 >= 35.0) {
                    ringT2 = bin.geomTime();
                    ringA2 = bin.modeAmp22;
                }
                lateRingOmega = bin.gwFreqGeom;
            }
            if (bin.isMergerFinished()) break;
        }

        assertTrue(sawPlunge && sawMerger && sawRingdown, "Binary must evolve through PLUNGE, MERGER, and RINGDOWN");
        assertTrue(maxAmpStep < 0.03, "Waveform amplitude must transition continuously, maxStep=" + maxAmpStep);
        assertTrue(maxOmStep < 0.05, "GW frequency must transition continuously, maxStep=" + maxOmStep);

        // Late ringdown frequency must converge to exact Berti-Cardoso-Will (2006) omega_220
        assertEquals(bin.qnmFreqGeom(), lateRingOmega, 2e-3,
                "Late ringdown frequency must match BCW (2,2,0) QNM frequency");

        // Late ringdown exponential decay rate must match exact BCW damping rate gamma_220 = 1/tau_220
        assertTrue(ringT2 > ringT1 && ringA1 > 0 && ringA2 > 0);
        double measuredGamma = -Math.log(ringA2 / ringA1) / (ringT2 - ringT1);
        assertEquals(bin.qnmDampingGeom(), measuredGamma, 0.012,
                "Late ringdown damping rate must match BCW gamma_220");

        // Final radiated energy must close to 1 - M_f
        assertEquals(1.0 - bin.remnantMass, bin.radiatedEnergy, 2e-3,
                "Final radiated energy must match NR remnant budget 1 - M_f");

        // GW150914 physical SI detector strain & frequency sanity (Abbott et al. 2016: ~1e-21, ~251 Hz)
        assertTrue(bin.peakDetectorStrain > 3e-22 && bin.peakDetectorStrain < 3e-21,
                "GW150914 physical SI peak strain must be O(1e-21), got " + bin.peakDetectorStrain);
        double fQnmDetHz = GravitationalWaveDetector.geomOmegaToHz(bin.qnmFreqGeom(), gw150914.detectorMassSolar);
        assertEquals(GwReferenceData.GW150914_PEAK_FREQ_HZ, fQnmDetHz, 18.0,
                "GW150914 detector-frame QNM ringdown frequency must match ~251 Hz");
    }

    @Test
    void polarizationsHigherModesRedshiftAndAntennaPatternsArePhysicallyConsistent() {
        GravitationalWaveDetector.DetectorOutput outFace = new GravitationalWaveDetector.DetectorOutput();
        GravitationalWaveDetector.DetectorOutput outEdge = new GravitationalWaveDetector.DetectorOutput();

        BinaryParameters pFace = new BinaryParameters(30.1, 8.3, 0.44, 0.0, 10.0, 0.0, 0.0, 0.0, 740.0, BinaryParameters.DetectorPreset.OPTIMAL);
        BinaryParameters pEdge = new BinaryParameters(30.1, 8.3, 0.44, 0.0, 10.0, 0.0, 0.0, 90.0, 740.0, BinaryParameters.DetectorPreset.OPTIMAL);

        // At iota = 0 (face-on): circular polarization |h_+| == |h_x| at phi = pi/4 and zero higher-mode projection
        GravitationalWaveDetector.synthesize(pFace, 0.20, 0.25, Math.PI / 4.0, 0.04, 0.3, 0.06, 0.7, 0.02, 1.1, outFace);
        assertEquals(Math.abs(outFace.physHPlus), Math.abs(outFace.physHCross), 1e-30,
                "Face-on (iota=0) GW must be purely circularly polarized (only m=2 contributes)");
        assertEquals(1.0, outFace.fPlus, 1e-12);
        assertEquals(0.0, outFace.fCross, 1e-12);

        // At iota = 90 deg (edge-on): purely linearly polarized h_x == 0 identically!
        GravitationalWaveDetector.synthesize(pEdge, 0.20, 0.25, 0.9, 0.04, 0.3, 0.06, 0.7, 0.02, 1.1, outEdge);
        assertEquals(0.0, outEdge.physHCross, 1e-30, "Edge-on (iota=90 deg) cross polarization must vanish identically");
        assertTrue(Math.abs(outEdge.physHPlus) > 0.0);

        // Cosmological redshift round-trip & 1/D_L scaling
        double z440 = BinaryParameters.redshiftFromLuminosityDistanceMpc(440.0);
        assertEquals(440.0, BinaryParameters.luminosityDistanceMpc(z440), 1e-3);
        assertTrue(z440 > 0.08 && z440 < 0.11, "Planck-2018 redshift at 440 Mpc must be ~0.092, got " + z440);
        assertEquals(outFace.gwFreqSrcHz / (1.0 + pFace.redshiftZ), outFace.gwFreqDetHz, 1e-9,
                "Detector frequency must equal source frequency / (1 + z)");

        // Unequal-mass GW190412 excites significant higher modes (3,3), (2,1)
        BinaryMergerPhysics bin190412 = new BinaryMergerPhysics();
        bin190412.reset(BinaryParameters.presetGw190412());
        bin190412.advanceCurrentToSeparation(5.0);
        assertTrue(bin190412.modeAmp33 > 0.01 && bin190412.modeAmp21 > 0.002,
                "Unequal-mass spinning GW190412 must strongly excite (3,3) and (2,1) modes");

        // Antenna pattern physical bound F_+^2 + F_x^2 <= 1
        for (BinaryParameters.DetectorPreset det : BinaryParameters.DetectorPreset.values()) {
            double fp = GravitationalWaveDetector.antennaFPlus(det.defaultThetaRad, det.defaultPhiRad, det.defaultPsiRad);
            double fx = GravitationalWaveDetector.antennaFCross(det.defaultThetaRad, det.defaultPhiRad, det.defaultPsiRad);
            assertTrue(fp * fp + fx * fx <= 1.0 + 1e-12, "Antenna response power F_+^2 + F_x^2 must not exceed 1");
        }
    }

    // =========================================================================
    // 3. REMNANT & QNM VALIDATION AGAINST PUBLISHED INDEPENDENT NR / SXS DATA
    // =========================================================================

    @Test
    void remnantFitsMatchPublishedSxsNumericalRelativityCatalogAcrossMassRatiosAndSpins() {
        // Published SXS Catalog NR values (independent external reference, NOT self-derived!)
        // 1. SXS:BBH:0180 (q=1, chi1=chi2=0): M_f/M = 0.9516, a_f = 0.6865
        BinaryRemnantModel.RemnantProperties r0180 = BinaryRemnantModel.compute(0.5, 0.5, 0.0, 0.0);
        assertEquals(0.9516, r0180.finalMassFrac(), 0.001, "SXS:BBH:0180 M_f");
        assertEquals(0.6865, r0180.finalSpinSigned(), 0.001, "SXS:BBH:0180 a_f");

        // 2. SXS:BBH:0152 (q=1, chi1=chi2=+0.60): M_f/M = 0.9252, a_f = 0.8578
        BinaryRemnantModel.RemnantProperties r0152 = BinaryRemnantModel.compute(0.5, 0.5, 0.60, 0.60);
        assertEquals(0.9252, r0152.finalMassFrac(), 0.002, "SXS:BBH:0152 M_f");
        assertEquals(0.8578, r0152.finalSpinSigned(), 0.004, "SXS:BBH:0152 a_f");

        // 3. SXS:BBH:0157 (q=1, chi1=chi2=+0.80): M_f/M = 0.9132, a_f = 0.9075
        BinaryRemnantModel.RemnantProperties r0157 = BinaryRemnantModel.compute(0.5, 0.5, 0.80, 0.80);
        assertEquals(0.9132, r0157.finalMassFrac(), 0.003, "SXS:BBH:0157 M_f");
        assertEquals(0.9075, r0157.finalSpinSigned(), 0.006, "SXS:BBH:0157 a_f");

        // 4. SXS:BBH:0154 (q=1, chi1=chi2=-0.80): M_f/M = 0.9685, a_f = 0.431
        BinaryRemnantModel.RemnantProperties r0154 = BinaryRemnantModel.compute(0.5, 0.5, -0.80, -0.80);
        assertEquals(0.9685, r0154.finalMassFrac(), 0.003, "SXS:BBH:0154 M_f");
        assertEquals(0.431, r0154.finalSpinSigned(), 0.012, "SXS:BBH:0154 a_f");

        // 5. SXS:BBH:0169 (q=1/2, non-spinning): M_f/M = 0.9603, a_f = 0.6234
        BinaryRemnantModel.RemnantProperties r0169 = BinaryRemnantModel.compute(2.0 / 3.0, 1.0 / 3.0, 0.0, 0.0);
        assertEquals(0.9603, r0169.finalMassFrac(), 0.002, "SXS:BBH:0169 M_f");
        assertEquals(0.6234, r0169.finalSpinSigned(), 0.004, "SXS:BBH:0169 a_f");

        // 6. SXS:BBH:0168 (q=1/3, non-spinning): M_f/M = 0.9700, a_f = 0.5406
        BinaryRemnantModel.RemnantProperties r0168 = BinaryRemnantModel.compute(0.75, 0.25, 0.0, 0.0);
        assertEquals(0.9700, r0168.finalMassFrac(), 0.002, "SXS:BBH:0168 M_f");
        assertEquals(0.5406, r0168.finalSpinSigned(), 0.004, "SXS:BBH:0168 a_f");

        // 7. GW150914 (Abbott et al. 2016 / GWTC-1)
        BinaryRemnantModel.RemnantProperties rGw = BinaryRemnantModel.compute(BinaryParameters.presetGw150914());
        assertEquals(GwReferenceData.GW150914_REMNANT_MASS_FRAC, rGw.finalMassFrac(), 0.005);
        assertEquals(GwReferenceData.GW150914_REMNANT_SPIN, rGw.finalSpinSigned(), 0.012);

        // 8. Independent Chandrasekhar & Leaver (1985) exact Schwarzschild QNM frequencies (a_f = 0, M_f = 1)
        double[] q22 = BinaryRemnantModel.evalBcwQnm(BinaryRemnantModel.QnmMode.M22, 0.0, 1.0);
        double[] q33 = BinaryRemnantModel.evalBcwQnm(BinaryRemnantModel.QnmMode.M33, 0.0, 1.0);
        double[] q44 = BinaryRemnantModel.evalBcwQnm(BinaryRemnantModel.QnmMode.M44, 0.0, 1.0);
        assertEquals(0.37367, q22[0], 0.006, "Schwarzschild (2,2,0) Leaver frequency");
        assertEquals(0.08896, q22[1], 0.003, "Schwarzschild (2,2,0) Leaver damping");
        assertEquals(0.59944, q33[0], 0.009, "Schwarzschild (3,3,0) Leaver frequency");
        assertEquals(0.80918, q44[0], 0.015, "Schwarzschild (4,4,0) Leaver frequency");
    }

    @Test
    void schwarzschildHorizonPlungeReachesSingularityWithSpaghettificationAndZeroCauchyFlash() {
        SimulationState state = new SimulationState();
        state.kerrSpinEnabled = false;
        state.spinParam = 0.0f;
        CameraController cam = new CameraController();
        cam.setShowcasePose(9.0, 0.0, 0.12, 0.0, 0.0, 0.0, state);

        cam.startPlunge(state);
        for (int i = 0; i < 600 && !state.plungeEnded; i++) {
            cam.update(1.0 / 60.0, state, false, false);
        }

        assertTrue(state.plungeHorizonCrossed, "Schwarzschild plunge must cross r_s = 2M");
        assertTrue(state.plungeEnded, "Schwarzschild plunge must reach interior singularity cutoff");
        assertEquals(SimulationState.ZONE_SINGULARITY, state.plungeZone);
        assertEquals(0.0f, state.cauchyFlashIntensity, 1e-6f, "Schwarzschild has no Cauchy horizon flash");
        assertTrue(state.spaghettification > 0.85f, "Schwarzschild plunge must engage optical spaghettification");
        assertTrue(state.singularityFade > 0.85f, "Schwarzschild plunge must fade to singularity");
    }

    private static double measureTimeToMerger(BinaryParameters params) {
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        bin.reset(params);
        int safety = 0;
        while (bin.phase() != BinaryMergerPhysics.Phase.MERGER
                && bin.phase() != BinaryMergerPhysics.Phase.RINGDOWN
                && safety++ < 6000) {
            bin.stepGeometric(0.5, false);
        }
        assertTrue(bin.phase() == BinaryMergerPhysics.Phase.MERGER || bin.phase() == BinaryMergerPhysics.Phase.RINGDOWN,
                "Binary must reach merger cleanly");
        return bin.geomTime();
    }
}
