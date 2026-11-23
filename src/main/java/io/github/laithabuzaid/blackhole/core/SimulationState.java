package io.github.laithabuzaid.blackhole.core;

import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;

/**
 * Mutable simulation parameters, spacetime mode flags, Doran free-fall plunge
 * state & telemetry, wormhole metric controls, audio controls, and physical
 * constants shared across all subsystems.
 */
public final class SimulationState {

    public static final float MIN_SPIN = 0.00f;
    public static final float MAX_SPIN = 0.998f;
    public static final float MIN_TEMP_SCALE = 0.55f;
    public static final float MAX_TEMP_SCALE = 1.65f;
    public static final float MIN_DENSITY = 0.25f;
    public static final float MAX_DENSITY = 2.00f;
    public static final float MIN_JET_POWER = 0.00f;
    public static final float MAX_JET_POWER = 2.00f;
    public static final float MIN_BLOOM = 0.00f;
    public static final float MAX_BLOOM = 0.80f;
    public static final float MIN_ANAMORPHIC = 0.00f;
    public static final float MAX_ANAMORPHIC = 1.00f;
    public static final float MIN_EXPOSURE = 0.40f;
    public static final float MAX_EXPOSURE = 2.40f;
    public static final float MIN_FOV_DEG = 14.0f;
    public static final float MAX_FOV_DEG = 40.0f;

    public static final float TOAST_DURATION = 2.2f;

    // Morris-Thorne / Interstellar Wormhole metric parameter bounds
    public static final float MIN_WORMHOLE_RHO = 0.60f;
    public static final float MAX_WORMHOLE_RHO = 6.00f;
    public static final float MIN_WORMHOLE_A = 0.00f;
    public static final float MAX_WORMHOLE_A = 3.50f;
    public static final float MIN_WORMHOLE_M = 0.20f;
    public static final float MAX_WORMHOLE_M = 4.00f;

    // Binary Black Hole Merger & Gravitational Wave parameter bounds
    public static final float MIN_MASS_RATIO = 0.10f;
    public static final float MAX_MASS_RATIO = 1.00f;
    public static final float MIN_BIN_COMP_MASS = 5.0f;
    public static final float MAX_BIN_COMP_MASS = 100.0f;
    public static final float MIN_BIN_SPIN = -0.95f;
    public static final float MAX_BIN_SPIN = 0.95f;
    public static final float MIN_BIN_INIT_SEP = 7.0f;
    public static final float MAX_BIN_INIT_SEP = 16.0f;
    public static final float MIN_BIN_ECC = 0.00f;
    public static final float MAX_BIN_ECC = 0.30f;
    public static final float MIN_BIN_ORB_INC = -60.0f;
    public static final float MAX_BIN_ORB_INC = 60.0f;
    public static final float MIN_BIN_OBS_INC = 0.0f;
    public static final float MAX_BIN_OBS_INC = 180.0f;
    public static final float MIN_BIN_DIST_MPC = 50.0f;
    public static final float MAX_BIN_DIST_MPC = 2000.0f;
    public static final float MIN_BIN_TIME_SCALE = 0.20f;
    public static final float MAX_BIN_TIME_SCALE = 2.50f;

    // Global Physics-Safe Timelapse Bounds & Discrete Quick-Step Presets
    public static final float MIN_TIME_SCALE = 0.05f;
    public static final float MAX_TIME_SCALE = 25.00f;
    public static final float[] TIME_SCALE_PRESETS = {
            0.10f, 0.25f, 0.50f, 1.00f, 2.00f, 5.00f, 10.00f, 25.00f
    };

    // 3-State HUD Visibility Cycle ([H] Key)
    public static final int HUD_CLEAN = 0;
    public static final int HUD_COMPACT = 1;
    public static final int HUD_FULL = 2;

    // 4-Tab Right Observatory Console IDs
    public static final int TAB_SCENE = 0;
    public static final int TAB_PHYSICS = 1;
    public static final int TAB_TDE_LAB = 2;
    public static final int TAB_OPTICS = 3;

    // Plunge & Audio bounds
    public static final float MIN_PLUNGE_SPEED = 0.10f;
    public static final float MAX_PLUNGE_SPEED = 2.00f;
    public static final float MIN_VOLUME = 0.00f;
    public static final float MAX_VOLUME = 1.00f;

