package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.BinarySpacetimeMetric;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.math.Rk4Integrator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scientific validation of the GPU-mirrored Superposed Spinning Kerr–Schild + 1PN
 * gravitomagnetic binary spacetime ({@link BinarySpacetimeMetric} &amp; {@code binary_merger.frag}):
 * <ul>
 *   <li>Exact spinning Kerr–Schild null vector identity {@code |l_i|^2 == 1} across spins &amp; inclinations.</li>
 *   <li>Co-moving 1PN gravitomagnetic frame-dragging direction for forward-in-time ZAMOs.</li>
 *   <li>Exact isolated single-body reduction to {@link KerrMetric} and post-merger remnant limit.</li>
 *   <li>Hamiltonian null-constraint conservation {@code |H_bin| < 1e-10} during active spinning
 *       inspiral and QNM ringdown.</li>
 *   <li>4th-order RK4 null-geodesic convergence and spinning horizon capture consistency.</li>
 *   <li>CPU/GPU shader uniform contract verification.</li>
 * </ul>
 */
class BinarySpacetimeValidationTest {

    @Test
    void spinningKerrSchildNullVectorsAreUnitLengthAcrossSpinsAndInclinations() {
        BinarySpacetimeMetric.SpacetimeSnapshot snap = new BinarySpacetimeMetric.SpacetimeSnapshot();
        snap.setOrbitalInclinationDeg(37.0);
        double[] l3 = new double[3];

        double[] chis = {-0.90, 0.0, +0.95};
        for (double chi : chis) {
            BinarySpacetimeMetric.evalSpinningNullVector(
                    5.2, -2.1, 3.8,
                    1.4, -0.5, 0.9,
                    0.6, chi, snap, l3);
            double norm = l3[0] * l3[0] + l3[1] * l3[1] + l3[2] * l3[2];
            assertEquals(1.0, norm, 1e-13,
                    "Spinning KS spatial null vector must satisfy |l|^2 == 1 identically for chi=" + chi);
        }
    }

    @Test
    void gravitomagneticShiftDragsForwardInTimeZamoAlongBodyVelocity() {
        // Place a single moving non-spinning body at origin with v = (0, 0, +0.25)
        // Evaluate canonical dx/dlambda at x = (5, 0, 0) for a forward-in-time ZAMO (p_i = 0, p_t = -1)
        BinarySpacetimeMetric.SpacetimeSnapshot snap = new BinarySpacetimeMetric.SpacetimeSnapshot();
        snap.mass1 = 1.0; snap.mass2 = 0.0; snap.chi1 = 0.0; snap.chi2 = 0.0;
        snap.vel1X = 0.0; snap.vel1Y = 0.0; snap.vel1Z = 0.25;
        double[] d6 = new double[6];
        BinarySpacetimeMetric.canonicalDerivatives(5.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, snap, d6);
        // Expected: dx_x = -2*H*1*1 < 0 (radial infall), dx_z = -2*H*(-1)*(+0.25) = +2*H*0.25 > 0 (co-moving drag!)
        double h = 1.0 / 5.0;
        assertEquals(-2.0 * h, d6[0], 1e-13, "ZAMO must experience inward Kerr-Schild infall");
        assertEquals(+2.0 * h * 0.25, d6[2], 1e-13, "Forward-in-time ZAMO must be frame-dragged along +v");
    }

