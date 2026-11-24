package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.MorrisThorneMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless scientific regression suite validating V3 physics-to-visualization calculations:
 * Kerr-Schild oblate geometry & Schwarzschild limit, synchronized Binary EOB histories/modes/SI
 * strain scaling, Morris-Thorne isometric embedding & null ray classification, TDE launch orbit
 * consistency, and clean layer defaults.
 */
final class PhysicsVisualizationIntegrationTest {

    @Test
    void kerrOblateCartesianRoundTripAndSchwarzschildLimitAreExact() {
        float[] xyz = new float[3];
        double[] spins = {0.0, 0.5, 0.90, 0.998};
        double[] radii = {1.5, 2.0, 3.8, 7.5};
        double[] thetas = {0.35, Math.PI * 0.5, 2.2};

        for (double a : spins) {
            for (double r : radii) {
                if (r < KerrMetric.outerHorizonRadius(a)) continue;
                for (double th : thetas) {
                    KerrMetric.oblateToCartesian(r, th, 1.15, a, xyz);
                    double rRec = KerrMetric.kerrRadius(xyz[0], xyz[1], xyz[2], a);
                    assertEquals(r, rRec, 1e-5,
                            "kerrRadius(oblateToCartesian) must invert exactly for a=" + a + " r=" + r);
                }
            }
            // Equatorial static limit lapse alpha_stat vanishes right on ergosphere r_ergo = 2M
            assertEquals(0.0, KerrMetric.staticLapse(2.0, 0.0, a), 1e-6);
            assertEquals(0.0, KerrMetric.staticLapse(KerrMetric.outerHorizonRadius(a), 1.0, a), 1e-5);
        }

        // Exact Schwarzschild a = 0 symmetry checks
        assertEquals(2.0, KerrMetric.outerHorizonRadius(0.0), 1e-12);
        assertEquals(6.0, KerrMetric.progradeIscoRadius(0.0), 1e-9);
        assertEquals(6.0, KerrMetric.retrogradeIscoRadius(0.0), 1e-9);
        assertEquals(2.0, KerrMetric.ergosphereRadius(0.0, 0.0), 1e-12);
        assertEquals(0.0, KerrMetric.zamoOmega(4.0, 0.0, 0.0), 1e-12);
    }