    // Plunge Zone IDs
    public static final int ZONE_EXTERIOR = 0;
    public static final int ZONE_PHOTON_SPHERE = 1;
    public static final int ZONE_EVENT_HORIZON = 2;
    public static final int ZONE_INTERIOR = 3;
    public static final int ZONE_CAUCHY_HORIZON = 4;
    public static final int ZONE_SINGULARITY = 5;

    public double simTime = 0.0;

    // Global Multi-Mode Simulation Time Scale (0.05x Ultra Slow-Mo .. 25.0x Timelapse)
    public float timeScale = 1.00f;

    // Spacetime Mode & UI Toggles ("Cinema First, Console Second" startup)
    public boolean wormholeMode = false;
    public boolean binaryMode = false;
    public boolean binaryGwOverlay = false;
    public boolean binaryAutoLoop = true;
    public int hudMode = HUD_CLEAN;
    public boolean showUi = false;
    public int activeConsoleTab = TAB_SCENE;
    public boolean controlsExpanded = true;
    public boolean attractTourActive = true;
    public boolean lensingEnabled = true;
    public boolean showDisk = true;
    public boolean showJets = true;
    public boolean dopplerEnabled = true;
    public boolean kerrSpinEnabled = true;
    public boolean freeFallMode = false;
    public boolean adaptiveAa4x = true;
    public boolean anamorphicEnabled = true;
    public boolean showGrid = false;
    public boolean orderVisualizer = false;
    public boolean ehtPolarization = false;
    public boolean ehtRadioPalette = false;
    public boolean showReticle = false;
    public boolean fullscreen = false;
    public boolean autoOrbit = false;
    public boolean paused = false;
    public boolean vsync = true;
    public boolean audioEnabled = true;

    // Minimalist Cinema Pill & Toast Notification State (zero per-frame heap allocations)
    public float cinemaHintAge = 0.0f;
    private final Object toastLock = new Object();
    private final StringBuilder toastText = new StringBuilder(96);
    private float toastRemaining = 0.0f;

    // Interactive Doran / Kerr-Schild Free-Fall Plunge Simulator State
    public boolean plungeActive = false;
    public boolean plungeEnded = false;
    public float plungeSpeed = 1.00f;         // Proper-time plunge speed multiplier [0.10 .. 2.00]
    public double plungeProperTime = 0.0;     // Observer proper time tau (M units)
    public double plungeCoordTime = 0.0;      // Distant observer time t_inf (diverges at r_+)
    public boolean plungeHorizonCrossed = false;
    public float plungeVelocityC = 0.0f;      // Infall velocity beta_in = v/c (1.0 at r_+)
    public float plungeTidalGrad = 0.0f;      // Radial tidal gradient 2GM/r^3
    public int plungeZone = ZONE_EXTERIOR;
    public float cauchyFlashIntensity = 0.0f; // Kerr inner Cauchy horizon (r_-) mass-inflation flash [0..1]
    public float spaghettification = 0.0f;    // Schwarzschild tidal optical stretching [0..1]
    public float singularityFade = 0.0f;      // Smooth singularity impact fade [0..1]

    // Black Hole Physics & Optics Sliders
    public float spinParam = 0.98f;       // Kerr dimensionless spin a* [0.00 .. 0.998]
    public float diskTempScale = 1.00f;   // Blackbody temperature multiplier [0.55 .. 1.65]
    public float diskDensity = 1.00f;     // Accretion optical density [0.25 .. 2.00]
    public float jetPower = 0.85f;        // Relativistic synchrotron jet intensity [0.00 .. 2.00]
    public float bloomStrength = 0.30f;      // Mipmap bloom intensity [0.00 .. 0.80]
    public float anamorphicStrength = 0.32f; // Horizontal anamorphic glare [0.00 .. 1.00]
    public float exposure = 1.15f;           // HDR exposure [0.40 .. 2.40]
    public float fovDeg = 22.5f;          // Camera half-FOV degrees [14.0 .. 40.0]

    // Morris-Thorne / Interstellar Wormhole Metric Sliders
    public float wormholeRho = 2.60f;     // Throat radius rho
    public float wormholeA = 0.45f;       // Throat half-length a
    public float wormholeM = 1.25f;       // Lensing transition width M_w

