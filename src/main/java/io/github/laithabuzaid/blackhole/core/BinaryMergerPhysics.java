package io.github.laithabuzaid.blackhole.core;

import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.BinaryRemnantModel;
import io.github.laithabuzaid.blackhole.math.BinarySpacetimeMetric;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.EobBinaryDynamics;
import io.github.laithabuzaid.blackhole.math.GravitationalWaveDetector;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.OdeSystem;

import java.util.Arrays;

/**
 * Scientific-grade, deterministic, frame-rate-independent Effective-One-Body (EOB)
 * Binary Black Hole Merger, Multipolar Gravitational Waveform, and Detector-Response
 * engine in consistent geometric units ({@code G = c = M_tot = 1}).
 *
 * <h3>Physical Models &amp; Explicit Boundaries</h3>
 * <ol>
 *   <li><b>Conservative &amp; Dissipative Dynamics ({@link EobBinaryDynamics}):</b>
 *       3PN Padé-resummed radial potential {@code P^1_3[A_3PN]} + Kerr centrifugal spin-spin
 *       coupling + NLO DJS aligned spin-orbit coupling ({@code g_S, g_{S*}}), integrated in
 *       4-DOF tortoise phase space {@code (r, phi, p_{r*}, p_phi)} via adaptive
 *       {@link DormandPrince54Integrator} ({@code rtol = 1e-8, atol = 1e-10}), driven by
 *       Damour–Iyer–Nagar (2009) factorized multipolar radiation reaction across
 *       {@code (2,2), (2,1), (3,3), (4,4)}.</li>
 *   <li><b>Dynamical Inspiral–Plunge–Merger Transition:</b> Plunge begins dynamically at the
 *       EOB ISCO {@code p_phi < p_{phi,ISCO}(nu, chi_1, chi_2)}. Coalescence attaches dynamically
 *       at the peak of the EOB orbital frequency {@code Omega(t)} near the deformed light ring
 *       (zero hardcoded separation thresholds), with a {@code C^1}-continuous amplitude and
 *       frequency bridge to the Berti–Cardoso–Will (2006) Kerr QNM spectrum.</li>
 *   <li><b>NR-Calibrated Remnant ({@link BinaryRemnantModel}):</b> Barausse &amp; Rezzolla (2009)
 *       aligned-spin final spin {@code a_f}, Barausse–Morozova–Rezzolla (2012) final mass
 *       {@code M_f}, and Berti–Cardoso–Will (2006) Table VIII QNM modes.</li>
 *   <li><b>Detector Strain ({@link GravitationalWaveDetector}):</b> Separates intrinsic
 *       multipolar geometric strain {@code (R/M) h_+, h_x} and normalized visualization strain
 *       from true physical redshifted SI detector strain {@code h_det(t) = F_+ h_+ + F_x h_x}
 *       at luminosity distance {@code D_L}.</li>
 * </ol>
 */
public final class BinaryMergerPhysics {

    public enum Phase {
        INSPIRAL("INSPIRAL"),
        PLUNGE("PLUNGE"),
        MERGER("MERGER"),
        RINGDOWN("RINGDOWN");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final int STRAIN_HISTORY_LEN = 160;
    public static final int VIS_HISTORY_LEN = 240;
    public static final double INITIAL_SEPARATION = 10.0;

    /** Conversion between wall-clock seconds (at 1.0x timeScale) and geometric time t/M. */
    public static final double GEOM_TIME_PER_SEC = 55.0;
    private static final double MAX_SUBSTEP_GEOM = 0.85;
    private static final int MAX_SUBSTEPS_PER_FRAME = 240;
    private static final double STRAIN_SAMPLE_PERIOD_GEOM = 1.65;

    // Pre-allocated rolling waveform history buffers (zero per-frame heap allocations)
    private final float[] strainHistory = new float[STRAIN_HISTORY_LEN];       // Normalized visual h_det
    private final double[] physStrainHistory = new double[STRAIN_HISTORY_LEN]; // True SI h_det(t)
    private int strainCursor = 0;
    private double sampleAccumGeom = 0.0;

    // Synchronized scientific visualization history buffers (cleared on reset, never advances when paused)
    private final float[] trail1X = new float[VIS_HISTORY_LEN];
    private final float[] trail1Y = new float[VIS_HISTORY_LEN];
    private final float[] trail1Z = new float[VIS_HISTORY_LEN];
    private final float[] trail2X = new float[VIS_HISTORY_LEN];
    private final float[] trail2Y = new float[VIS_HISTORY_LEN];
    private final float[] trail2Z = new float[VIS_HISTORY_LEN];
    private final byte[] trailPhase = new byte[VIS_HISTORY_LEN];

    private final float[] histGeomTime = new float[VIS_HISTORY_LEN];
    private final float[] histMode22 = new float[VIS_HISTORY_LEN];
    private final float[] histMode21 = new float[VIS_HISTORY_LEN];
    private final float[] histMode33 = new float[VIS_HISTORY_LEN];
    private final float[] histMode44 = new float[VIS_HISTORY_LEN];
    private final float[] histAmp22 = new float[VIS_HISTORY_LEN];
    private final float[] histAmp21 = new float[VIS_HISTORY_LEN];
    private final float[] histAmp33 = new float[VIS_HISTORY_LEN];
    private final float[] histAmp44 = new float[VIS_HISTORY_LEN];
    private final float[] histGeomHPlus = new float[VIS_HISTORY_LEN];
    private final float[] histGeomHCross = new float[VIS_HISTORY_LEN];
    private final double[] histPhysHPlus = new double[VIS_HISTORY_LEN];
    private final double[] histPhysHCross = new double[VIS_HISTORY_LEN];
    private final double[] histPhysHDet = new double[VIS_HISTORY_LEN];
    private final float[] histGwFreqHz = new float[VIS_HISTORY_LEN];
    private final float[] histOmegaGeom = new float[VIS_HISTORY_LEN];
    private final float[] histSeparation = new float[VIS_HISTORY_LEN];
    private final float[] histOrbitalEnergy = new float[VIS_HISTORY_LEN];
    private final float[] histRadiatedEnergy = new float[VIS_HISTORY_LEN];
    private final float[] histOrbitalAngMom = new float[VIS_HISTORY_LEN];
    private final float[] histRadiatedAngMom = new float[VIS_HISTORY_LEN];
    private final float[] histEnergyBalanceErr = new float[VIS_HISTORY_LEN];
    private int visHistCursor = 0;
    private int visHistCount = 0;

