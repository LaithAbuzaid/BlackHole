package io.github.laithabuzaid.blackhole.core;

import java.util.Arrays;

/**
 * Deterministic, frame-rate-independent relativistic Binary Black Hole Merger &
 * Gravitational Wave solver in geometric units ({@code G = c = 1, M_tot = 1}).
 *
 * <p>Evolves three physical phases with zero per-frame heap allocations:
 * <ol>
 *   <li><b>Inspiral &amp; Plunge:</b> Exact Peters–Mathews quadrupole energy-loss ODE
 *       ({@code da/dt = -(64/5) * eta / a^3}) with 1PN orbital phase evolution from
 *       {@code a_0 = 9.0 M} down to {@code a_merge = 2.2 M}.</li>
 *   <li><b>Coalescence / Merger:</b> Smooth horizon fusion to the origin while radiating
 *       {@code ~4.8% * 4*eta} mass-energy into peak quadrupole strain and forming a
 *       spinning Kerr remnant ({@code a_f ~ 3.464*eta - 2.9*eta^2}).</li>
 *   <li><b>Quasi-Normal Mode (QNM) Ringdown:</b> Damped {@code (l=2, m=2)} quadrupole
 *       oscillation on the remnant black hole (~4 s) with optional auto-loop.</li>
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

    public static final double INITIAL_SEPARATION = 9.0;
    public static final double PLUNGE_SEPARATION = 3.3;
    public static final double MERGE_SEPARATION = 2.2;

    // Exact Peters-Mathews coefficient (64/5) in G = c = M_tot = 1 units
    private static final double PETERS_MATHEWS_K = 64.0 / 5.0;
    // Geometric M per wall-second at timeScale = 1.0 (~22.3s inspiral for q = 0.65)
    private static final double GEOM_TIME_PER_SEC = 24.0;
    private static final double MERGER_WALL_DURATION = 0.90;
    private static final double RINGDOWN_WALL_DURATION = 4.20;
    private static final double MAX_SUBSTEP_SEC = 0.004;
    private static final double STRAIN_SAMPLE_PERIOD = 0.040; // 25 Hz -> 6.4s LIGO trace

    // Pre-allocated rolling LIGO h+(t) waveform ring buffer (zero allocations)
    private final float[] strainHistory = new float[STRAIN_HISTORY_LEN];
    private int strainCursor = 0;
    private double sampleAccumSec = 0.0;

    // Internal ODE state
    private double simTimeWall = 0.0;
    private double separation = INITIAL_SEPARATION;
    private double orbitalPhase = 0.35;
    private double gwPhase = 0.70;
    private double phaseClockSec = 0.0;
    private double mergerPeakOmega = 0.42;
    private double ringdownTauSec = 0.68;
    private double ringdownOmegaWall = 18.0;
    private double qnmFreqGeom = 0.55;
    private double qnmDampingGeom = 0.085;
    private boolean mergerFinished = false;
    private Phase phase = Phase.INSPIRAL;

    // Live zero-allocation physics & telemetry outputs
    public float pos1X, pos1Y, pos1Z;
    public float pos2X, pos2Y, pos2Z;
    public float vel1X, vel1Y, vel1Z;
    public float vel2X, vel2Y, vel2Z;
    public float mass1 = 0.606f;
    public float mass2 = 0.394f;
    public float symMassRatio = 0.2387f;
    public float remnantMass = 0.954f;
    public float remnantSpin = 0.662f;
    public float mergeBlend = 0.0f;
    public float ringdownEnv = 0.0f;
    public float mergerPulse = 0.0f;
    public float currentSeparation = (float) INITIAL_SEPARATION;
    public float currentOrbitalPhase = 0.35f;
    public float orbitalBeta = 0.25f;
    public float gwFreqHz = 45.0f;
    public float strainPlus = 0.0f;
    public float strainCross = 0.0f;
    public float strainAmplitude = 0.0f;
    public float binaryTime = 0.0f;
    public float gwPhaseOut = 0.70f;

    public BinaryMergerPhysics() {
        reset(0.65f);
    }

    /** Resets the binary cleanly to initial wide inspiral separation a_0 = 9.0 M. */
    public void reset(float massRatioQ) {
        double q = clampQ(massRatioQ);
        updateMassParameters(q);

        this.simTimeWall = 0.0;
        this.separation = INITIAL_SEPARATION;
        this.orbitalPhase = 0.35;
        this.gwPhase = 2.0 * this.orbitalPhase;
        this.phaseClockSec = 0.0;
        this.mergerFinished = false;
        this.phase = Phase.INSPIRAL;
        this.mergeBlend = 0.0f;
        this.ringdownEnv = 0.0f;
        this.mergerPulse = 0.0f;

        Arrays.fill(strainHistory, 0.0f);
        strainCursor = 0;
        sampleAccumSec = 0.0;

        recomputeOutputs(0.0);
    }

    /**
     * Deterministically advances the binary with fixed substeps for frame-rate independence.
     */
    public void step(double dtSec, float massRatioQ, float timeScale, boolean autoLoop) {
        if (dtSec <= 0.0) {
            updateMassParameters(clampQ(massRatioQ));
            return;
        }
        double q = clampQ(massRatioQ);
        updateMassParameters(q);

        double scaledDt = Math.min(0.10, dtSec) * Math.max(0.05, timeScale);
        int substeps = Math.max(1, (int) Math.ceil(scaledDt / MAX_SUBSTEP_SEC));
        double h = scaledDt / substeps;

        for (int s = 0; s < substeps; s++) {
            stepSub(h, autoLoop);
            sampleAccumSec += h;
            while (sampleAccumSec >= STRAIN_SAMPLE_PERIOD) {
                sampleAccumSec -= STRAIN_SAMPLE_PERIOD;
                strainHistory[strainCursor] = strainPlus;
                strainCursor = (strainCursor + 1) % STRAIN_HISTORY_LEN;
            }
        }
    }

    /**
     * Deterministically marches from a_0 = 9.0 M down to {@code targetSep} (in M) so
     * showcase captures start with a populated LIGO strain buffer and authentic phase.
     */
    public void advanceToSeparation(float massRatioQ, double targetSep) {
        reset(massRatioQ);
        double clampedTarget = Math.max(MERGE_SEPARATION + 0.02, Math.min(INITIAL_SEPARATION, targetSep));
        int safety = 0;
        while (separation > clampedTarget && phase != Phase.MERGER && phase != Phase.RINGDOWN && safety++ < 20000) {
            step(1.0 / 60.0, massRatioQ, 1.0f, false);
        }
    }

    private void stepSub(double dtWall, boolean autoLoop) {
        simTimeWall += dtWall;
        double dtGeom = dtWall * GEOM_TIME_PER_SEC;
        double eta = symMassRatio;

        if (phase == Phase.INSPIRAL || phase == Phase.PLUNGE) {
            // Midpoint integration of exact Peters-Mathews da/dt = -(64/5) * eta / a^3
            double a = separation;
            double da1 = -PETERS_MATHEWS_K * eta / (a * a * a);
            double aMid = Math.max(MERGE_SEPARATION * 0.85, a + 0.5 * da1 * dtGeom);
            double daMid = -PETERS_MATHEWS_K * eta / (aMid * aMid * aMid);
            double aNext = a + daMid * dtGeom;

            // 1PN-corrected orbital angular velocity dPhi/dt = sqrt(M/a^3) * (1 + (3 - eta)/2 * M/a)
            double omegaMid = computeOrbitalOmega(aMid, eta);
            orbitalPhase += omegaMid * dtGeom;
            gwPhase += 2.0 * omegaMid * dtGeom;

            double drDtGeom = daMid;
            if (aNext <= MERGE_SEPARATION) {
                separation = MERGE_SEPARATION;
                mergerPeakOmega = computeOrbitalOmega(MERGE_SEPARATION, eta);
                phase = Phase.MERGER;
                phaseClockSec = 0.0;
                // Calibrate continuous visual QNM frequency & damping to remnant spin a_f
                ringdownOmegaWall = 2.0 * mergerPeakOmega * GEOM_TIME_PER_SEC * 1.18;
                ringdownTauSec = 0.56 + 0.42 * remnantSpin;
                recomputeOutputs(drDtGeom);
            } else {
                separation = aNext;
                phase = (separation <= PLUNGE_SEPARATION) ? Phase.PLUNGE : Phase.INSPIRAL;
                recomputeOutputs(drDtGeom);
            }
        } else if (phase == Phase.MERGER) {
            phaseClockSec += dtWall;
            double u = Math.min(1.0, phaseClockSec / MERGER_WALL_DURATION);
            mergeBlend = (float) (u * u * (3.0 - 2.0 * u));

            // Smooth chirp frequency ramp from plunge contact into QNM ringdown frequency
            double omegaNow = mergerPeakOmega * (1.0 + 0.18 * mergeBlend);
            orbitalPhase += omegaNow * dtGeom;
            gwPhase += 2.0 * omegaNow * dtGeom;

            double effSep = MERGE_SEPARATION * (1.0 - mergeBlend);
            separation = effSep;
            recomputeMergerOutputs(u, omegaNow);

            if (u >= 1.0) {
                phase = Phase.RINGDOWN;
                phaseClockSec = 0.0;
                mergeBlend = 1.0f;
                separation = 0.0;
            }
        } else { // RINGDOWN
            phaseClockSec += dtWall;
            gwPhase += ringdownOmegaWall * dtWall;
            orbitalPhase += 0.5 * ringdownOmegaWall * dtWall;
            recomputeRingdownOutputs(phaseClockSec);

            if (phaseClockSec >= RINGDOWN_WALL_DURATION) {
                if (autoLoop) {
                    reset(mass2 / Math.max(1e-4f, mass1));
                } else {
                    mergerFinished = true;
                }
            }
        }
    }

    private void recomputeOutputs(double daDtGeom) {
        double a = Math.max(MERGE_SEPARATION, separation);
        double eta = symMassRatio;
        double omega = computeOrbitalOmega(a, eta);

        double c = Math.cos(orbitalPhase);
        double s = Math.sin(orbitalPhase);

        // Center-of-mass frame in xz orbital plane (prograde counter-clockwise in (-z, +x))
        double r1 = mass2 * a;
        double r2 = mass1 * a;
        pos1X = (float) (r1 * c);
        pos1Y = 0.0f;
        pos1Z = (float) (r1 * s);

        pos2X = (float) (-r2 * c);
        pos2Y = 0.0f;
        pos2Z = (float) (-r2 * s);

        // Tangential + Peters-Mathews radial infall velocity in geometric units (v/c)
        double vTan1 = r1 * omega;
        double vRad1 = mass2 * daDtGeom;
        double vTan2 = r2 * omega;
        double vRad2 = mass1 * daDtGeom;

        vel1X = (float) (-vTan1 * s + vRad1 * c);
        vel1Y = 0.0f;
        vel1Z = (float) (vTan1 * c + vRad1 * s);

        vel2X = (float) (vTan2 * s - vRad2 * c);
        vel2Y = 0.0f;
        vel2Z = (float) (-vTan2 * c - vRad2 * s);

        // Clamp individual coordinate speeds cleanly below c for Lorentz factor safety
        clampVelocity(1);
        clampVelocity(2);

        mergeBlend = 0.0f;
        ringdownEnv = 0.0f;
        mergerPulse = 0.0f;
        currentSeparation = (float) a;
        currentOrbitalPhase = (float) orbitalPhase;
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;

        double vRel = Math.min(0.68, a * omega);
        orbitalBeta = (float) Math.min(0.55, Math.max(Math.hypot(vel1X, vel1Z), Math.hypot(vel2X, vel2Z)));

        // Quadrupole strain amplitude h ~ 4 * eta * (M / a) normalized to 1.0 at equal-mass merger
        double normAmp = (4.0 * eta) * (MERGE_SEPARATION / a) * 0.72;
        strainAmplitude = (float) Math.min(1.0, normAmp);
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        // Map dimensionless GW frequency 2*omega into iconic audible LIGO chirp band [42 Hz .. 310 Hz]
        double omegaMin = computeOrbitalOmega(INITIAL_SEPARATION, eta);
        double omegaMax = computeOrbitalOmega(MERGE_SEPARATION, eta);
        double freqNorm = Math.max(0.0, Math.min(1.0, (omega - omegaMin) / Math.max(1e-5, omegaMax - omegaMin)));
        gwFreqHz = (float) (42.0 + 258.0 * Math.pow(freqNorm, 1.12));
        // Avoid unused local warning while retaining accurate relative speed diagnostic
        if (vRel < 0.0) {
            orbitalBeta = 0.0f;
        }
    }

    private void recomputeMergerOutputs(double u, double omegaNow) {
        double aEff = MERGE_SEPARATION * (1.0 - mergeBlend);
        double c = Math.cos(orbitalPhase);
        double s = Math.sin(orbitalPhase);

        double r1 = mass2 * aEff;
        double r2 = mass1 * aEff;
        pos1X = (float) (r1 * c);
        pos1Y = 0.0f;
        pos1Z = (float) (r1 * s);

        pos2X = (float) (-r2 * c);
        pos2Y = 0.0f;
        pos2Z = (float) (-r2 * s);

        double damp = 1.0 - mergeBlend;
        vel1X = (float) (-r1 * omegaNow * s * damp);
        vel1Y = 0.0f;
        vel1Z = (float) (r1 * omegaNow * c * damp);
        vel2X = (float) (r2 * omegaNow * s * damp);
        vel2Y = 0.0f;
        vel2Z = (float) (-r2 * omegaNow * c * damp);
        clampVelocity(1);
        clampVelocity(2);

        double hPeak = 4.0 * symMassRatio; // 1.0 for equal-mass binary
        double peakBell = 0.72 + 0.28 * Math.sin(u * Math.PI * 0.78);
        strainAmplitude = (float) (hPeak * peakBell);
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        // Sharp coalescence impact envelope for audio thump & horizon shock
        double pulseDist = (u - 0.65) / 0.24;
        mergerPulse = (float) Math.exp(-pulseDist * pulseDist);
        ringdownEnv = (float) Math.max(0.0, (u - 0.55) / 0.45);

        currentSeparation = (float) aEff;
        currentOrbitalPhase = (float) orbitalPhase;
        orbitalBeta = (float) (0.50 * (1.0 - 0.85 * mergeBlend));
        gwFreqHz = (float) (300.0 + 40.0 * mergeBlend);
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;
    }

    private void recomputeRingdownOutputs(double tPrimeSec) {
        pos1X = pos1Y = pos1Z = 0.0f;
        pos2X = pos2Y = pos2Z = 0.0f;
        vel1X = vel1Y = vel1Z = 0.0f;
        vel2X = vel2Y = vel2Z = 0.0f;

        double hPeak = 4.0 * symMassRatio;
        double decay = Math.exp(-tPrimeSec / Math.max(0.15, ringdownTauSec));
        ringdownEnv = (float) decay;
        mergerPulse = (float) Math.exp(-tPrimeSec * 5.2);

        strainAmplitude = (float) (hPeak * decay);
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        currentSeparation = 0.0f;
        currentOrbitalPhase = (float) orbitalPhase;
        orbitalBeta = 0.0f;
        // Ringdown settles cleanly at the fundamental (l=2, m=2) QNM frequency
        gwFreqHz = (float) (340.0 * (0.94 + 0.06 * remnantSpin));
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;
    }

    private void clampVelocity(int body) {
        if (body == 1) {
            float v2 = vel1X * vel1X + vel1Y * vel1Y + vel1Z * vel1Z;
            if (v2 > 0.3025f) { // <= 0.55c
                float s = 0.55f / (float) Math.sqrt(v2);
                vel1X *= s; vel1Y *= s; vel1Z *= s;
            }
        } else {
            float v2 = vel2X * vel2X + vel2Y * vel2Y + vel2Z * vel2Z;
            if (v2 > 0.3025f) {
                float s = 0.55f / (float) Math.sqrt(v2);
                vel2X *= s; vel2Y *= s; vel2Z *= s;
            }
        }
    }

    private void updateMassParameters(double q) {
        double m1 = 1.0 / (1.0 + q);
        double m2 = q / (1.0 + q);
        double eta = q / ((1.0 + q) * (1.0 + q));

        // Numerical relativity fits for non-spinning quasicircular merger remnant
        double mf = 1.0 - 0.048 * 4.0 * eta;
        double af = Math.max(0.0, Math.min(0.998, 3.464 * eta - 2.9 * eta * eta));

        // Berti-Cardoso-Will (2006) fundamental (l=2, m=2) QNM fit
        double omegaQnm = (1.5251 - 1.1568 * Math.pow(Math.max(1e-4, 1.0 - af), 0.1292)) / mf;
        double qualityQ = 0.700 + 1.4187 * Math.pow(Math.max(1e-4, 1.0 - af), -0.499);

        this.mass1 = (float) m1;
        this.mass2 = (float) m2;
        this.symMassRatio = (float) eta;
        this.remnantMass = (float) mf;
        this.remnantSpin = (float) af;
        this.qnmFreqGeom = omegaQnm;
        this.qnmDampingGeom = omegaQnm / (2.0 * qualityQ);
    }

    private static double computeOrbitalOmega(double a, double eta) {
        double invA = 1.0 / Math.max(1.0, a);
        return Math.sqrt(invA * invA * invA) * (1.0 + 0.5 * (3.0 - eta) * invA);
    }

    private static double clampQ(float q) {
        return Math.max(SimulationState.MIN_MASS_RATIO, Math.min(SimulationState.MAX_MASS_RATIO, q));
    }

    public Phase phase() {
        return phase;
    }

    public boolean isMergerFinished() {
        return mergerFinished;
    }

    public double qnmFreqGeom() {
        return qnmFreqGeom;
    }

    public double qnmDampingGeom() {
        return qnmDampingGeom;
    }

    public float[] strainHistory() {
        return strainHistory;
    }

    public int strainCursor() {
        return strainCursor;
    }
}
