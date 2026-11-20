package com.mycompany.mavenproject1.core;

/**
 * Mutable simulation parameters, spacetime mode flags, wormhole metric controls,
 * audio controls, and physical constants shared across all subsystems.
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
    public static final float MIN_EXPOSURE = 0.40f;
    public static final float MAX_EXPOSURE = 2.40f;
    public static final float MIN_FOV_DEG = 14.0f;
    public static final float MAX_FOV_DEG = 40.0f;

    // Morris-Thorne / Interstellar Wormhole metric parameter bounds
    public static final float MIN_WORMHOLE_RHO = 0.60f;
    public static final float MAX_WORMHOLE_RHO = 6.00f;
    public static final float MIN_WORMHOLE_A = 0.00f;
    public static final float MAX_WORMHOLE_A = 3.50f;
    public static final float MIN_WORMHOLE_M = 0.20f;
    public static final float MAX_WORMHOLE_M = 4.00f;

    public static final float MIN_VOLUME = 0.00f;
    public static final float MAX_VOLUME = 1.00f;

    public double simTime = 0.0;

    // Spacetime Mode & UI Toggles
    public boolean wormholeMode = false;
    public boolean showUi = true;
    public boolean controlsExpanded = true;
    public boolean lensingEnabled = true;
    public boolean showDisk = true;
    public boolean showJets = true;
    public boolean dopplerEnabled = true;
    public boolean kerrSpinEnabled = true;
    public boolean freeFallMode = false;
    public boolean adaptiveAa4x = true;
    public boolean showGrid = false;
    public boolean orderVisualizer = false;
    public boolean autoOrbit = false;
    public boolean paused = false;
    public boolean vsync = true;
    public boolean audioEnabled = true;

    // Black Hole Physics & Optics Sliders
    public float spinParam = 0.98f;       // Kerr dimensionless spin a* [0.00 .. 0.998]
    public float diskTempScale = 1.00f;   // Blackbody temperature multiplier [0.55 .. 1.65]
    public float diskDensity = 1.00f;     // Accretion optical density [0.25 .. 2.00]
    public float jetPower = 0.85f;        // Relativistic synchrotron jet intensity [0.00 .. 2.00]
    public float bloomStrength = 0.30f;   // Mipmap bloom intensity [0.00 .. 0.80]
    public float exposure = 1.15f;        // HDR exposure [0.40 .. 2.40]
    public float fovDeg = 22.5f;          // Camera half-FOV degrees [14.0 .. 40.0]

    // Morris-Thorne / Interstellar Wormhole Metric Sliders
    public float wormholeRho = 2.60f;     // Throat radius rho
    public float wormholeA = 0.45f;       // Throat half-length a
    public float wormholeM = 1.25f;       // Lensing transition width M_w

    // Procedural Relativistic Audio
    public float audioVolume = 0.60f;     // Master volume [0.00 .. 1.00]

    public float effectiveSpin() {
        return kerrSpinEnabled ? spinParam : 0.0f;
    }

    /** Exact outer Kerr event horizon r_+ = M + sqrt(M^2 - a^2) in geometric units (M = 1). */
    public double outerHorizonRadius() {
        double a = effectiveSpin();
        return 1.0 + Math.sqrt(Math.max(0.0, 1.0 - a * a));
    }

    /** Exact Bardeen-Press-Teukolsky prograde ISCO radius in geometric units (M = 1). */
    public double progradeIscoRadius() {
        double a = Math.max(0.0, Math.min(0.999, effectiveSpin()));
        double z1 = 1.0 + Math.cbrt(1.0 - a * a) * (Math.cbrt(1.0 + a) + Math.cbrt(1.0 - a));
        double z2 = Math.sqrt(3.0 * a * a + z1 * z1);
        return 3.0 + z2 - Math.sqrt(Math.max(0.0, (3.0 - z1) * (3.0 + z1 + 2.0 * z2)));
    }

    public void resetDefaults() {
        autoOrbit = false;
        lensingEnabled = true;
        showDisk = true;
        showJets = true;
        dopplerEnabled = true;
        kerrSpinEnabled = true;
        freeFallMode = false;
        adaptiveAa4x = true;
        showGrid = false;
        orderVisualizer = false;
        paused = false;

        spinParam = 0.98f;
        diskTempScale = 1.00f;
        diskDensity = 1.00f;
        jetPower = 0.85f;
        bloomStrength = 0.30f;
        exposure = 1.15f;
        fovDeg = 22.5f;

        wormholeRho = 2.60f;
        wormholeA = 0.45f;
        wormholeM = 1.25f;
    }
}