    // Merger & eccentricity markers
    public boolean hasMergerOccurred = false;
    public double mergerGeomTime = 0.0;
    public float mergerLoc1X, mergerLoc1Y, mergerLoc1Z;
    public float mergerLoc2X, mergerLoc2Y, mergerLoc2Z;
    public float mergerComX, mergerComY, mergerComZ;
    public float minSepSeen = (float) INITIAL_SEPARATION;
    public float maxSepSeen = (float) INITIAL_SEPARATION;

    // Active physical configuration & models
    private BinaryParameters params = BinaryParameters.presetGw150914();
    private EobBinaryDynamics eob = EobBinaryDynamics.fromParameters(params);
    private BinaryRemnantModel.RemnantProperties remnant = BinaryRemnantModel.compute(params);
    private OdeSystem eobOde = eob.createOdeSystem(true);

    // Zero-allocation adaptive DOPRI5(4) integrator & scratch structures
    private final DormandPrince54Integrator dopri = new DormandPrince54Integrator();
    private final IntegratorConfig eobConfig = new IntegratorConfig(1e-8, 1e-10, 0.25, 1e-4, 2.0, 2000, 0.9);
    private final IntegrationDiagnostics eobDiag = new IntegrationDiagnostics();
    private final EobBinaryDynamics.EvalResult eobEval = new EobBinaryDynamics.EvalResult();
    private final GravitationalWaveDetector.DetectorOutput detOut = new GravitationalWaveDetector.DetectorOutput();

    // 4-DOF EOB state vector [r, phi, p_{r*}, p_phi]
    private final double[] eobState = new double[4];
    private double geomTime = 0.0;
    private double simTimeWall = 0.0;
    private double lastGeomDt = 0.0;

    // Dynamical Omega-peak attachment & C^1 merger-ringdown bridge state (modes: 0=22, 1=21, 2=33, 3=44)
    private double prevOmega = 0.0;
    private double peakOmegaSeen = 0.0;
    private double prevAmp22 = 0.0, prevAmp21 = 0.0, prevAmp33 = 0.0, prevAmp44 = 0.0;
    private double matchGeomTime = 0.0;
    private double matchSeparation = 3.0;
    private double matchRadialVel = 0.0;
    private double mergerDurationGeom = 30.0;
    private double ringdownDurationGeom = 120.0;
    private final double[] matchAmp = new double[4];
    private final double[] matchB = new double[4];
    private final double[] matchOmega = new double[4];
    private final double[] matchPhase = new double[4];
    private double radEnergyAtMatch = 0.0;
    private double angMomAtMatch = 0.0;

    // Energy & angular momentum conservation trackers
    private double initialBindingEnergy = 0.0;
    private double initialTotalAngMom = 0.0;
    private double radiatedEnergyAccum = 0.0;
    private double radiatedAngMomAccum = 0.0;
    private double maxIntegrationErr = 0.0;
    private boolean mergerFinished = false;
    private Phase phase = Phase.INSPIRAL;

    // Orbital plane orthonormal basis (e1, lHat, e3)
    public float orbE1X = 1f, orbE1Y = 0f, orbE1Z = 0f;
    public float orbLHX = 0f, orbLHY = 1f, orbLHZ = 0f;
    public float orbE3X = 0f, orbE3Y = 0f, orbE3Z = 1f;

    // Live zero-allocation physics, waveform & GPU uniform outputs
    public float pos1X, pos1Y, pos1Z;
    public float pos2X, pos2Y, pos2Z;
    public float vel1X, vel1Y, vel1Z;
    public float vel2X, vel2Y, vel2Z;
    public float mass1 = 0.538f;
    public float mass2 = 0.462f;
    public float massRatioQ = 0.860f;
    public float symMassRatio = 0.2485f;
    public float chi1 = 0.02f;
    public float chi2 = -0.04f;
    public float remnantMass = 0.953f;
    public float remnantSpin = 0.684f;          // Signed a_f along L_hat
    public float mergeBlend = 0.0f;
    public float ringdownEnv = 0.0f;
    public float mergerPulse = 0.0f;
    public float currentSeparation = (float) INITIAL_SEPARATION;
    public float currentOrbitalPhase = 0.35f;
    public float orbitalOmegaGeom = 0.031f;
    public float orbitalOmegaDot = 0.0f;
    public float radialVel = 0.0f;
    public float orbitalFreqHz = 16.0f;
    public float orbitalBeta = 0.25f;
    public float gwFreqHz = 32.0f;              // Detector-frame dominant (2,2) GW frequency (Hz)
    public float gwFreqSrcHz = 35.0f;           // Source-frame dominant (2,2) GW frequency (Hz)
    public float gwFreqGeom = 0.062f;           // Dimensionless M * omega_22
    public float strainPlus = 0.0f;             // Normalized visual h_+ for oscilloscope/shader [-1..1]
    public float strainCross = 0.0f;            // Normalized visual h_x for shader [-1..1]
    public float strainAmplitude = 0.0f;        // Normalized visual amplitude [0..1]
    public float binaryTime = 0.0f;
    public float lastStepGeomDt = 0.0f;
    public float gwPhaseOut = 0.70f;

    // Scientific double-precision physical outputs (strictly distinct from normalized visual values)
    public double geomHPlus = 0.0;              // Intrinsic (R/M) * h_+(iota_obs)
    public double geomHCross = 0.0;             // Intrinsic (R/M) * h_x(iota_obs)
    public double geomAmplitude = 0.0;          // Intrinsic |(R/M) h(iota_obs)|
    public double physHPlus = 0.0;              // True SI h_+(t) at D_L
    public double physHCross = 0.0;             // True SI h_x(t) at D_L
    public double detectorStrain = 0.0;         // True SI h_det(t) = F_+ h_+ + F_x h_x
    public double peakDetectorStrain = 0.0;     // Peak |h_det| observed so far
    public double antennaFPlus = 1.0;
    public double antennaFCross = 0.0;
    public double higherModePowerFrac = 0.0;    // Fraction of instantaneous GW power in (2,1),(3,3),(4,4)
    public double powerFrac22 = 1.0, powerFrac21 = 0.0, powerFrac33 = 0.0, powerFrac44 = 0.0;
    public double hPlus22 = 0.0, hPlus21 = 0.0, hPlus33 = 0.0, hPlus44 = 0.0;
    public double hCross22 = 0.0, hCross21 = 0.0, hCross33 = 0.0, hCross44 = 0.0;
    public double modeAmp22 = 0.0, modeAmp21 = 0.0, modeAmp33 = 0.0, modeAmp44 = 0.0;
    public double modePhase22 = 0.0, modePhase21 = 0.0, modePhase33 = 0.0, modePhase44 = 0.0;