    // Binary Black Hole Merger Sliders (v3.0 EOB + Detector Parameters)
    public float binaryMass1Solar = 35.6f;     // Primary BH source-frame mass M1 [M_sun]
    public float binaryMass2Solar = 30.6f;     // Secondary BH source-frame mass M2 [M_sun]
    public float binaryMassRatio = 30.6f / 35.6f; // Derived q = min(M1,M2) / max(M1,M2)
    public float binaryChi1 = 0.02f;           // Primary dimensionless aligned spin chi_1 [-0.95 .. +0.95]
    public float binaryChi2 = -0.04f;          // Secondary dimensionless aligned spin chi_2 [-0.95 .. +0.95]
    public float binaryInitialSep = 10.0f;     // Initial EOB orbital separation r_0 [7 .. 16 M]
    public float binaryEccentricity = 0.00f;   // Initial orbital eccentricity e_0 [0.00 .. 0.30]
    public float binaryOrbitalIncDeg = 0.0f;   // 3D orbital plane inclination [-60 .. +60 deg]
    public float binaryObserverIncDeg = 30.0f; // Observer inclination iota relative to L_hat [0 .. 180 deg]
    public float binaryDistanceMpc = 440.0f;   // Luminosity distance D_L [50 .. 2000 Mpc]
    public BinaryParameters.DetectorPreset binaryDetector = BinaryParameters.DetectorPreset.LIGO_HANFORD;
    public boolean binaryParamsDirty = false;  // Set when any binary parameter changes -> triggers EOB recalc
    public float binaryTimeScale = 1.00f;      // Retained for compatibility; global timeScale drives all modes

    // Interactive TDE Lab Parameters
    public int tdeArchetypeIndex = 0;          // 0=Solar, 1=Red Giant, 2=Blue O-Type, 3=White Dwarf
    public float tdeBeta = 1.55f;              // Penetration factor beta = r_t / r_p [0.60 .. 4.00]
    public float tdeInclinationDeg = 22.0f;    // Launch orbital inclination [-65 .. +65 deg]
    public float tdeHorizonSurge = 0.0f;       // Live horizon accretion surge [0..1]
    public boolean tdeHydroEnabled = true;     // true = 64^3 GPU grid hydro pressure + shock viscosity; false = pure gravity
    public long tdeSeed = TdeInitialConditions.DEFAULT_SEED;

    // Numerical Integrator & Scientific Diagnostics (v2.0)
    public static final int INTEGRATOR_RK4 = 0;
    public static final int INTEGRATOR_DOPRI54 = 1;
    public static final int DIAG_OFF = 0;
    public static final int DIAG_HAMILTONIAN_ERR = 1;
    public static final int DIAG_STEP_DENSITY = 2;
    public static final int DIAG_ANALYTICAL_OVERLAY = 3;

    public int integratorMode = INTEGRATOR_RK4;      // 0 = VGPR-opt RK4, 1 = Adaptive Dormand-Prince 5(4)
    public float integratorRtol = 2.5e-4f;           // GPU DOPRI5(4) relative tolerance [1e-5 .. 5e-3]
    public int scientificDiagMode = DIAG_OFF;        // [F3] cycle: Off -> |H| Error -> Step Density -> Analytical Curves

    // Procedural Relativistic Audio
    public float audioVolume = 0.60f;     // Master volume [0.00 .. 1.00]

    /** Builds a validated {@link BinaryParameters} snapshot from current state sliders. */
    public BinaryParameters buildBinaryParameters() {
        binaryMassRatio = Math.min(binaryMass1Solar, binaryMass2Solar)
                / Math.max(1e-3f, Math.max(binaryMass1Solar, binaryMass2Solar));
        return new BinaryParameters(
                binaryMass1Solar, binaryMass2Solar,
                binaryChi1, binaryChi2,
                binaryInitialSep, binaryEccentricity,
                binaryOrbitalIncDeg, binaryObserverIncDeg,
                binaryDistanceMpc, binaryDetector
        );
    }