    @Test
    void isolatedSingleBodyAndPostMergerLimitsReduceIdenticallyToExactKerrMetric() {
        // 1. Isolated single-body limit (m1 = 1, m2 = 0, v = 0, chi1 = 0.88) -> exact KerrMetric
        BinarySpacetimeMetric.SpacetimeSnapshot iso = new BinarySpacetimeMetric.SpacetimeSnapshot();
        iso.mass1 = 1.0; iso.mass2 = 0.0; iso.chi1 = 0.88; iso.chi2 = 0.0;
        iso.mergeBlend = 0.0;

        double[] dBin = new double[6];
        double[] dKerr = new double[6];
        double x = 4.2, y = 1.7, z = -3.1;
        double px = -0.62, py = 0.28, pz = 0.73;

        double hBin = BinarySpacetimeMetric.hamiltonian(x, y, z, px, py, pz, 1.0, iso);
        double hKerr = KerrMetric.hamiltonian(x, y, z, 1.0, px, py, pz, 0.88);
        assertEquals(hKerr, hBin, 1e-13, "Isolated spinning body must match exact KerrMetric Hamiltonian");

        BinarySpacetimeMetric.canonicalDerivatives(x, y, z, px, py, pz, 1.0, iso, dBin);
        KerrMetric.nullGeodesicOde(0.88, 1.0).evaluate(0.0, new double[]{x, y, z, px, py, pz}, dKerr);
        for (int i = 0; i < 6; i++) {
            assertEquals(dKerr[i], dBin[i], 1e-13, "Isolated body canonical derivative " + i + " must match exact Kerr");
        }

        // 2. Post-merger remnant limit (mergeBlend = 1, ringdownEnv = 0, M_f = 1.0, chi_f = 0.72)
        BinarySpacetimeMetric.SpacetimeSnapshot rem = new BinarySpacetimeMetric.SpacetimeSnapshot();
        rem.mergeBlend = 1.0; rem.ringdownEnv = 0.0; rem.remnantMass = 1.0; rem.remnantSpin = 0.72;
        assertEquals(KerrMetric.hamiltonian(x, y, z, 1.0, px, py, pz, 0.72),
                BinarySpacetimeMetric.hamiltonian(x, y, z, px, py, pz, 1.0, rem), 1e-13,
                "Post-merger remnant must reduce identically to exact KerrMetric");
    }

    @Test
    void binaryNullGeodesicsConserveHamiltonianAndExhibitFourthOrderRk4Convergence() {
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        bin.reset(new BinaryParameters(35.6, 24.4, 0.65, -0.45, 9.0, 0.0, 22.0, 30.0, 440.0, BinaryParameters.DetectorPreset.OPTIMAL));
        bin.advanceCurrentToSeparation(5.5);

        BinarySpacetimeMetric.SpacetimeSnapshot snap = new BinarySpacetimeMetric.SpacetimeSnapshot();
        bin.copySpacetimeSnapshot(snap);

        // 1. DOPRI5(4) Hamiltonian conservation along strongly lensed grazing ray in inclined spinning binary
        double[] y0 = BinarySpacetimeMetric.initializeNullRay(0.0, 6.8, 22.0, 0.18, -0.05, -0.98, snap);
        assertEquals(0.0, BinarySpacetimeMetric.hamiltonian(y0[0], y0[1], y0[2], y0[3], y0[4], y0[5], 1.0, snap), 1e-13);

        OdeSystem ode = BinarySpacetimeMetric.nullGeodesicOde(snap, 1.0);
        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        double[] yRef = y0.clone();
        double[] maxHamErr = {0.0};
        dopri.integrate(ode, 0.0, yRef, 36.0, new IntegratorConfig(1e-11, 1e-13, 0.1, 1e-5, 1.0, 20000, 0.9), diag,
                (lam, st, h) -> {
                    double err = Math.abs(BinarySpacetimeMetric.hamiltonian(st[0], st[1], st[2], st[3], st[4], st[5], 1.0, snap));
                    maxHamErr[0] = Math.max(maxHamErr[0], err);
                    return true;
                });
        assertTrue(diag.completed);
        assertTrue(maxHamErr[0] < 1e-10,
                "Inclined spinning binary Hamiltonian must be conserved to <1e-10, got " + maxHamErr[0]);

        // 2. Also verify Hamiltonian conservation during active QNM ringdown (proves exact QNM gradient!)
        snap.mergeBlend = 1.0;
        snap.ringdownEnv = 0.85;
        snap.gwPhase = 1.2;
        double[] yRing = BinarySpacetimeMetric.initializeNullRay(0.0, 3.0, 18.0, 0.30, -0.15, -0.94, snap);
        OdeSystem ringOde = BinarySpacetimeMetric.nullGeodesicOde(snap, 1.0);
        double[] maxRingErr = {0.0};
        dopri.integrate(ringOde, 0.0, yRing, 25.0, new IntegratorConfig(1e-11, 1e-13, 0.1, 1e-5, 1.0, 20000, 0.9), null,
                (lam, st, h) -> {
                    maxRingErr[0] = Math.max(maxRingErr[0],
                            Math.abs(BinarySpacetimeMetric.hamiltonian(st[0], st[1], st[2], st[3], st[4], st[5], 1.0, snap)));
                    return true;
                });
        assertTrue(maxRingErr[0] < 1e-10, "QNM ringdown Hamiltonian must be conserved to <1e-10, got " + maxRingErr[0]);

        // 3. 4th-order RK4 convergence test on the active spinning binary spacetime
        bin.copySpacetimeSnapshot(snap);
        Rk4Integrator rk4 = new Rk4Integrator();
        double[] yCoarse = y0.clone();
        double[] yFine = y0.clone();
        rk4.integrate(ode, 0.0, yCoarse, 36.0, IntegratorConfig.fixedStep(0.30, 5000), null, null);
        rk4.integrate(ode, 0.0, yFine,   36.0, IntegratorConfig.fixedStep(0.15, 5000), null, null);

        double errCoarse = Math.hypot(yCoarse[0] - yRef[0], Math.hypot(yCoarse[1] - yRef[1], yCoarse[2] - yRef[2]));
        double errFine = Math.hypot(yFine[0] - yRef[0], Math.hypot(yFine[1] - yRef[1], yFine[2] - yRef[2]));
        double ratio = errCoarse / Math.max(1e-16, errFine);
        assertTrue(ratio > 12.0 && ratio < 22.0,
                "RK4 geodesic error must scale at 4th order (~16x on halving h), got ratio=" + ratio);
    }

