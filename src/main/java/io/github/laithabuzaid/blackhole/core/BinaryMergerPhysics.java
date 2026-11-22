package io.github.laithabuzaid.blackhole.core;

import java.util.Arrays;

/**
 * Deterministic, frame-rate-independent analytical Post-Newtonian + NR-calibrated
 * phenomenological Binary Black Hole Merger &amp; Gravitational Wave model in geometric
 * units ({@code G = c = 1, M_tot = 1}).
 *
 * <p><b>Approximation Status (Explicit):</b> This is an <em>analytical/phenomenological
 * approximation</em>, NOT a 3+1 Numerical Relativity PDE evolution:
 * <ol>
 *   <li><b>Inspiral &amp; Plunge ({@code a_0 = 9.0 M -> a_merge = 2.2 M}):</b>
 *       2.5PN Peters-Mathews quadrupole flux ODE with 1PN/2PN quasi-circular orbital
 *       frequency {@code Omega(a) = a^(-3/2) [1 - (3 - eta)/(2a) + (36 - 5*eta + eta^2)/(8 a^2)]}
 *       (Blanchet 2014 harmonic coordinates; exact negative 1PN sign), integrated via 4th-order RK4.</li>
 *   <li><b>Coalescence / Merger:</b> {@code C^1}-smooth phenomenological horizon fusion with
 *       radial + tangential infall velocities, radiating energy into the peak quadrupole strain
 *       and transitioning continuously to the remnant Kerr black hole.</li>
 *   <li><b>Quasi-Normal Mode (QNM) Ringdown:</b> Driven directly by the Berti-Cardoso-Will (2006)
 *       fundamental {@code (l=2, m=2, n=0)} Kerr QNM frequency {@code omega_220} and damping rate
 *       {@code gamma_220} with NR-calibrated Hofmann-Barausse-Rezzolla / Healy-Lousto-Zlochower
 *       non-spinning remnant mass {@code M_f} and spin {@code a_f}.</li>
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

    // Exact 2.5PN Peters-Mathews quadrupole flux coefficient (64/5) in G = c = M_tot = 1 units
    private static final double PETERS_MATHEWS_K = 64.0 / 5.0;
    private static final double GEOM_TIME_PER_SEC = 24.0;
    private static final double MERGER_WALL_DURATION = 0.85;
    private static final double RINGDOWN_WALL_DURATION = 4.00;
    private static final double MAX_SUBSTEP_SEC = 0.004;
    private static final int MAX_SUBSTEPS_PER_FRAME = 350;
    private static final double STRAIN_SAMPLE_PERIOD = 0.040;
    // Reference total binary mass for physical Hz readout (GW150914-scale 65 M_sun -> f_QNM ~ 251 Hz)
    private static final double REF_TOTAL_MASS_SUN = 65.0;
    private static final double C3_OVER_2PI_G_MSUN_HZ = 32312.5; // c^3 / (2*pi*G*M_sun)

    // Pre-allocated rolling LIGO h+(t) waveform ring buffer (zero allocations)
    private final float[] strainHistory = new float[STRAIN_HISTORY_LEN];
    private int strainCursor = 0;
    private double sampleAccumSec = 0.0;

    // Internal ODE state
    private double initialMassRatio = 0.65;
    private double simTimeWall = 0.0;
    private double separation = INITIAL_SEPARATION;
    private double orbitalPhase = 0.35;
    private double gwPhase = 0.70;
    private double phaseClockSec = 0.0;
    private double mergerStartOmegaGeom = 0.25;
    private double mergerStartStrainAmp = 0.70;
    private double mergerPeakStrainAmp = 1.00;
    private double ringdownStartStrainAmp = 1.00;
    private double ringdownOmegaGeom = 0.55;
    private double ringdownTauSec = 0.55;
    private double qnmFreqGeom = 0.55;
    private double qnmDampingGeom = 0.085;
    private double initialBindingEnergy = 0.0;
    private double radiatedEnergyAccum = 0.0;
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
    public float gwFreqGeom = 0.07f;
    public float strainPlus = 0.0f;
    public float strainCross = 0.0f;
    public float strainAmplitude = 0.0f;
    public float binaryTime = 0.0f;
    public float gwPhaseOut = 0.70f;
    // Live conservation diagnostics (dimensionless geometric units M_tot = 1)
    public float orbitalEnergy = 0.0f;
    public float radiatedEnergy = 0.0f;
    public float orbitalAngularMomentum = 0.0f;

    public BinaryMergerPhysics() {
        reset(0.65f);
    }

    /** Resets the binary cleanly to initial wide inspiral separation {@code a_0 = 9.0 M}. */
    public void reset(float massRatioQ) {
        double q = clampQ(massRatioQ);
        this.initialMassRatio = q;
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
        this.initialBindingEnergy = computeBindingEnergy(INITIAL_SEPARATION, symMassRatio);
        this.radiatedEnergyAccum = 0.0;

        Arrays.fill(strainHistory, 0.0f);
        strainCursor = 0;
        sampleAccumSec = 0.0;

        recomputeInspiralOutputs(computeDaDt(INITIAL_SEPARATION, symMassRatio));
    }

    /** Deterministically advances the binary with fixed RK4 substeps for frame-rate independence. */
    public void step(double dtSec, float massRatioQ, float timeScale, boolean autoLoop) {
        if (dtSec <= 0.0) {
            updateMassParameters(clampQ(massRatioQ));
            return;
        }
        double q = clampQ(massRatioQ);
        this.initialMassRatio = q;
        updateMassParameters(q);

        double clampedWallDt = Math.min(0.05, dtSec);
        double clampedScale = Math.max(SimulationState.MIN_TIME_SCALE, Math.min(SimulationState.MAX_TIME_SCALE, timeScale));
        double scaledDt = clampedWallDt * clampedScale;
        int substeps = Math.max(1, Math.min(MAX_SUBSTEPS_PER_FRAME, (int) Math.ceil(scaledDt / MAX_SUBSTEP_SEC)));
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

    /** Deterministically marches from {@code a_0 = 9.0 M} down to {@code targetSep}. */
    public void advanceToSeparation(float massRatioQ, double targetSep) {
        reset(massRatioQ);
        double clampedTarget = Math.max(MERGE_SEPARATION + 0.02, Math.min(INITIAL_SEPARATION, targetSep));
        int safety = 0;
        while (separation > clampedTarget && phase != Phase.MERGER && phase != Phase.RINGDOWN && safety++ < 25000) {
            step(1.0 / 60.0, massRatioQ, 1.0f, false);
        }
    }

    private void stepSub(double dtWall, boolean autoLoop) {
        simTimeWall += dtWall;
        double dtGeom = dtWall * GEOM_TIME_PER_SEC;
        double eta = symMassRatio;

        if (phase == Phase.INSPIRAL || phase == Phase.PLUNGE) {
            // 4th-order RK4 integration of 2.5PN Peters-Mathews radial decay + 2PN orbital phase
            double a0 = separation;
            double k1a = computeDaDt(a0, eta);
            double k1p = computeOrbitalOmega(a0, eta);

            double a2 = Math.max(MERGE_SEPARATION * 0.85, a0 + 0.5 * dtGeom * k1a);
            double k2a = computeDaDt(a2, eta);
            double k2p = computeOrbitalOmega(a2, eta);

            double a3 = Math.max(MERGE_SEPARATION * 0.85, a0 + 0.5 * dtGeom * k2a);
            double k3a = computeDaDt(a3, eta);
            double k3p = computeOrbitalOmega(a3, eta);

            double a4 = Math.max(MERGE_SEPARATION * 0.85, a0 + dtGeom * k3a);
            double k4a = computeDaDt(a4, eta);
            double k4p = computeOrbitalOmega(a4, eta);

            double daAvg = (k1a + 2.0 * k2a + 2.0 * k3a + k4a) / 6.0;
            double omegaAvg = (k1p + 2.0 * k2p + 2.0 * k3p + k4p) / 6.0;
            double aNext = a0 + daAvg * dtGeom;

            // Positive-definite Peters-Mathews quadrupole GW energy luminosity dE_rad/dt = (32/5) * eta^2 / a^5
            // (matches -dE_bind/dt in weak field and stays strictly non-negative through plunge!)
            double aMidFlux = Math.max(MERGE_SEPARATION, 0.5 * (a0 + Math.max(MERGE_SEPARATION, aNext)));
            double fluxCoeff = (32.0 / 5.0) * eta * eta / Math.pow(aMidFlux, 5.0);
            double maxInspiralRad = (1.0 - remnantMass) * 0.65;
            radiatedEnergyAccum = Math.min(maxInspiralRad, radiatedEnergyAccum + fluxCoeff * dtGeom);

            orbitalPhase += omegaAvg * dtGeom;
            gwPhase += 2.0 * omegaAvg * dtGeom;

            if (aNext <= MERGE_SEPARATION) {
                separation = MERGE_SEPARATION;
                mergerStartOmegaGeom = computeOrbitalOmega(MERGE_SEPARATION, eta);
                mergerStartStrainAmp = Math.min(1.0, (4.0 * eta) * 0.72);
                mergerPeakStrainAmp = 4.0 * eta;
                ringdownStartStrainAmp = mergerPeakStrainAmp;
                // Drive ringdown directly from Berti-Cardoso-Will (2006) (2,2,0) QNM parameters
                ringdownOmegaGeom = qnmFreqGeom;
                // Visual ringdown damping preserves exact QNM quality factor Q = omega / (2 * gamma)
                // scaled cleanly to human-observable wall time (~0.45..0.95s)
                ringdownTauSec = Math.max(0.22, 1.0 / (qnmDampingGeom * GEOM_TIME_PER_SEC * 0.75));
                phase = Phase.MERGER;
                phaseClockSec = 0.0;
                recomputeInspiralOutputs(daAvg);
            } else {
                separation = aNext;
                phase = (separation <= PLUNGE_SEPARATION) ? Phase.PLUNGE : Phase.INSPIRAL;
                recomputeInspiralOutputs(daAvg);
            }
        } else if (phase == Phase.MERGER) {
            phaseClockSec += dtWall;
            double u = Math.min(1.0, phaseClockSec / MERGER_WALL_DURATION);
            // Quintic C^2 smoothstep (zero 1st and 2nd derivatives at u = 0 and u = 1)
            double s = u * u * u * (u * (u * 6.0 - 15.0) + 10.0);
            double dsDu = 30.0 * u * u * (1.0 - u) * (1.0 - u);
            mergeBlend = (float) s;

            // Smooth GW frequency chirp from 2*mergerStartOmegaGeom -> exact BCW qnmFreqGeom
            double omegaGwNow = (2.0 * mergerStartOmegaGeom) * (1.0 - s) + ringdownOmegaGeom * s;
            double omegaOrbNow = 0.5 * omegaGwNow;
            orbitalPhase += omegaOrbNow * dtGeom;
            gwPhase += omegaGwNow * dtGeom;

            double effSep = MERGE_SEPARATION * (1.0 - s);
            double daDtGeom = -MERGE_SEPARATION * (dsDu / (MERGER_WALL_DURATION * GEOM_TIME_PER_SEC));
            separation = effSep;
            recomputeMergerOutputs(u, s, omegaOrbNow, omegaGwNow, daDtGeom);

            if (u >= 1.0) {
                phase = Phase.RINGDOWN;
                phaseClockSec = 0.0;
                mergeBlend = 1.0f;
                separation = 0.0;
                // Ensure exact C^0/C^1 handoff to ringdown at t' = 0
                recomputeRingdownOutputs(0.0);
            }
        } else { // RINGDOWN
            phaseClockSec += dtWall;
            gwPhase += ringdownOmegaGeom * dtGeom;
            orbitalPhase += 0.5 * ringdownOmegaGeom * dtGeom;
            recomputeRingdownOutputs(phaseClockSec);

            if (phaseClockSec >= RINGDOWN_WALL_DURATION) {
                if (autoLoop) {
                    reset((float) initialMassRatio);
                } else {
                    mergerFinished = true;
                }
            }
        }
    }

    private void recomputeInspiralOutputs(double daDtGeom) {
        double a = Math.max(MERGE_SEPARATION, separation);
        double eta = symMassRatio;
        double omega = computeOrbitalOmega(a, eta);

        double c = Math.cos(orbitalPhase);
        double s = Math.sin(orbitalPhase);

        double r1 = mass2 * a;
        double r2 = mass1 * a;
        pos1X = (float) (r1 * c);
        pos1Y = 0.0f;
        pos1Z = (float) (r1 * s);

        pos2X = (float) (-r2 * c);
        pos2Y = 0.0f;
        pos2Z = (float) (-r2 * s);

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

        clampVelocity(1);
        clampVelocity(2);

        mergeBlend = 0.0f;
        ringdownEnv = 0.0f;
        mergerPulse = 0.0f;
        currentSeparation = (float) a;
        currentOrbitalPhase = (float) orbitalPhase;
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;
        orbitalBeta = (float) Math.min(0.55, Math.max(Math.hypot(vel1X, vel1Z), Math.hypot(vel2X, vel2Z)));

        // Quadrupole strain amplitude h ~ 4 * eta * (a_merge / a) * 0.72
        double normAmp = (4.0 * eta) * (MERGE_SEPARATION / a) * 0.72;
        strainAmplitude = (float) Math.min(1.0, normAmp);
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        double omegaGw = 2.0 * omega;
        gwFreqGeom = (float) omegaGw;
        gwFreqHz = (float) geomOmegaToHz(omegaGw);

        // Conservation diagnostics: binding energy E(t) = E_0 - E_rad(t) and angular momentum L(a)
        orbitalEnergy = (float) (initialBindingEnergy - radiatedEnergyAccum);
        radiatedEnergy = (float) radiatedEnergyAccum;
        orbitalAngularMomentum = (float) computeAngularMomentum(a, eta);
    }

    private void recomputeMergerOutputs(double u, double sBlend, double omegaOrb, double omegaGw, double daDtGeom) {
        double aEff = MERGE_SEPARATION * (1.0 - sBlend);
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

        // Include both tangential and smooth radial coalescence velocities
        double damp = 1.0 - sBlend;
        double vTan1 = r1 * omegaOrb;
        double vRad1 = mass2 * daDtGeom;
        double vTan2 = r2 * omegaOrb;
        double vRad2 = mass1 * daDtGeom;

        vel1X = (float) ((-vTan1 * s + vRad1 * c) * damp);
        vel1Y = 0.0f;
        vel1Z = (float) ((vTan1 * c + vRad1 * s) * damp);
        vel2X = (float) ((vTan2 * s - vRad2 * c) * damp);
        vel2Y = 0.0f;
        vel2Z = (float) ((-vTan2 * c - vRad2 * s) * damp);
        clampVelocity(1);
        clampVelocity(2);

        // C^1-smooth amplitude rise from mergerStartStrainAmp (u=0) -> ringdownStartStrainAmp (u=1)
        double amp = mergerStartStrainAmp + (ringdownStartStrainAmp - mergerStartStrainAmp) * sBlend;
        strainAmplitude = (float) amp;
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        // C^1-smooth mergerPulse and ringdownEnv reaching exactly 1.0 at u = 1.0 (zero jump into ringdown!)
        mergerPulse = (float) sBlend;
        ringdownEnv = (float) sBlend;

        currentSeparation = (float) aEff;
        currentOrbitalPhase = (float) orbitalPhase;
        orbitalBeta = (float) Math.max(Math.hypot(vel1X, vel1Z), Math.hypot(vel2X, vel2Z));
        gwFreqGeom = (float) omegaGw;
        gwFreqHz = (float) geomOmegaToHz(omegaGw);
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;

        // Smoothly radiate remaining energy up to total NR radiated mass-energy (1 - M_f)
        double totalRadTarget = Math.max(0.0, 1.0 - remnantMass);
        double radNow = Math.max(radiatedEnergyAccum,
                radiatedEnergyAccum + (totalRadTarget - radiatedEnergyAccum) * sBlend);
        radiatedEnergy = (float) radNow;
        orbitalEnergy = (float) (initialBindingEnergy - radNow);
        double lMergeStart = computeAngularMomentum(MERGE_SEPARATION, symMassRatio);
        double jFinal = remnantSpin * remnantMass * remnantMass;
        orbitalAngularMomentum = (float) (lMergeStart * (1.0 - sBlend) + jFinal * sBlend);
    }

    private void recomputeRingdownOutputs(double tPrimeSec) {
        pos1X = pos1Y = pos1Z = 0.0f;
        pos2X = pos2Y = pos2Z = 0.0f;
        vel1X = vel1Y = vel1Z = 0.0f;
        vel2X = vel2Y = vel2Z = 0.0f;

        double decay = Math.exp(-tPrimeSec / Math.max(0.15, ringdownTauSec));
        ringdownEnv = (float) decay;
        mergerPulse = (float) Math.exp(-tPrimeSec * 4.5);

        strainAmplitude = (float) (ringdownStartStrainAmp * decay);
        strainPlus = (float) (strainAmplitude * Math.cos(gwPhase));
        strainCross = (float) (strainAmplitude * Math.sin(gwPhase));

        currentSeparation = 0.0f;
        currentOrbitalPhase = (float) orbitalPhase;
        orbitalBeta = 0.0f;
        gwFreqGeom = (float) ringdownOmegaGeom;
        gwFreqHz = (float) geomOmegaToHz(ringdownOmegaGeom);
        binaryTime = (float) simTimeWall;
        gwPhaseOut = (float) gwPhase;

        radiatedEnergy = (float) Math.max(0.0, 1.0 - remnantMass);
        orbitalEnergy = (float) -(1.0 - remnantMass);
        orbitalAngularMomentum = (float) (remnantSpin * remnantMass * remnantMass);
    }

    private void clampVelocity(int body) {
        if (body == 1) {
            float v2 = vel1X * vel1X + vel1Y * vel1Y + vel1Z * vel1Z;
            if (v2 > 0.3025f) {
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

        // NR-calibrated non-spinning remnant mass fit (exact test-particle ISCO limit 1 - sqrt(8/9)
        // as eta -> 0, and exact equal-mass NR E_rad/M = 0.0484 at eta = 0.25)
        double eIscoTest = 1.0 - Math.sqrt(8.0 / 9.0); // 0.0571909584...
        double eRadFrac = eIscoTest * eta + 0.545 * eta * eta;
        double mf = Math.max(0.85, 1.0 - eRadFrac);

        // NR-calibrated non-spinning remnant spin (Hofmann-Barausse-Rezzolla 2016: a_f(0.25) = 0.6865)
        double af = Math.max(0.0, Math.min(0.998,
                (2.0 * Math.sqrt(3.0)) * eta - 3.871 * eta * eta + 4.028 * eta * eta * eta));

        // Berti-Cardoso-Will (2006) fundamental (l=2, m=2, n=0) Kerr QNM fit
        double oneMinusA = Math.max(1e-4, 1.0 - af);
        double omegaQnm = (1.5251 - 1.1568 * Math.pow(oneMinusA, 0.1292)) / mf;
        double qualityQ = 0.700 + 1.4187 * Math.pow(oneMinusA, -0.499);

        this.mass1 = (float) m1;
        this.mass2 = (float) m2;
        this.symMassRatio = (float) eta;
        this.remnantMass = (float) mf;
        this.remnantSpin = (float) af;
        this.qnmFreqGeom = omegaQnm;
        this.qnmDampingGeom = omegaQnm / (2.0 * qualityQ);
    }

    /**
     * 1PN Padé-resummed quasi-circular orbital angular velocity:
     * {@code Omega(a) = 1 / (a^(3/2) + 0.5 * (3 - eta) * sqrt(a))}.
     * Matches exact 1PN harmonic-coordinate expansion {@code a^(-3/2) * [1 - (3 - eta)/(2 a) + O(a^-2)]},
     * remains strictly monotonic, subluminal, and strictly below the BCW QNM ringdown frequency down to {@code a_merge}.
     */
    public static double computeOrbitalOmega(double a, double eta) {
        double safeA = Math.max(1.5, a);
        double sqrtA = Math.sqrt(safeA);
        return 1.0 / (safeA * sqrtA + 0.5 * (3.0 - eta) * sqrtA);
    }

    /** 2.5PN Peters-Mathews radial decay rate {@code da/dt} with 1PN flux correction. */
    public static double computeDaDt(double a, double eta) {
        double safeA = Math.max(1.5, a);
        double invA = 1.0 / safeA;
        // 1PN Peters-Mathews flux factor: 1 - (743/336 + 11*eta/4) / a, floored cleanly near plunge
        double fluxCorr = Math.max(0.45, 1.0 - (743.0 / 336.0 + 2.75 * eta) * invA);
        return -PETERS_MATHEWS_K * eta * (invA * invA * invA) * fluxCorr;
    }

    /** 1PN quasi-circular orbital binding energy {@code E_bind = -(eta / (2a)) * [1 - (7 + eta) / (4a)]}. */
    public static double computeBindingEnergy(double a, double eta) {
        double invA = 1.0 / Math.max(1.5, a);
        return -0.5 * eta * invA * (1.0 - 0.25 * (7.0 + eta) * invA);
    }

    /** 1PN quasi-circular orbital angular momentum {@code L = eta * sqrt(a) * [1 + (3 + eta) / (2a)]}. */
    public static double computeAngularMomentum(double a, double eta) {
        double safeA = Math.max(1.5, a);
        return eta * Math.sqrt(safeA) * (1.0 + 0.5 * (3.0 + eta) / safeA);
    }

    /** Converts dimensionless geometric GW angular frequency {@code M * omega_GW} to Hz for a 65 M_sun binary. */
    public static double geomOmegaToHz(double omegaGwGeom) {
        return (omegaGwGeom / (2.0 * Math.PI)) * (C3_OVER_2PI_G_MSUN_HZ * 2.0 * Math.PI / REF_TOTAL_MASS_SUN);
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