    // Live conservation & solver diagnostics (geometric units M_tot = 1)
    public float orbitalEnergy = 0.0f;          // E_bind = H_EOB - 1
    public float radiatedEnergy = 0.0f;         // Cumulative radiated GW energy E_rad / M
    public float orbitalAngularMomentum = 0.0f; // L_orb = nu * p_phi (or J_f post-merger)
    public float radiatedAngularMomentum = 0.0f;// Cumulative J_rad / M^2
    public double eobEnergyBalanceErr = 0.0;    // |(E_bind(t) - E_bind(0)) + E_rad(t)| during inspiral
    public double eobIntegrationErr = 0.0;      // Max DOPRI5(4) scaled error norm
    public double eobIscoRadius = 5.72;
    public double eobHorizonRadius = 1.24;

    public BinaryMergerPhysics() {
        reset(BinaryParameters.presetGw150914());
    }

    /** Resets the binary cleanly using a full validated {@link BinaryParameters} set. */
    public void reset(BinaryParameters newParams) {
        this.params = (newParams != null) ? newParams : BinaryParameters.presetGw150914();
        this.eob = EobBinaryDynamics.fromParameters(this.params);
        this.remnant = BinaryRemnantModel.compute(this.params);
        this.eobOde = this.eob.createOdeSystem(true);

        updateOrbitalBasis(this.params.orbitalIncDeg);
        syncStaticParams();

        this.geomTime = 0.0;
        this.simTimeWall = 0.0;
        this.lastGeomDt = 0.0;
        this.lastStepGeomDt = 0.0f;
        this.mergerFinished = false;
        this.phase = Phase.INSPIRAL;
        this.mergeBlend = 0.0f;
        this.ringdownEnv = 0.0f;
        this.mergerPulse = 0.0f;
        this.maxIntegrationErr = 0.0;
        this.peakDetectorStrain = 0.0;

        double[] init = eob.computeInitialState(this.params.initialSepM, 0.35, this.params.eccentricity, true);
        System.arraycopy(init, 0, eobState, 0, 4);

        eob.evaluate(eobState[0], eobState[2], eobState[3], true, eobEval);
        this.initialBindingEnergy = eobEval.bindingEnergy;
        this.initialTotalAngMom = eob.nu * eobState[3]
                + eob.m1Frac * eob.m1Frac * eob.chi1
                + eob.m2Frac * eob.m2Frac * eob.chi2;
        this.radiatedEnergyAccum = 0.0;
        this.radiatedAngMomAccum = 0.0;
        this.eobEnergyBalanceErr = 0.0;

        this.prevOmega = eobEval.omega;
        this.peakOmegaSeen = eobEval.omega;
        this.prevAmp22 = eobEval.amp22;
        this.prevAmp21 = eobEval.amp21;
        this.prevAmp33 = eobEval.amp33;
        this.prevAmp44 = eobEval.amp44;

        Arrays.fill(strainHistory, 0.0f);
        Arrays.fill(physStrainHistory, 0.0);
        this.strainCursor = 0;
        this.sampleAccumGeom = 0.0;

        clearVisHistories();

        publishInspiralOutputs();
        this.orbitalOmegaDot = 0.0f;
        this.minSepSeen = this.currentSeparation;
        this.maxSepSeen = this.currentSeparation;
        recordVisSample();
    }

    private void clearVisHistories() {
        Arrays.fill(trail1X, 0.0f); Arrays.fill(trail1Y, 0.0f); Arrays.fill(trail1Z, 0.0f);
        Arrays.fill(trail2X, 0.0f); Arrays.fill(trail2Y, 0.0f); Arrays.fill(trail2Z, 0.0f);
        Arrays.fill(trailPhase, (byte) 0);
        Arrays.fill(histGeomTime, 0.0f);
        Arrays.fill(histMode22, 0.0f); Arrays.fill(histMode21, 0.0f);
        Arrays.fill(histMode33, 0.0f); Arrays.fill(histMode44, 0.0f);
        Arrays.fill(histAmp22, 0.0f); Arrays.fill(histAmp21, 0.0f);
        Arrays.fill(histAmp33, 0.0f); Arrays.fill(histAmp44, 0.0f);
        Arrays.fill(histGeomHPlus, 0.0f); Arrays.fill(histGeomHCross, 0.0f);
        Arrays.fill(histPhysHPlus, 0.0); Arrays.fill(histPhysHCross, 0.0); Arrays.fill(histPhysHDet, 0.0);
        Arrays.fill(histGwFreqHz, 0.0f); Arrays.fill(histOmegaGeom, 0.0f); Arrays.fill(histSeparation, 0.0f);
        Arrays.fill(histOrbitalEnergy, 0.0f); Arrays.fill(histRadiatedEnergy, 0.0f);
        Arrays.fill(histOrbitalAngMom, 0.0f); Arrays.fill(histRadiatedAngMom, 0.0f);
        Arrays.fill(histEnergyBalanceErr, 0.0f);
        visHistCursor = 0;
        visHistCount = 0;
        hasMergerOccurred = false;
        mergerGeomTime = 0.0;
        mergerLoc1X = mergerLoc1Y = mergerLoc1Z = 0.0f;
        mergerLoc2X = mergerLoc2Y = mergerLoc2Z = 0.0f;
        mergerComX = mergerComY = mergerComZ = 0.0f;
    }