    /** Copies a preset {@link BinaryParameters} into all UI state fields and marks dynamics dirty. */
    public void applyBinaryParameters(BinaryParameters p) {
        this.binaryMass1Solar = (float) p.mass1Solar;
        this.binaryMass2Solar = (float) p.mass2Solar;
        this.binaryMassRatio = (float) p.massRatioQ;
        this.binaryChi1 = (float) p.chi1;
        this.binaryChi2 = (float) p.chi2;
        this.binaryInitialSep = (float) p.initialSepM;
        this.binaryEccentricity = (float) p.eccentricity;
        this.binaryOrbitalIncDeg = (float) p.orbitalIncDeg;
        this.binaryObserverIncDeg = (float) p.observerIncDeg;
        this.binaryDistanceMpc = (float) p.luminosityDistMpc;
        this.binaryDetector = p.detectorPreset;
        this.binaryParamsDirty = true;
    }

    public StellarArchetype currentTdeArchetype() {
        return StellarArchetype.fromIndex(tdeArchetypeIndex);
    }

    public void cycleTdeArchetype() {
        tdeArchetypeIndex = (tdeArchetypeIndex + 1) % StellarArchetype.count();
    }

    public void setHudMode(int mode) {
        this.hudMode = Math.max(HUD_CLEAN, Math.min(HUD_FULL, mode));
        this.showUi = (this.hudMode == HUD_FULL);
        if (this.hudMode == HUD_CLEAN) {
            this.cinemaHintAge = 0.0f;
        }
    }

    public void cycleHudMode() {
        setHudMode((hudMode + 1) % 3);
    }

    public boolean isHudClean() {
        return hudMode == HUD_CLEAN;
    }

    public boolean isHudCompact() {
        return hudMode == HUD_COMPACT;
    }

    public boolean isHudFull() {
        return hudMode == HUD_FULL;
    }

    public void setTimeScale(float scale) {
        this.timeScale = Math.max(MIN_TIME_SCALE, Math.min(MAX_TIME_SCALE, scale));
    }

    public void stepTimeScaleUp() {
        for (int i = 0; i < TIME_SCALE_PRESETS.length; i++) {
            if (TIME_SCALE_PRESETS[i] > timeScale + 1e-3f) {
                timeScale = TIME_SCALE_PRESETS[i];
                return;
            }
        }
        timeScale = MAX_TIME_SCALE;
    }

    public void stepTimeScaleDown() {
        for (int i = TIME_SCALE_PRESETS.length - 1; i >= 0; i--) {
            if (TIME_SCALE_PRESETS[i] < timeScale - 1e-3f) {
                timeScale = TIME_SCALE_PRESETS[i];
                return;
            }
        }
        timeScale = MIN_TIME_SCALE;
    }

    /** Returns effective simulation delta (0 when paused, preserving chosen {@link #timeScale}). */
    public double effectiveSimDt(double wallDt) {
        if (paused || wallDt <= 0.0) {
            return 0.0;
        }
        return wallDt * timeScale;
    }

    public float effectiveSpin() {
        return kerrSpinEnabled ? spinParam : 0.0f;
    }

    /** Exact outer Kerr event horizon r_+ = M + sqrt(M^2 - a^2) in geometric units (M = 1). */
    public double outerHorizonRadius() {
        return KerrMetric.outerHorizonRadius(effectiveSpin());
    }

    /** Exact inner Kerr Cauchy horizon r_- = M - sqrt(M^2 - a^2) in geometric units (M = 1). */
    public double innerHorizonRadius() {
        return KerrMetric.innerHorizonRadius(effectiveSpin());
    }

    /** Exact Bardeen-Press-Teukolsky prograde ISCO radius in geometric units (M = 1). */
    public double progradeIscoRadius() {
        return KerrMetric.progradeIscoRadius(effectiveSpin());
    }

    public void cycleScientificDiagMode() {
        scientificDiagMode = (scientificDiagMode + 1) % 4;
    }

    public String scientificDiagLabel() {
        return switch (scientificDiagMode) {
            case DIAG_HAMILTONIAN_ERR -> "HAMILTONIAN |H| ERROR";
            case DIAG_STEP_DENSITY -> "INTEGRATOR STEPS";
            case DIAG_ANALYTICAL_OVERLAY -> "ANALYTICAL CURVES";
            default -> "OFF";
        };
    }

