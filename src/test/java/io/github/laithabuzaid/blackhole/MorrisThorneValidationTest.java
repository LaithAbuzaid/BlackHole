package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.MorrisThorneMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.math.PlanckBlackbody;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scientific validation of Morris-Thorne wormhole geodesics, CIE Planck blackbody colorimetry,
 * IEC 61966-2-1 sRGB transfer functions, and deterministic centrally concentrated TDE star profiles.
 */
class MorrisThorneValidationTest {

    @Test
    void dnegShapeFunctionDerivativesMatchFiniteDifferencesAndAreC1Continuous() {
        double rho = 1.6;
        double a = 0.5;
        double m = 0.9;
        double eps = 1e-6;
        double[] sampleL = {-4.0, -0.8, -0.2, 0.0, 0.2, 0.5, 0.5001, 1.5, 6.0};
        for (double l : sampleL) {
            double fd = (MorrisThorneMetric.radius(l + eps, rho, a, m)
                    - MorrisThorneMetric.radius(l - eps, rho, a, m)) / (2.0 * eps);
            double analytic = MorrisThorneMetric.dRadiusDl(l, a, m);
            assertEquals(fd, analytic, 1e-5, "dr/dl mismatch at l=" + l);
        }
        // Inside throat |l| <= a, r(l) == rho and r'(l) == 0
        assertEquals(rho, MorrisThorneMetric.radius(0.0, rho, a, m), 1e-14);
        assertEquals(0.0, MorrisThorneMetric.dRadiusDl(0.3, a, m), 1e-14);
    }

    @Test
    void ellisWormholeGeodesicMatchesExactCompleteEllipticIntegral() {
        double rho = 2.0;
        double h = 1.2; // |H| < rho -> passes through throat from +l0 to -l0
        double lStart = 800.0;
        double rStart = Math.sqrt(lStart * lStart + rho * rho);
        double vlStart = -Math.sqrt(1.0 - (h * h) / (rStart * rStart));
        double[] state = {lStart, vlStart, 0.0};

        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        OdeSystem ode = MorrisThorneMetric.ellisGeodesicOde(rho, h);
        IntegratorConfig cfg = new IntegratorConfig(1e-11, 1e-13, 0.1, 1e-7, 5.0, 50_000, 0.9);

        dopri.integrate(ode, 0.0, state, 2500.0, cfg, diag, (lam, y, step) -> y[0] > -lStart);

        // Account for finite-distance tail 2 * integral_{lStart}^{inf} (H / r^2) dl ~= 2 * H / lStart
        double finiteTailCorrection = 2.0 * h / lStart;
        double numericInf = state[2] + finiteTailCorrection;
        double exact = MorrisThorneMetric.ellisAnalyticalThroughAngle(h, rho);
        assertEquals(exact, numericInf, 1e-5,
                "Through-throat Ellis deflection must match 2*K(H/rho)");
    }

    @Test
    void dnegGeodesicConservesNullConstraintAndUniverseBHasZeroHouseholderReflection() {
        double rho = 1.5, a = 0.4, m = 0.85;
        double l0 = 12.0;
        double r0 = MorrisThorneMetric.radius(l0, rho, a, m);
        double h = 0.9 * rho; // passes through throat into Universe B
        double vl0 = -Math.sqrt(1.0 - (h * h) / (r0 * r0));
        double[] state = {l0, vl0, 0.0};

        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        OdeSystem ode = MorrisThorneMetric.nullGeodesicOde(rho, a, m, h);
        IntegratorConfig cfg = new IntegratorConfig(1e-10, 1e-12, 0.05, 1e-7, 1.0, 50_000, 0.9);
        final double[] maxNullErr = {0.0};

        dopri.integrate(ode, 0.0, state, 80.0, cfg, diag, (lam, y, step) -> {
            double err = Math.abs(MorrisThorneMetric.nullResidual(y[0], y[1], rho, a, m, h));
            if (err > maxNullErr[0]) maxNullErr[0] = err;
            return y[0] > -20.0;
        });

        assertTrue(state[0] <= -20.0, "Ray with H < rho must traverse throat to Universe B");
        assertTrue(maxNullErr[0] < 1e-8, "Wormhole null constraint drift too large: " + maxNullErr[0]);

        // Camera-invariance test: a ray traversing the same S^2 point (0,0,1) from two different
        // camera positions (eR1 = (1,0,0) at phi=pi/2 vs eR2 = (cos45,0,sin45) at phi=pi/4)
        // must map to the EXACT same Universe B skybox vector (0, 0, 1) — catching Householder reflection bugs!
        double[] dir1 = new double[3];
        double[] dir2 = new double[3];
        MorrisThorneMetric.sphericalExitDirection(Math.PI * 0.5, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, dir1);
        double s = Math.sqrt(0.5);
        MorrisThorneMetric.sphericalExitDirection(Math.PI * 0.25, s, 0.0, s, -s, 0.0, s, dir2);
        assertEquals(dir1[0], dir2[0], 1e-14);
        assertEquals(dir1[1], dir2[1], 1e-14);
        assertEquals(dir1[2], dir2[2], 1e-14);
        assertEquals(1.0, dir2[2], 1e-14);
    }

