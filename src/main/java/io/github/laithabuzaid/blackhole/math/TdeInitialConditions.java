package io.github.laithabuzaid.blackhole.math;

import io.github.laithabuzaid.blackhole.core.StellarArchetype;

import java.nio.FloatBuffer;

/**
 * Headless, deterministic, explicitly seedable polytropic star generator for Tidal Disruption Events.
 *
 * <p>Generates a centrally concentrated stellar sphere with radial distribution
 * {@code r = R_* * u^p} where {@code p >= 1/3} guarantees monotonically non-increasing 3D density
 * {@code n(r) ~ r^(1/p - 3)} (uniform at {@code p = 1/3}, centrally concentrated polytrope for {@code p > 1/3}).</p>
 */
public final class TdeInitialConditions {

    public static final long DEFAULT_SEED = 0x5DEECE66DL;

    private TdeInitialConditions() {
    }

    /**
     * Generates {@code particleCount} particles (8 floats per particle: {@code pos.xyz, tempK, vel.xyz, massWeight})
     * into {@code outBuf} (position reset to 0 and flipped on return) using a deterministic LCG seeded by {@code seed}.
     *
     * @return next RNG state for chaining multiple deterministic launches.
     */
    public static long generateStar(
            StellarArchetype archetype,
            float penetrationBeta,
            float inclinationDeg,
            float spinParam,
            int launchVariant,
            int particleCount,
            long seed,
            FloatBuffer outBuf
    ) {
        double rPlus = KerrMetric.outerHorizonRadius(spinParam);
        double rPeri = archetype.computePeriapsis(penetrationBeta, rPlus);
        // Standard Paczynski-Wiita radius denominator r_PW = r - r_s (r_s = 2M for Schwarzschild)
        double rPwEff = 2.0;

        double startR = Math.max(15.5, rPeri * 3.1 + archetype.starRadius * 3.2 + (launchVariant % 3) * 1.4);
        double azimuth = 0.52 + (launchVariant % 5) * 1.18;
        double incRad = Math.toRadians(Math.max(-65.0, Math.min(65.0, inclinationDeg)));

        double rxHat = Math.cos(azimuth);
        double rzHat = Math.sin(azimuth);
        double txHat = -rzHat;
        double tzHat = rxHat;

        double cx = startR * rxHat;
        double cy = 0.0;
        double cz = startR * rzHat;

        // Parabolic / marginally bound trajectory in Paczynski-Wiita potential V(r) = -1 / (r - rPwEff)
        // Marginally bound capture radius is r_mb = 4M (L_crit = 4M); keeping vPeriSq uncapped up to
        // the compute shader speed ceiling (0.90) ensures r_p > 4.25M orbits whip around periapsis
        // into genuine Hills bound/unbound tidal streams rather than plunging prematurely.
        double rPeriSafe = Math.max(rPwEff + 0.45, rPeri);
        double vPeriSq = Math.min(0.90, (2.0 / Math.max(0.35, rPeriSafe - rPwEff)) * 0.992);
        double L = rPeriSafe * Math.sqrt(vPeriSq);

        double vStartSq = Math.max(0.015, (2.0 / Math.max(0.45, startR - rPwEff)) * 0.992);
        double vTan = Math.min(Math.sqrt(vStartSq) * 0.90, L / startR);
        double vRadIn = Math.sqrt(Math.max(0.008, vStartSq - vTan * vTan));

        double cosInc = Math.cos(incRad);
        double sinInc = Math.sin(incRad);
        double vBaseX = -vRadIn * rxHat + vTan * cosInc * txHat;
        double vBaseY = vTan * sinInc;
        double vBaseZ = -vRadIn * rzHat + vTan * cosInc * tzHat;

        // Enforce p >= 1/3 so 3D density n(r) ~ r^(1/p - 3) is NEVER hollow at the core
        double p = Math.max(1.0 / 3.0, archetype.densityExponent);
        double starR = archetype.starRadius;
        double baseTemp = archetype.photosphereTempK;
        double coreTemp = archetype.coreTempK;
        double velDisp = 0.018 * Math.sqrt(Math.max(0.35, 1.0 / starR));

        long rng = (seed == 0L) ? DEFAULT_SEED : seed;
        outBuf.clear();
        for (int i = 0; i < particleCount; i++) {
            rng = nextRng(rng);
            double uRad = toUnitDouble(rng);
            rng = nextRng(rng);
            double cosTheta = 2.0 * toUnitDouble(rng) - 1.0;
            rng = nextRng(rng);
            double phi = 2.0 * Math.PI * toUnitDouble(rng);
            double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));

            // Centrally concentrated polytrope radial sample
            double radFrac = Math.pow(Math.max(1e-7, uRad), p);
            double rad = starR * radFrac;
            double ox = rad * sinTheta * Math.cos(phi);
            double oy = rad * cosTheta;
            double oz = rad * sinTheta * Math.sin(phi);

            // Core-to-envelope temperature & mass weight
            double coreWeight = Math.max(0.0, Math.min(1.0, 1.0 - radFrac));
            double massWeight = 0.35 + 0.65 * coreWeight * coreWeight;
            double temp = baseTemp + (coreTemp - baseTemp) * Math.pow(coreWeight, 1.45);

            // Small isotropic thermal/hydrostatic velocity dispersion
            rng = nextRng(rng);
            double dvx = (toUnitDouble(rng) - 0.5) * 2.0 * velDisp;
            rng = nextRng(rng);
            double dvy = (toUnitDouble(rng) - 0.5) * 2.0 * velDisp;
            rng = nextRng(rng);
            double dvz = (toUnitDouble(rng) - 0.5) * 2.0 * velDisp;

            // Layout matches tidal_disruption.comp Particle struct: posMass(x,y,z,mass) + velTemp(vx,vy,vz,tempK)
            outBuf.put((float) (cx + ox));
            outBuf.put((float) (cy + oy));
            outBuf.put((float) (cz + oz));
            outBuf.put((float) massWeight);
            outBuf.put((float) (vBaseX + dvx));
            outBuf.put((float) (vBaseY + dvy));
            outBuf.put((float) (vBaseZ + dvz));
            outBuf.put((float) temp);
        }
        outBuf.flip();
        return rng;
    }

    private static long nextRng(long state) {
        return (state * 6364136223846793005L) + 1442695040888963407L;
    }

    private static double toUnitDouble(long state) {
        return ((state >>> 11) & 0x1FFFFFL) / (double) 0x200000L;
    }
}