    private void recordVisSample() {
        int i = visHistCursor;
        trail1X[i] = pos1X; trail1Y[i] = pos1Y; trail1Z[i] = pos1Z;
        trail2X[i] = pos2X; trail2Y[i] = pos2Y; trail2Z[i] = pos2Z;
        trailPhase[i] = (byte) phase.ordinal();

        histGeomTime[i] = (float) geomTime;
        histMode22[i] = (float) (modeAmp22 * Math.cos(modePhase22));
        histMode21[i] = (float) (modeAmp21 * Math.cos(modePhase21));
        histMode33[i] = (float) (modeAmp33 * Math.cos(modePhase33));
        histMode44[i] = (float) (modeAmp44 * Math.cos(modePhase44));
        histAmp22[i] = (float) modeAmp22;
        histAmp21[i] = (float) modeAmp21;
        histAmp33[i] = (float) modeAmp33;
        histAmp44[i] = (float) modeAmp44;
        histGeomHPlus[i] = (float) geomHPlus;
        histGeomHCross[i] = (float) geomHCross;
        histPhysHPlus[i] = physHPlus;
        histPhysHCross[i] = physHCross;
        histPhysHDet[i] = detectorStrain;
        histGwFreqHz[i] = gwFreqHz;
        histOmegaGeom[i] = orbitalOmegaGeom;
        histSeparation[i] = currentSeparation;
        histOrbitalEnergy[i] = orbitalEnergy;
        histRadiatedEnergy[i] = radiatedEnergy;
        histOrbitalAngMom[i] = orbitalAngularMomentum;
        histRadiatedAngMom[i] = radiatedAngularMomentum;
        histEnergyBalanceErr[i] = (float) eobEnergyBalanceErr;

        visHistCursor = (visHistCursor + 1) % VIS_HISTORY_LEN;
        if (visHistCount < VIS_HISTORY_LEN) {
            visHistCount++;
        }
    }

    /** Backward-compatible reset from mass ratio {@code q} (non-spinning, 65 M_sun, r_0 = 10M). */
    public void reset(float massRatioQ) {
        reset(BinaryParameters.fromMassRatio(massRatioQ, 65.0, 0.0, 0.0));
    }

    /** Backward-compatible step using mass ratio {@code q}. */
    public void step(double dtSec, float massRatioQ, float timeScale, boolean autoLoop) {
        if (Math.abs(massRatioQ - params.massRatioQ) > 1e-4) {
            BinaryParameters updated = new BinaryParameters(
                    params.totalMassSolar / (1.0 + massRatioQ),
                    params.totalMassSolar * massRatioQ / (1.0 + massRatioQ),
                    params.chi1, params.chi2, params.initialSepM, params.eccentricity,
                    params.orbitalIncDeg, params.observerIncDeg, params.luminosityDistMpc,
                    params.detectorPreset, params.skyThetaRad, params.skyPhiRad, params.polarizationPsiRad
            );
            reset(updated);
        }
        step(dtSec, timeScale, autoLoop);
    }

    /**
     * Deterministically advances the EOB binary with adaptive DOPRI5(4) integration in
     * geometric time {@code t/M}, independent of wall-clock frame rate.
     */
    public void step(double dtSec, float timeScale, boolean autoLoop) {
        if (dtSec <= 0.0) {
            this.lastGeomDt = 0.0;
            this.lastStepGeomDt = 0.0f;
            return;
        }
        double clampedWallDt = Math.min(0.05, dtSec);
        double clampedScale = Math.max(SimulationState.MIN_TIME_SCALE, Math.min(SimulationState.MAX_TIME_SCALE, timeScale));
        double scaledWallDt = clampedWallDt * clampedScale;
        double totalGeomDt = scaledWallDt * GEOM_TIME_PER_SEC;
        this.lastGeomDt = totalGeomDt;
        this.lastStepGeomDt = (float) totalGeomDt;

        int substeps = Math.max(1, Math.min(MAX_SUBSTEPS_PER_FRAME, (int) Math.ceil(totalGeomDt / MAX_SUBSTEP_GEOM)));
        double hGeom = totalGeomDt / substeps;
        double hWall = scaledWallDt / substeps;

        for (int s = 0; s < substeps; s++) {
            stepGeomChunk(hGeom, hWall, autoLoop);
            sampleAccumGeom += hGeom;
            while (sampleAccumGeom >= STRAIN_SAMPLE_PERIOD_GEOM) {
                sampleAccumGeom -= STRAIN_SAMPLE_PERIOD_GEOM;
                strainHistory[strainCursor] = strainPlus;
                physStrainHistory[strainCursor] = detectorStrain;
                strainCursor = (strainCursor + 1) % STRAIN_HISTORY_LEN;
                recordVisSample();
            }
        }
    }

    /** Advances directly in geometric time {@code deltaTGeom} (in units of {@code M_tot}) for scientific tests. */
    public void stepGeometric(double deltaTGeom, boolean autoLoop) {
        if (deltaTGeom <= 0.0) {
            return;
        }
        int substeps = Math.max(1, (int) Math.ceil(deltaTGeom / MAX_SUBSTEP_GEOM));
        double hGeom = deltaTGeom / substeps;
        double hWall = hGeom / GEOM_TIME_PER_SEC;
        this.lastGeomDt = deltaTGeom;
        this.lastStepGeomDt = (float) deltaTGeom;
        for (int s = 0; s < substeps; s++) {
            stepGeomChunk(hGeom, hWall, autoLoop);
            sampleAccumGeom += hGeom;
            while (sampleAccumGeom >= STRAIN_SAMPLE_PERIOD_GEOM) {
                sampleAccumGeom -= STRAIN_SAMPLE_PERIOD_GEOM;
                strainHistory[strainCursor] = strainPlus;
                physStrainHistory[strainCursor] = detectorStrain;
                strainCursor = (strainCursor + 1) % STRAIN_HISTORY_LEN;
                recordVisSample();
            }
        }
    }

    /** Deterministically marches from initial separation down to {@code targetSep}. */
    public void advanceToSeparation(float massRatioQ, double targetSep) {
        reset(massRatioQ);
        advanceCurrentToSeparation(targetSep);
    }