    @Test
    void horizonCaptureAndShaderUniformContractAreConsistent() throws IOException {
        // Spinning component horizon radius shrinks strictly with |chi|: r_+ = m * (1 + sqrt(1 - chi^2))
        double rPlusSchw = BinarySpacetimeMetric.componentHorizonRadius(0.6, 0.0);
        double rPlusSpin = BinarySpacetimeMetric.componentHorizonRadius(0.6, 0.9);
        assertEquals(1.2, rPlusSchw, 1e-12);
        assertEquals(0.6 * (1.0 + Math.sqrt(1.0 - 0.81)), rPlusSpin, 1e-12);
        assertTrue(rPlusSpin < rPlusSchw);

        // Aim a ray directly at spinning body 1 (from z = 6M) and verify capture inside r_{+,1}
        BinarySpacetimeMetric.SpacetimeSnapshot snap = new BinarySpacetimeMetric.SpacetimeSnapshot();
        snap.pos1X = -3.0; snap.pos2X = 3.0;
        snap.mass1 = 0.5; snap.mass2 = 0.5;
        snap.chi1 = 0.8; snap.chi2 = -0.5;
        double rPlus1 = BinarySpacetimeMetric.componentHorizonRadius(snap.mass1, snap.chi1);
        double[] y = BinarySpacetimeMetric.initializeNullRay(-3.0, 0.0, 6.0, 0.0, 0.0, -1.0, snap);
        boolean[] captured = {false};
        new DormandPrince54Integrator().integrate(
                BinarySpacetimeMetric.nullGeodesicOde(snap, 1.0), 0.0, y, 25.0,
                new IntegratorConfig(1e-8, 1e-10, 0.1, 1e-5, 0.5, 5000, 0.9), null,
                (lam, st, h) -> {
                    double r1 = BinarySpacetimeMetric.kerrRadius(st[0] - snap.pos1X, st[1], st[2], (0.5 * 0.8) * (0.5 * 0.8));
                    double p2 = st[3] * st[3] + st[4] * st[4] + st[5] * st[5];
                    // In ingoing Kerr-Schild backward raytracing, trapped horizon rays reach r -> r_+ with |p|^2 -> inf
                    if (r1 <= rPlus1 * 1.03 || (r1 <= rPlus1 * 1.35 && p2 > 400.0)) {
                        captured[0] = true;
                        return false;
                    }
                    return true;
                });
        assertTrue(captured[0], "Ray aimed at spinning component 1 must be captured by its Kerr horizon");

        // Verify CPU/GPU uniform contract in binary_merger.frag
        String shaderSrc;
        try (InputStream in = getClass().getResourceAsStream("/shaders/simulation/binary_merger.frag")) {
            assertNotNull(in, "Missing binary_merger.frag resource");
            shaderSrc = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] requiredUniforms = {
                "uGeomDt", "uChi1", "uChi2", "uOrbE1", "uOrbLHat", "uOrbE3",
                "uGwOmegaGeom", "uRemnantMass", "uRemnantSpin", "uMergeBlend", "uIntegTol"
        };
        for (String u : requiredUniforms) {
            assertTrue(shaderSrc.contains(u), "binary_merger.frag must declare required v3.0 uniform " + u);
        }
    }
}