    public String plungeZoneName() {
        return switch (plungeZone) {
            case ZONE_PHOTON_SPHERE -> "PHOTON SPHERE";
            case ZONE_EVENT_HORIZON -> "EVENT HORIZON (r+)";
            case ZONE_INTERIOR -> "INTERIOR";
            case ZONE_CAUCHY_HORIZON -> "CAUCHY HORIZON (r-)";
            case ZONE_SINGULARITY -> "SINGULARITY";
            default -> "EXTERIOR";
        };
    }

    public void clearPlungeTelemetry() {
        plungeActive = false;
        plungeEnded = false;
        plungeProperTime = 0.0;
        plungeCoordTime = 0.0;
        plungeHorizonCrossed = false;
        plungeVelocityC = 0.0f;
        plungeTidalGrad = 0.0f;
        plungeZone = ZONE_EXTERIOR;
        cauchyFlashIntensity = 0.0f;
        spaghettification = 0.0f;
        singularityFade = 0.0f;
    }

    public void showToast(CharSequence message) {
        synchronized (toastLock) {
            toastText.setLength(0);
            toastText.append(message);
            toastRemaining = TOAST_DURATION;
        }
    }

    public void showSavedToast(CharSequence relativePath) {
        synchronized (toastLock) {
            toastText.setLength(0);
            toastText.append("SAVED: ").append(relativePath);
            toastRemaining = TOAST_DURATION;
        }
    }

    public void clearToast() {
        synchronized (toastLock) {
            toastText.setLength(0);
            toastRemaining = 0.0f;
        }
    }

    public void tickUiTimers(double dt) {
        if (hudMode == HUD_CLEAN) {
            cinemaHintAge = Math.min(20.0f, cinemaHintAge + (float) dt);
        }
        synchronized (toastLock) {
            if (toastRemaining > 0.0f) {
                toastRemaining = Math.max(0.0f, toastRemaining - (float) dt);
            }
        }
    }

    /** Copies active toast into {@code dst} without allocating and returns fade alpha [0..1]. */
    public float readActiveToast(StringBuilder dst) {
        synchronized (toastLock) {
            if (toastRemaining <= 0.0f || toastText.length() == 0) {
                dst.setLength(0);
                return 0.0f;
            }
            dst.setLength(0);
            dst.append(toastText);
            float elapsed = TOAST_DURATION - toastRemaining;
            if (elapsed < 0.22f) {
                return elapsed / 0.22f;
            }
            if (toastRemaining < 0.48f) {
                return toastRemaining / 0.48f;
            }
            return 1.0f;
        }
    }

    public void resetDefaults() {
        clearPlungeTelemetry();
        autoOrbit = false;
        attractTourActive = false;
        lensingEnabled = true;
        showDisk = true;
        showJets = true;
        dopplerEnabled = true;
        kerrSpinEnabled = true;
        freeFallMode = false;
        adaptiveAa4x = true;
        anamorphicEnabled = true;
        showGrid = false;
        orderVisualizer = false;
        ehtPolarization = false;
        ehtRadioPalette = false;
        showReticle = false;
        paused = false;

        plungeSpeed = 1.00f;
        spinParam = 0.98f;
        diskTempScale = 1.00f;
        diskDensity = 1.00f;
        jetPower = 0.85f;
        bloomStrength = 0.30f;
        anamorphicStrength = 0.32f;
        exposure = 1.15f;
        fovDeg = 22.5f;

        wormholeRho = 2.60f;
        wormholeA = 0.45f;
        wormholeM = 1.25f;

        applyBinaryParameters(BinaryParameters.presetGw150914());
        binaryParamsDirty = false;
        binaryTimeScale = 1.00f;
        binaryGwOverlay = false;
        binaryAutoLoop = true;

        timeScale = 1.00f;
        tdeArchetypeIndex = 0;
        tdeBeta = 1.55f;
        tdeInclinationDeg = 22.0f;
        tdeHorizonSurge = 0.0f;
        tdeHydroEnabled = true;
        tdeSeed = TdeInitialConditions.DEFAULT_SEED;
        integratorMode = INTEGRATOR_RK4;
        integratorRtol = 2.5e-4f;
        scientificDiagMode = DIAG_OFF;
    }
}