    /** Deterministically marches the currently configured parameters down to {@code targetSep}. */
    public void advanceCurrentToSeparation(double targetSep) {
        double clampedTarget = Math.max(eob.eobHorizonR * 1.3, Math.min(params.initialSepM, targetSep));
        int safety = 0;
        while (currentSeparation > clampedTarget && phase != Phase.MERGER && phase != Phase.RINGDOWN && safety++ < 25000) {
            stepGeometric(0.5, false);
        }
    }

    private void stepGeomChunk(double hGeom, double hWall, boolean autoLoop) {
        simTimeWall += hWall;
        if (phase == Phase.INSPIRAL || phase == Phase.PLUNGE) {
            double rBefore = eobState[0];
            double omegaBefore = eobEval.omega;
            double fluxBefore = eobEval.gwFlux;
            double fPhiBefore = eobEval.fPhi;
            double a22Before = eobEval.amp22;
            double a21Before = eobEval.amp21;
            double a33Before = eobEval.amp33;
            double a44Before = eobEval.amp44;

            // Adaptive DOPRI5(4) evolution of 4-DOF EOB Hamiltonian + radiation reaction
            double tEnd = geomTime + hGeom;
            double tReached = dopri.integrate(eobOde, geomTime, eobState, tEnd, eobConfig, eobDiag,
                    (tNow, yNow, stepH) -> yNow[0] > eob.eobHorizonR * 1.04);
            double actualDt = Math.max(1e-9, tReached - geomTime);
            geomTime = tReached;
            maxIntegrationErr = Math.max(maxIntegrationErr, eobDiag.maxErrorRatio);
            eobIntegrationErr = maxIntegrationErr;

            eob.evaluate(eobState[0], eobState[2], eobState[3], true, eobEval);

            // Trapezoidal quadrature of radiated energy & angular momentum from exact multipolar flux
            radiatedEnergyAccum += 0.5 * (fluxBefore + eobEval.gwFlux) * actualDt;
            radiatedAngMomAccum += -0.5 * eob.nu * (fPhiBefore + eobEval.fPhi) * actualDt;
            eobEnergyBalanceErr = Math.abs((eobEval.bindingEnergy - initialBindingEnergy) + radiatedEnergyAccum);

            // Dynamic inspiral -> plunge transition at EOB ISCO (no arbitrary separation constant)
            if (eobState[3] < eob.eobIscoPphi || eobState[0] <= eob.eobIscoR) {
                phase = Phase.PLUNGE;
            }
            if (eobEval.omega > peakOmegaSeen) {
                peakOmegaSeen = eobEval.omega;
            }

            // Dynamical merger attachment at peak of EOB orbital frequency Omega(t) near deformed light ring
            // (or safety bound if extremal spin approaches horizon before turnover)
            boolean pastIsco = (phase == Phase.PLUNGE) && (eobState[0] < Math.max(eob.eobIscoR * 0.96, eob.eobHorizonR * 1.65));
            boolean omegaTurnedOver = pastIsco
                    && (peakOmegaSeen > eob.eobIscoOmega * 1.03)
                    && (eobEval.omega < omegaBefore);
            boolean reachedLightRingFloor = (eobState[0] <= Math.max(eob.eobHorizonR * 1.16, 1.55))
                    || (2.0 * eobEval.omega >= remnant.omega220() * 0.96);

            if (omegaTurnedOver || reachedLightRingFloor) {
                beginMergerAttachment(actualDt, a22Before, a21Before, a33Before, a44Before);
                publishMergerRingdownOutputs(0.0);
                orbitalOmegaDot = (float) ((orbitalOmegaGeom - omegaBefore) / Math.max(1e-5, actualDt));
                recordVisSample();
            } else {
                orbitalOmegaDot = (float) ((eobEval.omega - omegaBefore) / Math.max(1e-5, actualDt));
                prevOmega = eobEval.omega;
                prevAmp22 = a22Before;
                prevAmp21 = a21Before;
                prevAmp33 = a33Before;
                prevAmp44 = a44Before;
                publishInspiralOutputs();
            }
        } else {
            double omegaPrev = orbitalOmegaGeom;
            geomTime += hGeom;
            double dtSinceMatch = geomTime - matchGeomTime;
            if (phase == Phase.MERGER && dtSinceMatch >= mergerDurationGeom) {
                phase = Phase.RINGDOWN;
            }
            publishMergerRingdownOutputs(dtSinceMatch);
            orbitalOmegaDot = (float) ((orbitalOmegaGeom - omegaPrev) / Math.max(1e-5, hGeom));

            if (dtSinceMatch >= mergerDurationGeom + ringdownDurationGeom) {
                if (autoLoop) {
                    reset(params);
                } else {
                    mergerFinished = true;
                }
            }
        }
    }

    /**
     * Attaches C^1-continuous merger-ringdown bridge directly to the EOB plunge state at
     * {@code t_match} (zero discontinuities in amplitude, phase, or frequency).
     */
    private void beginMergerAttachment(double lastDt, double a22Prev, double a21Prev, double a33Prev, double a44Prev) {
        matchGeomTime = geomTime;
        matchSeparation = eobState[0];
        matchRadialVel = eobEval.drDt;
        radEnergyAtMatch = radiatedEnergyAccum;
        angMomAtMatch = eob.nu * eobState[3];

        hasMergerOccurred = true;
        mergerGeomTime = geomTime;
        mergerLoc1X = pos1X; mergerLoc1Y = pos1Y; mergerLoc1Z = pos1Z;
        mergerLoc2X = pos2X; mergerLoc2Y = pos2Y; mergerLoc2Z = pos2Z;
        mergerComX = 0.0f; mergerComY = 0.0f; mergerComZ = 0.0f;

        // Physical coalescence & ringdown durations scaled strictly to remnant QNM damping time tau_220
        mergerDurationGeom = Math.max(14.0, 2.2 * remnant.tau220());
        ringdownDurationGeom = Math.max(60.0, 10.0 * remnant.tau220());

        double invDt = 1.0 / Math.max(1e-4, lastDt);
        double[] amps = {eobEval.amp22, eobEval.amp21, eobEval.amp33, eobEval.amp44};
        double[] ampDots = {
                (eobEval.amp22 - a22Prev) * invDt,
                (eobEval.amp21 - a21Prev) * invDt,
                (eobEval.amp33 - a33Prev) * invDt,
                (eobEval.amp44 - a44Prev) * invDt
        };
        double[] phaseOffs = {eobEval.phaseOff22, eobEval.phaseOff21, eobEval.phaseOff33, eobEval.phaseOff44};
        int[] ms = {2, 1, 3, 4};
        BinaryRemnantModel.QnmMode[] modes = {
                BinaryRemnantModel.QnmMode.M22,
                BinaryRemnantModel.QnmMode.M21,
                BinaryRemnantModel.QnmMode.M33,
                BinaryRemnantModel.QnmMode.M44
        };

        for (int k = 0; k < 4; k++) {
            double gamma = remnant.gammaFor(modes[k]);
            double omegaQnm = remnant.omegaFor(modes[k]);
            double aMatch = Math.max(0.0, amps[k]);
            double aDot = Math.max(-0.5 * gamma * aMatch, Math.min(2.0 * gamma * aMatch, ampDots[k]));
            matchAmp[k] = aMatch;
            // Exact C^1 slope matching: dA/dt(0) = gamma * (B - A_match) == aDot
            matchB[k] = Math.max(0.0, Math.min(1.85 * aMatch, aMatch + aDot / Math.max(1e-5, gamma)));
            matchOmega[k] = Math.min(omegaQnm * 0.995, ms[k] * Math.max(1e-4, eobEval.omega));
            matchPhase[k] = ms[k] * eobState[1] - phaseOffs[k];
        }

        phase = Phase.MERGER;
    }

