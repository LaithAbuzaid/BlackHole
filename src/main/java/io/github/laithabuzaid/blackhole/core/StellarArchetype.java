package io.github.laithabuzaid.blackhole.core;

/**
 * Physical parameters for the four interactive Tidal Disruption Event (TDE)
 * stellar archetypes in geometric units ({@code G = c = M_BH = 1}).
 *
 * <p>Each archetype pairs realistic photospheric/core temperatures, Roche tidal
 * radius {@code r_t}, stellar radius {@code R_*}, polytropic concentration, and
 * shock-heating gain so penetration factor {@code beta = r_t / r_p} produces
 * distinct partial, full, and deep-plunge disruption dynamics.</p>
 */
public enum StellarArchetype {

    SOLAR_MAIN_SEQ(
            "SOLAR (G2V)",
            "SOLAR G2V",
            5800f, 13200f,
            1.15f, 6.40f,
            0.44f, 1.00f, 1.00f,
            1.04f, 0.92f, 0.78f,
            1.00f, 0.82f, 0.42f
    ),
    RED_SUPERGIANT(
            "RED SUPERGIANT",
            "RED GIANT",
            3400f, 7600f,
            2.05f, 9.40f,
            0.58f, 0.85f, 1.32f,
            1.20f, 0.68f, 0.42f,
            0.98f, 0.44f, 0.28f
    ),
    BLUE_SUPERGIANT(
            "BLUE O-TYPE",
            "BLUE O-TYPE",
            24000f, 36500f,
            0.92f, 5.50f,
            0.36f, 1.48f, 0.94f,
            0.76f, 0.94f, 1.26f,
            0.44f, 0.78f, 1.00f
    ),
    WHITE_DWARF(
            "WHITE DWARF",
            "WHITE DWARF",
            16000f, 30000f,
            0.48f, 3.85f,
            0.56f, 1.68f, 0.76f,
            0.88f, 0.98f, 1.22f,
            0.78f, 0.94f, 1.00f
    );

    public static final float MIN_BETA = 0.60f;
    public static final float MAX_BETA = 4.00f;
    public static final float MIN_INCLINATION_DEG = -65.0f;
    public static final float MAX_INCLINATION_DEG = 65.0f;

    /** Fixed compute substep cap (in wall-equivalent seconds before TDE time scaling) for timelapse safety. */
    public static final double MAX_TDE_SUBSTEP_DT = 0.016;
    /**
     * In-shader substep loop cap (particles stay in GPU registers across all substeps in 1 dispatch,
     * preserving exact 25x timelapse synchronization at 60 FPS with zero dropped simulation time).
     */
    public static final int MAX_TDE_SUBSTEPS_PER_FRAME = 32;

    private static final StellarArchetype[] VALUES = values();

    public final String displayName;
    public final String shortLabel;
    public final float photosphereTempK;
    public final float coreTempK;
    public final float starRadius;
    public final float tidalRadius;
    public final float densityExponent;
    public final float shockGain;
    public final float ribbonScale;
    public final float tintR;
    public final float tintG;
    public final float tintB;
    public final float accentR;
    public final float accentG;
    public final float accentB;

    StellarArchetype(String displayName, String shortLabel,
                     float photosphereTempK, float coreTempK,
                     float starRadius, float tidalRadius,
                     float densityExponent, float shockGain, float ribbonScale,
                     float tintR, float tintG, float tintB,
                     float accentR, float accentG, float accentB) {
        this.displayName = displayName;
        this.shortLabel = shortLabel;
        this.photosphereTempK = photosphereTempK;
        this.coreTempK = coreTempK;
        this.starRadius = starRadius;
        this.tidalRadius = tidalRadius;
        this.densityExponent = densityExponent;
        this.shockGain = shockGain;
        this.ribbonScale = ribbonScale;
        this.tintR = tintR;
        this.tintG = tintG;
        this.tintB = tintB;
        this.accentR = accentR;
        this.accentG = accentG;
        this.accentB = accentB;
    }

    public static StellarArchetype fromIndex(int index) {
        int wrapped = Math.floorMod(index, VALUES.length);
        return VALUES[wrapped];
    }

    public static int count() {
        return VALUES.length;
    }

    public static float clampBeta(float beta) {
        return Math.max(MIN_BETA, Math.min(MAX_BETA, beta));
    }

    public static float clampInclinationDeg(float deg) {
        return Math.max(MIN_INCLINATION_DEG, Math.min(MAX_INCLINATION_DEG, deg));
    }

    /**
     * Exact periapsis radius {@code r_p = r_t / beta}, bounded strictly outside the
     * Kerr outer event horizon {@code r_+} so grazing trajectories never spawn inside the horizon.
     */
    public double computePeriapsis(double beta, double horizonR) {
        double clampedBeta = Math.max(MIN_BETA, Math.min(MAX_BETA, beta));
        return Math.max(horizonR * 1.05, tidalRadius / clampedBeta);
    }

    /** Human-readable physical disruption regime label for the HUD. */
    public static String betaRegimeLabel(float beta) {
        if (beta < 1.05f) {
            return "PARTIAL";
        }
        if (beta < 2.20f) {
            return "FULL TDE";
        }
        return "DEEP PLUNGE";
    }

    /**
     * Computes the number of physics-safe fixed compute substeps for a given simulation
     * delta {@code simDt} so stars never tunnel past periapsis or explode numerically
     * even under {@code 10x–25x} timelapse.
     */
    public static int computeSubstepCount(double simDt) {
        if (simDt <= 0.0) {
            return 0;
        }
        int needed = (int) Math.ceil(simDt / MAX_TDE_SUBSTEP_DT);
        return Math.max(1, Math.min(MAX_TDE_SUBSTEPS_PER_FRAME, needed));
    }

    /** Computes the clamped per-substep delta for a given simulation delta {@code simDt}. */
    public static double computeSubstepDt(double simDt) {
        int steps = computeSubstepCount(simDt);
        if (steps <= 0) {
            return 0.0;
        }
        return Math.min(MAX_TDE_SUBSTEP_DT, simDt / steps);
    }
}