    @Test
    void binaryHistoriesModesDetectorAndResetBehavePhysically() {
        BinaryMergerPhysics physics = new BinaryMergerPhysics();

        // 1. Equal-mass non-spinning binary must suppress odd-m (2,1) & (3,3) modes
        physics.reset(BinaryParameters.presetEqualNonSpinning());
        physics.advanceCurrentToSeparation(5.2);
        assertTrue(physics.visHistCount() >= 40, "Synchronous EOB history must populate during evolution");
        assertTrue(physics.powerFrac21 < 1e-4, "Equal non-spinning binary must suppress (2,1) mode");
        assertTrue(physics.powerFrac33 < 1e-4, "Equal non-spinning binary must suppress (3,3) mode");
        assertTrue(physics.powerFrac22 > 0.90, "Dominant quadrupole power fraction expected for equal mass");

        // Monotonic radiated energy & non-decreasing time across ring buffer
        int n = physics.visHistCount();
        int s = physics.visHistStartIdx();
        float prevT = -1f;
        float prevEr = -1f;
        for (int i = 0; i < n; i++) {
            int idx = (s + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
            float t = physics.histGeomTime()[idx];
            float er = physics.histRadiatedEnergy()[idx];
            assertTrue(t >= prevT, "Geometric time history must be non-decreasing");
            assertTrue(er >= prevEr - 1e-6f, "Radiated energy must accumulate monotonically");
            prevT = t;
            prevEr = er;
        }

        // 2. Eccentric GW190412 must excite odd modes and exhibit radial modulation (maxSep > initial after periapsis/apoapsis)
        BinaryParameters pBase = BinaryParameters.presetGw190412();
        BinaryParameters pEcc = new BinaryParameters(
                pBase.mass1Solar, pBase.mass2Solar, pBase.chi1, pBase.chi2,
                11.5, 0.22, pBase.orbitalIncDeg, pBase.observerIncDeg, 500.0,
                BinaryParameters.DetectorPreset.LIGO_HANFORD);
        physics.reset(pEcc);
        physics.advanceCurrentToSeparation(6.2);
        assertTrue(physics.powerFrac21 + physics.powerFrac33 > 1e-3,
                "Unequal-mass GW190412 must activate odd (2,1)+(3,3) modes");
        assertTrue(physics.maxSepSeen - physics.minSepSeen > 1.5f,
                "Eccentricity e=0.22 must produce measurable radial modulation");

        // 3. Luminosity distance scaling & Detector preset switch
        double env500 = Math.hypot(physics.physHPlus, physics.physHCross);
        double fPlusH1 = physics.antennaFPlus;
        BinaryParameters pFar = new BinaryParameters(
                pBase.mass1Solar, pBase.mass2Solar, pBase.chi1, pBase.chi2,
                11.5, 0.22, pBase.orbitalIncDeg, pBase.observerIncDeg, 1000.0,
                BinaryParameters.DetectorPreset.LIGO_HANFORD);
        physics.reset(pFar);
        physics.advanceCurrentToSeparation(6.2);
        double env1000 = Math.hypot(physics.physHPlus, physics.physHCross);
        assertTrue(env1000 < env500 * 0.70,
                "Doubling luminosity distance must reduce SI polarization envelope (~1/D_L)");

        physics.reset(new BinaryParameters(
                pBase.mass1Solar, pBase.mass2Solar, pBase.chi1, pBase.chi2,
                11.5, 0.22, pBase.orbitalIncDeg, pBase.observerIncDeg, 1000.0,
                BinaryParameters.DetectorPreset.VIRGO));
        physics.advanceCurrentToSeparation(6.2);
        double fPlusV1 = physics.antennaFPlus;
        assertNotEquals(fPlusH1, fPlusV1, 1e-3, "Detector preset change must update antenna pattern F+");

        // 4. Merger clears cleanly on reset()
        while (physics.phase() != BinaryMergerPhysics.Phase.RINGDOWN) {
            physics.stepGeometric(0.5, false);
        }
        assertTrue(physics.hasMergerOccurred);
        physics.reset(pBase);
        assertFalse(physics.hasMergerOccurred);
        assertEquals(1, physics.visHistCount(), "reset() must clear prior history leaving only initial t=0 sample");
    }

    @Test
    void morrisThorneEmbeddingIdentityAndDopriRaysHold() {
        double rho = 2.65, a = 0.45, mw = 1.25;

        // Cylindrical throat segment |l| <= a and odd symmetry
        assertEquals(0.0, MorrisThorneMetric.embeddingZ(0.0, a, mw), 1e-12);
        assertEquals(0.3, MorrisThorneMetric.embeddingZ(0.3, a, mw), 1e-12);
        assertEquals(-0.3, MorrisThorneMetric.embeddingZ(-0.3, a, mw), 1e-12);

        double zPos = MorrisThorneMetric.embeddingZ(6.5, a, mw);
        double zNeg = MorrisThorneMetric.embeddingZ(-6.5, a, mw);
        assertEquals(-zPos, zNeg, 1e-10, "Isometric embedding z(l) must be odd symmetric");

        // Isometric arc-length condition: (dr/dl)^2 + (dz/dl)^2 == 1 everywhere
        double h = 1e-5;
        for (double l : new double[]{0.2, 0.8, 2.5, 7.5, -4.0}) {
            double dr = MorrisThorneMetric.dRadiusDl(l, a, mw);
            double dz = (MorrisThorneMetric.embeddingZ(l + h, a, mw) - MorrisThorneMetric.embeddingZ(l - h, a, mw)) / (2.0 * h);
            assertEquals(1.0, dr * dr + dz * dz, 2e-4, "Embedding must satisfy isometric arc-length condition at l=" + l);
        }

        // Critical throat traversal H < rho vs reflection H > rho using DOPRI5(4)
        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegratorConfig cfg = new IntegratorConfig(1e-10, 1e-12, 0.05, 1e-7, 1.0, 50_000, 0.9);
        IntegrationDiagnostics diag = new IntegrationDiagnostics();

        double l0 = 12.0;
        double r0 = MorrisThorneMetric.radius(l0, rho, a, mw);

        for (double ratio : new double[]{0.60, 1.40}) {
            double impH = ratio * rho;
            double[] y = {l0, -Math.sqrt(1.0 - (impH * impH) / (r0 * r0)), 0.0};
            final boolean[] crossed = {false};
            final double[] maxNullErr = {0.0};
            OdeSystem ode = MorrisThorneMetric.nullGeodesicOde(rho, a, mw, impH);
            dopri.integrate(ode, 0.0, y, 36.0, cfg, diag, (lam, st, step) -> {
                double err = Math.abs(MorrisThorneMetric.nullResidual(st[0], st[1], rho, a, mw, impH));
                maxNullErr[0] = Math.max(maxNullErr[0], err);
                if (st[0] < 0.0) crossed[0] = true;
                return Math.abs(st[0]) <= 13.5;
            });
            assertTrue(maxNullErr[0] < 1e-8, "DOPRI5(4) null constraint residual must remain < 1e-8");
            if (ratio < 1.0) {
                assertTrue(crossed[0], "Ray with H < rho must traverse throat into Universe B");
            } else {
                assertFalse(crossed[0], "Ray with H > rho must reflect back into Universe A");
            }
        }
    }

    @Test
    void tdeLaunchOrbitMatchesParticleCentroidAndVisResetRestoresCleanDefaults() {
        double[] out6 = new double[6];
        StellarArchetype arch = StellarArchetype.BLUE_SUPERGIANT;
        TdeInitialConditions.computeLaunchOrbit(arch, 1.25f, -30f, 0.95f, 2, out6);

        int count = 2048;
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        TdeInitialConditions.generateStar(arch, 1.25f, -30f, 0.95f, 2, count, TdeInitialConditions.DEFAULT_SEED, buf);

        double cx = 0, cy = 0, cz = 0, vx = 0, vy = 0, vz = 0;
        for (int i = 0; i < count; i++) {
            int base = i * 8;
            cx += buf.get(base);
            cy += buf.get(base + 1);
            cz += buf.get(base + 2);
            vx += buf.get(base + 4);
            vy += buf.get(base + 5);
            vz += buf.get(base + 6);
        }
        assertEquals(out6[0], cx / count, 0.15);
        assertEquals(out6[1], cy / count, 0.15);
        assertEquals(out6[2], cz / count, 0.15);
        assertEquals(out6[3], vx / count, 0.05);
        assertEquals(out6[4], vy / count, 0.05);
        assertEquals(out6[5], vz / count, 0.05);

        // Visualization layer defaults reset cleanly
        SimulationState state = new SimulationState();
        state.kerrDiskVisMode = SimulationState.DISK_VIS_TEMP;
        state.binGwModeFilter = SimulationState.BIN_MODE_33;
        state.tdeHydroVisMode = SimulationState.TDE_HYDRO_VIS_SHOCK;
        state.visKerrGeodesics = false;
        state.showGwReference = true;
        state.resetVisualizationSettings();

        assertEquals(SimulationState.DISK_VIS_BLACKBODY, state.kerrDiskVisMode);
        assertEquals(SimulationState.BIN_MODE_ALL, state.binGwModeFilter);
        assertEquals(SimulationState.TDE_HYDRO_VIS_OFF, state.tdeHydroVisMode);
        assertTrue(state.visKerrGeodesics);
        assertFalse(state.showGwReference);
    }
}