    private void publishInspiralOutputs() {
        double r = eobState[0];
        double phi = eobState[1];
        double drDt = eobEval.drDt;
        double omega = eobEval.omega;

        double c = Math.cos(phi);
        double s = Math.sin(phi);
        // Relative position & velocity in inclined 3D orbital plane
        double rx = r * (c * orbE1X + s * orbE3X);
        double ry = r * (c * orbE1Y + s * orbE3Y);
        double rz = r * (c * orbE1Z + s * orbE3Z);

        double vr = drDt;
        double vt = r * omega;
        double vx = (vr * c - vt * s) * orbE1X + (vr * s + vt * c) * orbE3X;
        double vy = (vr * c - vt * s) * orbE1Y + (vr * s + vt * c) * orbE3Y;
        double vz = (vr * c - vt * s) * orbE1Z + (vr * s + vt * c) * orbE3Z;

        assignBodyKinematics(rx, ry, rz, vx, vy, vz, 1.0);

        mergeBlend = 0.0f;
        ringdownEnv = 0.0f;
        mergerPulse = 0.0f;
        currentSeparation = (float) r;
        if (currentSeparation < minSepSeen) minSepSeen = currentSeparation;
        if (currentSeparation > maxSepSeen) maxSepSeen = currentSeparation;
        radialVel = (float) drDt;
        currentOrbitalPhase = (float) phi;
        orbitalOmegaGeom = (float) omega;
        orbitalFreqHz = (float) GravitationalWaveDetector.geomOmegaToHz(omega, params.detectorMassSolar);
        binaryTime = (float) simTimeWall;

        modeAmp22 = eobEval.amp22;
        modeAmp21 = eobEval.amp21;
        modeAmp33 = eobEval.amp33;
        modeAmp44 = eobEval.amp44;
        modePhase22 = 2.0 * phi - eobEval.phaseOff22;
        modePhase21 = 1.0 * phi - eobEval.phaseOff21;
        modePhase33 = 3.0 * phi - eobEval.phaseOff33;
        modePhase44 = 4.0 * phi - eobEval.phaseOff44;

        publishWaveformOutputs(2.0 * omega);

        orbitalEnergy = (float) eobEval.bindingEnergy;
        radiatedEnergy = (float) radiatedEnergyAccum;
        orbitalAngularMomentum = (float) (eob.nu * eobState[3]);
        radiatedAngularMomentum = (float) radiatedAngMomAccum;
    }