    @Test
    void planckBlackbodyAndIecSrgbTransferFunctionsArePhysicallyAccurate() {
        double[] c2000 = new double[4];
        double[] c6500 = new double[4];
        double[] c28000 = new double[4];
        PlanckBlackbody.blackbodyLinearRgb(2000.0, c2000);
        PlanckBlackbody.blackbodyLinearRgb(6500.0, c6500);
        PlanckBlackbody.blackbodyLinearRgb(28000.0, c28000);

        // Cool blackbody is red-dominated; D65 (~6500K) is near-neutral white; 28000K is blue-dominated
        assertTrue(c2000[0] > c2000[1] && c2000[1] > c2000[2], "2000K must be warm orange-red");
        assertTrue(c6500[0] > 0.85 && c6500[1] > 0.85 && c6500[2] > 0.85, "6500K must be near white");
        assertTrue(c28000[2] > c28000[1] && c28000[1] > c28000[0], "28000K must be hot blue-white");
        assertTrue(c2000[3] < c6500[3], "Visible-band attenuation must diminish cool redshifted plasma");

        // Exact IEC 61966-2-1 round-trip
        for (double lin : new double[]{0.0, 0.0005, 0.0031308, 0.02, 0.18, 0.5, 1.0}) {
            double srgb = PlanckBlackbody.linearToSrgb(lin);
            double back = PlanckBlackbody.srgbToLinear(srgb);
            assertEquals(lin, back, 1e-12, "IEC sRGB EOTF/OETF round-trip failed at " + lin);
        }
        // Verify deep-space dark toe is NOT lifted 3.3x like naive pow(x, 1/2.2)
        assertEquals(12.92 * 0.001, PlanckBlackbody.linearToSrgb(0.001), 1e-12);
    }

    @Test
    void tdeInitialConditionsAreCentrallyConcentratedAndSeedDeterministic() {
        int count = 4096;
        FloatBuffer bufA = FloatBuffer.allocate(count * 8);
        FloatBuffer bufB = FloatBuffer.allocate(count * 8);

        for (StellarArchetype arch : StellarArchetype.values()) {
            assertTrue(arch.densityExponent >= 1.0f / 3.0f,
                    arch.displayName + " must have densityExponent >= 1/3 to avoid a hollow core");
        }

        long seed1 = TdeInitialConditions.generateStar(
                StellarArchetype.WHITE_DWARF, 2.0f, 15.0f, 0.94f, 0, count, 123456789L, bufA);
        long seed2 = TdeInitialConditions.generateStar(
                StellarArchetype.WHITE_DWARF, 2.0f, 15.0f, 0.94f, 0, count, 123456789L, bufB);
        assertEquals(seed1, seed2, "Identical seeds must yield identical next RNG state");
        for (int i = 0; i < count * 8; i++) {
            assertEquals(bufA.get(i), bufB.get(i), 0.0f, "Particle buffer must be bit-identical for identical seed");
        }

        // Compute center of mass and check inner-half density strictly exceeds outer-half density
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < count; i++) {
            cx += bufA.get(i * 8);
            cy += bufA.get(i * 8 + 1);
            cz += bufA.get(i * 8 + 2);
        }
        cx /= count; cy /= count; cz /= count;
        int innerCount = 0;
        double halfR = 0.5 * StellarArchetype.WHITE_DWARF.starRadius;
        for (int i = 0; i < count; i++) {
            double dx = bufA.get(i * 8) - cx;
            double dy = bufA.get(i * 8 + 1) - cy;
            double dz = bufA.get(i * 8 + 2) - cz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < halfR) {
                innerCount++;
            }
        }
        // Uniform sphere has 1/8 = 12.5% of particles inside r < R/2; centrally concentrated must have > 20%
        double innerFraction = innerCount / (double) count;
        assertTrue(innerFraction > 0.20,
                "White Dwarf must be centrally concentrated (not hollow!), innerFraction=" + innerFraction);
    }
}