    private void publishMergerRingdownOutputs(double dt) {
        double u = Math.max(0.0, Math.min(1.0, dt / Math.max(1e-4, mergerDurationGeom)));
        // C^2 quintic smoothstep for horizon coalescence blend
        double sBlend = u * u * u * (u * (u * 6.0 - 15.0) + 10.0);
        double dsDu = 30.0 * u * u * (1.0 - u) * (1.0 - u);

        // Evaluate all 4 C^1-matched merger-ringdown QNM modes
        BinaryRemnantModel.QnmMode[] modes = {
                BinaryRemnantModel.QnmMode.M22,
                BinaryRemnantModel.QnmMode.M21,
                BinaryRemnantModel.QnmMode.M33,
                BinaryRemnantModel.QnmMode.M44
        };
        double[] ampNow = new double[4];
        double[] phiNow = new double[4];
        double[] omNow = new double[4];
        for (int k = 0; k < 4; k++) {
            double omQnm = remnant.omegaFor(modes[k]);
            double gamma = remnant.gammaFor(modes[k]);
            double tauOm = 0.72 / Math.max(1e-5, gamma);
            double expOm = Math.exp(-dt / tauOm);
            double dOm = omQnm - matchOmega[k];
            omNow[k] = omQnm - dOm * expOm;
            phiNow[k] = matchPhase[k] + omQnm * dt - dOm * tauOm * (1.0 - expOm);

            double expG = Math.exp(-gamma * dt);
            ampNow[k] = expG * (matchAmp[k] + matchB[k] * (1.0 - expG));
        }

        double omegaOrbNow = 0.5 * omNow[0];
        double phiOrbNow = 0.5 * phiNow[0];
        double rEff = matchSeparation * (1.0 - sBlend);
        double drEff = -matchSeparation * (dsDu / Math.max(1e-4, mergerDurationGeom))
                + matchRadialVel * (1.0 - sBlend) * (1.0 - sBlend);

        double c = Math.cos(phiOrbNow);
        double s = Math.sin(phiOrbNow);
        double rx = rEff * (c * orbE1X + s * orbE3X);
        double ry = rEff * (c * orbE1Y + s * orbE3Y);
        double rz = rEff * (c * orbE1Z + s * orbE3Z);
        double vt = rEff * omegaOrbNow;
        double vx = (drEff * c - vt * s) * orbE1X + (drEff * s + vt * c) * orbE3X;
        double vy = (drEff * c - vt * s) * orbE1Y + (drEff * s + vt * c) * orbE3Y;
        double vz = (drEff * c - vt * s) * orbE1Z + (drEff * s + vt * c) * orbE3Z;

        assignBodyKinematics(rx, ry, rz, vx, vy, vz, 1.0 - sBlend);

        mergeBlend = (float) sBlend;
        // Smooth physical QNM envelope: rises cleanly across merger, decays at exact gamma_220 in ringdown
        double qnmDecay = Math.exp(-remnant.gamma220() * Math.max(0.0, dt - mergerDurationGeom));
        ringdownEnv = (float) (sBlend * qnmDecay);
        mergerPulse = (float) (sBlend * Math.exp(-remnant.gamma220() * 1.4 * Math.max(0.0, dt - 0.5 * mergerDurationGeom)));

        currentSeparation = (float) rEff;
        radialVel = (float) drEff;
        currentOrbitalPhase = (float) phiOrbNow;
        orbitalOmegaGeom = (float) omegaOrbNow;
        orbitalFreqHz = (float) GravitationalWaveDetector.geomOmegaToHz(omegaOrbNow, params.detectorMassSolar);
        binaryTime = (float) simTimeWall;

        modeAmp22 = ampNow[0];
        modeAmp21 = ampNow[1];
        modeAmp33 = ampNow[2];
        modeAmp44 = ampNow[3];
        modePhase22 = phiNow[0];
        modePhase21 = phiNow[1];
        modePhase33 = phiNow[2];
        modePhase44 = phiNow[3];

        publishWaveformOutputs(omNow[0]);

        // Smooth total radiated energy & angular momentum closure onto exact NR remnant budget
        double totalRadTarget = remnant.radiatedEnergyFrac();
        double closure = 1.0 - Math.exp(-2.0 * remnant.gamma220() * dt);
        double radNow = radEnergyAtMatch + (totalRadTarget - radEnergyAtMatch) * closure;
        radiatedEnergy = (float) Math.max(radEnergyAtMatch, Math.min(totalRadTarget, radNow));
        orbitalEnergy = (float) (initialBindingEnergy - radiatedEnergy);

        double jFinal = remnant.finalAngMom();
        double lNow = angMomAtMatch * (1.0 - closure) + jFinal * closure;
        orbitalAngularMomentum = (float) lNow;
        radiatedAngularMomentum = (float) Math.max(0.0, initialTotalAngMom - (jFinal + (1.0 - closure) * (angMomAtMatch - jFinal)));
    }

    private void publishWaveformOutputs(double omega22Geom) {
        GravitationalWaveDetector.synthesize(
                params, omega22Geom,
                modeAmp22, modePhase22,
                modeAmp21, modePhase21,
                modeAmp33, modePhase33,
                modeAmp44, modePhase44,
                detOut
        );

        geomHPlus = detOut.geomHPlus;
        geomHCross = detOut.geomHCross;
        geomAmplitude = detOut.geomAmplitude;
        physHPlus = detOut.physHPlus;
        physHCross = detOut.physHCross;
        detectorStrain = detOut.detectorStrain;
        peakDetectorStrain = Math.max(peakDetectorStrain, Math.abs(detectorStrain));
        antennaFPlus = detOut.fPlus;
        antennaFCross = detOut.fCross;
        higherModePowerFrac = detOut.higherModePowerFrac;
        powerFrac22 = detOut.powerFrac22;
        powerFrac21 = detOut.powerFrac21;
        powerFrac33 = detOut.powerFrac33;
        powerFrac44 = detOut.powerFrac44;
        hPlus22 = detOut.hPlus22; hPlus21 = detOut.hPlus21; hPlus33 = detOut.hPlus33; hPlus44 = detOut.hPlus44;
        hCross22 = detOut.hCross22; hCross21 = detOut.hCross21; hCross33 = detOut.hCross33; hCross44 = detOut.hCross44;

        gwFreqGeom = (float) omega22Geom;
        gwFreqHz = (float) detOut.gwFreqDetHz;
        gwFreqSrcHz = (float) detOut.gwFreqSrcHz;
        gwPhaseOut = (float) modePhase22;

        // Normalized visualization strain (strictly labeled as normalized for HUD oscilloscope & GPU ripples)
        // Reference scale: face-on equal-mass NR merger peak |R h / M| ~ 0.394 * 2 * sqrt(5/(64*pi)) ~ 0.25
        double visNorm = Math.max(0.05, 0.26 * (4.0 * eob.nu));
        strainPlus = (float) Math.max(-1.0, Math.min(1.0, geomHPlus / visNorm));
        strainCross = (float) Math.max(-1.0, Math.min(1.0, geomHCross / visNorm));
        strainAmplitude = (float) Math.max(0.0, Math.min(1.0, geomAmplitude / visNorm));
    }

    /** Assigns exact center-of-mass kinematics with symmetric relativistic speed ceiling (preserves P_tot == 0). */
    private void assignBodyKinematics(double rx, double ry, double rz,
                                      double vx, double vy, double vz, double velDamp) {
        double x1 = eob.m1Frac;
        double x2 = eob.m2Frac;
        pos1X = (float) (x2 * rx);
        pos1Y = (float) (x2 * ry);
        pos1Z = (float) (x2 * rz);
        pos2X = (float) (-x1 * rx);
        pos2Y = (float) (-x1 * ry);
        pos2Z = (float) (-x1 * rz);

        double v1x = x2 * vx * velDamp, v1y = x2 * vy * velDamp, v1z = x2 * vz * velDamp;
        double v2x = -x1 * vx * velDamp, v2y = -x1 * vy * velDamp, v2z = -x1 * vz * velDamp;

        double maxSpeed = Math.max(Math.sqrt(v1x * v1x + v1y * v1y + v1z * v1z),
                Math.sqrt(v2x * v2x + v2y * v2y + v2z * v2z));
        // Symmetric scaling keeps m1*v1 + m2*v2 == 0 identically while keeping boosted KS gamma well-conditioned
        if (maxSpeed > 0.65) {
            double sc = 0.65 / maxSpeed;
            v1x *= sc; v1y *= sc; v1z *= sc;
            v2x *= sc; v2y *= sc; v2z *= sc;
            maxSpeed = 0.65;
        }
        vel1X = (float) v1x; vel1Y = (float) v1y; vel1Z = (float) v1z;
        vel2X = (float) v2x; vel2Y = (float) v2y; vel2Z = (float) v2z;
        orbitalBeta = (float) maxSpeed;
    }

    private void updateOrbitalBasis(double incDeg) {
        double rad = Math.toRadians(incDeg);
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        orbE1X = 1f; orbE1Y = 0f; orbE1Z = 0f;
        orbLHX = 0f; orbLHY = (float) c; orbLHZ = (float) s;
        orbE3X = 0f; orbE3Y = (float) -s; orbE3Z = (float) c;
    }

    private void syncStaticParams() {
        this.mass1 = (float) eob.m1Frac;
        this.mass2 = (float) eob.m2Frac;
        this.massRatioQ = (float) params.massRatioQ;
        this.symMassRatio = (float) eob.nu;
        this.chi1 = (float) eob.chi1;
        this.chi2 = (float) eob.chi2;
        this.remnantMass = (float) remnant.finalMassFrac();
        this.remnantSpin = (float) remnant.finalSpinSigned();
        this.eobIscoRadius = eob.eobIscoR;
        this.eobHorizonRadius = eob.eobHorizonR;
    }

    /** Populates a {@link BinarySpacetimeMetric.SpacetimeSnapshot} matching GPU shader uniforms identically. */
    public void copySpacetimeSnapshot(BinarySpacetimeMetric.SpacetimeSnapshot dst) {
        dst.pos1X = pos1X; dst.pos1Y = pos1Y; dst.pos1Z = pos1Z;
        dst.pos2X = pos2X; dst.pos2Y = pos2Y; dst.pos2Z = pos2Z;
        dst.vel1X = vel1X; dst.vel1Y = vel1Y; dst.vel1Z = vel1Z;
        dst.vel2X = vel2X; dst.vel2Y = vel2Y; dst.vel2Z = vel2Z;
        dst.mass1 = mass1; dst.mass2 = mass2;
        dst.chi1 = chi1; dst.chi2 = chi2;
        dst.remnantMass = remnantMass;
        dst.remnantSpin = remnantSpin;
        dst.mergeBlend = mergeBlend;
        dst.ringdownEnv = ringdownEnv;
        dst.gwPhase = gwPhaseOut;
        dst.e1X = orbE1X; dst.e1Y = orbE1Y; dst.e1Z = orbE1Z;
        dst.lHatX = orbLHX; dst.lHatY = orbLHY; dst.lHatZ = orbLHZ;
        dst.e3X = orbE3X; dst.e3Y = orbE3Y; dst.e3Z = orbE3Z;
    }

    public BinaryParameters parameters() { return params; }
    public EobBinaryDynamics eobDynamics() { return eob; }
    public BinaryRemnantModel.RemnantProperties remnant() { return remnant; }
    public Phase phase() { return phase; }
    public boolean isMergerFinished() { return mergerFinished; }
    public double geomTime() { return geomTime; }
    public double qnmFreqGeom() { return remnant.omega220(); }
    public double qnmDampingGeom() { return remnant.gamma220(); }
    public double qnmTauGeom() { return remnant.tau220(); }
    public float[] strainHistory() { return strainHistory; }
    public double[] physStrainHistory() { return physStrainHistory; }
    public int strainCursor() { return strainCursor; }

    public int visHistCount() { return visHistCount; }
    public int visHistStartIdx() { return (visHistCount < VIS_HISTORY_LEN) ? 0 : visHistCursor; }
    public float[] trail1X() { return trail1X; }
    public float[] trail1Y() { return trail1Y; }
    public float[] trail1Z() { return trail1Z; }
    public float[] trail2X() { return trail2X; }
    public float[] trail2Y() { return trail2Y; }
    public float[] trail2Z() { return trail2Z; }
    public byte[] trailPhase() { return trailPhase; }
    public float[] histGeomTime() { return histGeomTime; }
    public float[] histMode22() { return histMode22; }
    public float[] histMode21() { return histMode21; }
    public float[] histMode33() { return histMode33; }
    public float[] histMode44() { return histMode44; }
    public float[] histAmp22() { return histAmp22; }
    public float[] histAmp21() { return histAmp21; }
    public float[] histAmp33() { return histAmp33; }
    public float[] histAmp44() { return histAmp44; }
    public float[] histGeomHPlus() { return histGeomHPlus; }
    public float[] histGeomHCross() { return histGeomHCross; }
    public double[] histPhysHPlus() { return histPhysHPlus; }
    public double[] histPhysHCross() { return histPhysHCross; }
    public double[] histPhysHDet() { return histPhysHDet; }
    public float[] histGwFreqHz() { return histGwFreqHz; }
    public float[] histOmegaGeom() { return histOmegaGeom; }
    public float[] histSeparation() { return histSeparation; }
    public float[] histOrbitalEnergy() { return histOrbitalEnergy; }
    public float[] histRadiatedEnergy() { return histRadiatedEnergy; }
    public float[] histOrbitalAngMom() { return histOrbitalAngMom; }
    public float[] histRadiatedAngMom() { return histRadiatedAngMom; }
    public float[] histEnergyBalanceErr() { return histEnergyBalanceErr; }
    public double initialBindingEnergy() { return initialBindingEnergy; }
    public double initialTotalAngMom() { return initialTotalAngMom; }

    /** Backward-compatible 1PN/EOB helper for existing tests. */
    public static double computeOrbitalOmega(double r, double eta) {
        double q = (1.0 - Math.sqrt(Math.max(0.0, 1.0 - 4.0 * eta)))
                 / Math.max(1e-9, 1.0 + Math.sqrt(Math.max(0.0, 1.0 - 4.0 * eta)));
        EobBinaryDynamics dyn = new EobBinaryDynamics(1.0 / (1.0 + q), q / (1.0 + q), 0.0, 0.0);
        double pPhi = dyn.solveCircularPphi(r);
        EobBinaryDynamics.EvalResult ev = new EobBinaryDynamics.EvalResult();
        dyn.evaluate(r, 0.0, pPhi, false, ev);
        return ev.omega;
    }
}
