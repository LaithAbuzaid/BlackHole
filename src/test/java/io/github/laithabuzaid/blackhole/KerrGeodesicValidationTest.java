package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scientific validation of the exact Cartesian Kerr-Schild metric, Hamiltonian derivatives,
 * Doran free-fall geodesic 4-velocity, conserved quantities (H, L_y, Carter Q), and Bardeen shadow.
 */
class KerrGeodesicValidationTest {

    @Test
    void metricTimesInverseEqualsIdentityAcrossSpinsAndCoordinates() {
        double[] spins = {0.0, 0.5, 0.9, 0.998};
        double[][] points = {
                {4.2, 0.0, 1.3},
                {1.8, 2.4, -3.1},
                {0.0, 3.0, 0.0},      // symmetry pole
                {1.05, 0.25, -0.75}   // inside ergosphere / near horizon
        };
        double[][] g = new double[4][4];
        double[][] gInv = new double[4][4];

        for (double a : spins) {
            for (double[] p : points) {
                KerrMetric.metricAndInverse(p[0], p[1], p[2], a, g, gInv);
                for (int mu = 0; mu < 4; mu++) {
                    for (int nu = 0; nu < 4; nu++) {
                        double sum = 0.0;
                        for (int alpha = 0; alpha < 4; alpha++) {
                            sum += g[mu][alpha] * gInv[alpha][nu];
                        }
                        double expected = (mu == nu) ? 1.0 : 0.0;
                        assertEquals(expected, sum, 1e-13,
                                "g*g^-1 failed at a=" + a + ", mu=" + mu + ", nu=" + nu);
                    }
                }
            }
        }
    }

    @Test
    void analyticHamiltonianDerivativesMatchCentralFiniteDifferences() {
        double a = 0.94;
        double pt = 1.0;
        double[] state = {3.2, 1.4, -2.1, -0.72, 0.31, 0.58};
        double[] analytic = new double[6];
        KerrMetric.hamiltonianDerivatives(state, pt, a, analytic);

        double eps = 1e-6;
        for (int i = 0; i < 3; i++) {
            // dx^i/dlambda = +dH/dp_i
            double[] sPlus = state.clone();
            double[] sMinus = state.clone();
            sPlus[3 + i] += eps;
            sMinus[3 + i] -= eps;
            double hPlus = KerrMetric.hamiltonian(sPlus[0], sPlus[1], sPlus[2], pt, sPlus[3], sPlus[4], sPlus[5], a);
            double hMinus = KerrMetric.hamiltonian(sMinus[0], sMinus[1], sMinus[2], pt, sMinus[3], sMinus[4], sMinus[5], a);
            double fd = (hPlus - hMinus) / (2.0 * eps);
            assertEquals(fd, analytic[i], 1e-8, "dx[" + i + "]/dlambda mismatch");

            // dp_i/dlambda = -dH/dx^i
            sPlus = state.clone();
            sMinus = state.clone();
            sPlus[i] += eps;
            sMinus[i] -= eps;
            hPlus = KerrMetric.hamiltonian(sPlus[0], sPlus[1], sPlus[2], pt, sPlus[3], sPlus[4], sPlus[5], a);
            hMinus = KerrMetric.hamiltonian(sMinus[0], sMinus[1], sMinus[2], pt, sMinus[3], sMinus[4], sMinus[5], a);
            fd = -(hPlus - hMinus) / (2.0 * eps);
            assertEquals(fd, analytic[3 + i], 1e-8, "dp[" + i + "]/dlambda mismatch");
        }
    }

    @Test
    void exactDoranFourVelocitySatisfiesTimelikeUnitNormEnergyOneAndZeroAngularMomentum() {
        double a = 0.96;
        double[][] testPoints = {
                {8.0, 2.0, 3.0},      // exterior
                {1.6, 0.7, -0.9},     // inside ergosphere
                {0.85, 0.45, 0.55}    // strictly INSIDE outer event horizon r_+ = 1.28
        };
        double[] uCon = new double[4];
        double[][] g = new double[4][4];
        double[][] gInv = new double[4][4];

        for (double[] p : testPoints) {
            KerrMetric.doranFourVelocity(p[0], p[1], p[2], a, uCon);
            KerrMetric.metricAndInverse(p[0], p[1], p[2], a, g, gInv);

            double[] uCov = new double[4];
            double norm = 0.0;
            for (int mu = 0; mu < 4; mu++) {
                double sum = 0.0;
                for (int nu = 0; nu < 4; nu++) {
                    sum += g[mu][nu] * uCon[nu];
                }
                uCov[mu] = sum;
                norm += uCov[mu] * uCon[mu];
            }

            assertEquals(-1.0, norm, 1e-13, "Doran u^mu must be unit timelike g_{mu nu} u^mu u^nu = -1");
            assertEquals(-1.0, uCov[0], 1e-13, "Doran infaller from rest at infinity must have E = -u_t = 1");
            double lPhi = -p[2] * uCov[1] + p[0] * uCov[3];
            assertEquals(0.0, lPhi, 1e-13, "Doran infaller must have exact L_y = u_phi = 0");
        }
    }

    @Test
    void strongFieldKerrNullGeodesicConservesHamiltonianAngularMomentumAndCarterConstant() {
        double a = 0.92;
        double[] state = new double[6];
        // Launch inclined ray from r ~ 20M passing deep in strong field near photon sphere
        double x0 = 20.0, y0 = 4.5, z0 = 0.0;
        double dx = -0.94, dy = -0.14, dz = 0.3111269837;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double pt = KerrMetric.initStaticShellRay(x0, y0, z0, dx / len, dy / len, dz / len, a, state);

        double h0 = KerrMetric.hamiltonian(state[0], state[1], state[2], pt, state[3], state[4], state[5], a);
        double ly0 = KerrMetric.conservedLy(state[0], state[2], state[3], state[5]);
        double q0 = KerrMetric.carterConstant(state[0], state[1], state[2], pt, state[3], state[4], state[5], a);
        assertEquals(0.0, h0, 1e-14, "Initial static-shell ray must be strictly null");

        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        OdeSystem ode = KerrMetric.nullGeodesicOde(a, pt);
        IntegratorConfig cfg = new IntegratorConfig(1e-11, 1e-13, 0.05, 1e-7, 1.0, 50_000, 0.9);

        final double[] maxDrifts = {0.0, 0.0, 0.0};
        final double[] minR = {Double.POSITIVE_INFINITY};
        dopri.integrate(ode, 0.0, state, 60.0, cfg, diag, (lam, y, h) -> {
            double r = KerrMetric.kerrRadius(y[0], y[1], y[2], a);
            if (r < minR[0]) minR[0] = r;
            double hErr = Math.abs(KerrMetric.hamiltonian(y[0], y[1], y[2], pt, y[3], y[4], y[5], a));
            double lyErr = Math.abs(KerrMetric.conservedLy(y[0], y[2], y[3], y[5]) - ly0);
            double qErr = Math.abs(KerrMetric.carterConstant(y[0], y[1], y[2], pt, y[3], y[4], y[5], a) - q0);
            if (hErr > maxDrifts[0]) maxDrifts[0] = hErr;
            if (lyErr > maxDrifts[1]) maxDrifts[1] = lyErr;
            if (qErr > maxDrifts[2]) maxDrifts[2] = qErr;
            return r > KerrMetric.outerHorizonRadius(a) * 1.02 && r < 45.0;
        });

        assertTrue(minR[0] < 7.0, "Ray should probe deep strong field, minR=" + minR[0]);
        assertTrue(maxDrifts[0] < 1e-10, "Null Hamiltonian constraint drift too large: " + maxDrifts[0]);
        assertTrue(maxDrifts[1] < 1e-9, "Axial angular momentum L_y drift too large: " + maxDrifts[1]);
        assertTrue(maxDrifts[2] < 1e-8, "Carter constant Q drift too large: " + maxDrifts[2]);
    }

    @Test
    void numericalShadowBoundaryMatchesAnalyticalSchwarzschildAndKerrBardeenCurves() {
        // 1. Schwarzschild (a = 0): critical impact parameter b_c = 3*sqrt(3) = 5.1961524227
        double bCritSchw = findEquatorialCriticalImpact(0.0, 1.0);
        assertEquals(KerrMetric.SCHWARZSCHILD_SHADOW_RADIUS, bCritSchw, 1e-3,
                "Schwarzschild numerical critical curve must match 3*sqrt(3)");

        // 2. High-spin Kerr (a = 0.95): backward-traced rays launch opposite to forward photon momentum,
        // so sideSign = -1 probes prograde photons (compressed ~2.58M) and +1 probes retrograde (~6.92M).
        double a = 0.95;
        double bPro = findEquatorialCriticalImpact(a, -1.0);
        double bRet = findEquatorialCriticalImpact(a, +1.0);

        double[] proPt = new double[2];
        double[] retPt = new double[2];
        assertTrue(KerrMetric.bardeenShadowPoint(a, Math.PI * 0.5, KerrMetric.progradePhotonSphereRadius(a), proPt));
        assertTrue(KerrMetric.bardeenShadowPoint(a, Math.PI * 0.5, KerrMetric.retrogradePhotonSphereRadius(a), retPt));

        assertEquals(Math.abs(proPt[0]), bPro, 2e-2, "Prograde Kerr shadow edge must match Bardeen(1973)");
        assertEquals(Math.abs(retPt[0]), bRet, 2e-2, "Retrograde Kerr shadow edge must match Bardeen(1973)");
        assertTrue(bRet - bPro > 3.5, "Spinning Kerr shadow must exhibit strong D-shaped asymmetry");
    }

    @Test
    void analyticalIscoAndPhotonSphereTableMatchesPublishedReferences() {
        // Schwarzschild a = 0
        assertEquals(2.0, KerrMetric.outerHorizonRadius(0.0), 1e-12);
        assertEquals(6.0, KerrMetric.progradeIscoRadius(0.0), 1e-12);
        assertEquals(6.0, KerrMetric.retrogradeIscoRadius(0.0), 1e-12);
        assertEquals(3.0, KerrMetric.progradePhotonSphereRadius(0.0), 1e-12);
        assertEquals(3.0, KerrMetric.retrogradePhotonSphereRadius(0.0), 1e-12);

        // Near-extremal Kerr a = 0.998 (Thorne limit)
        assertEquals(1.0632139, KerrMetric.outerHorizonRadius(0.998), 1e-5);
        assertEquals(1.23697, KerrMetric.progradeIscoRadius(0.998), 5e-4);
        assertEquals(8.9946, KerrMetric.retrogradeIscoRadius(0.998), 1e-3);
        assertEquals(1.0739, KerrMetric.progradePhotonSphereRadius(0.998), 1e-3);
        assertEquals(3.9977, KerrMetric.retrogradePhotonSphereRadius(0.998), 1e-3);
    }

    @Test
    void doranInteriorTetradIsExactAndEscapesBackwardAcrossHorizon() {
        double a = 0.90;
        double rPlus = KerrMetric.outerHorizonRadius(a); // ~1.436M
        double rCam = 1.12;
        assertTrue(rCam < rPlus, "Test observer must start inside event horizon r_+");

        // Place observer at oblate radius rCam, pitch = 0.18 rad
        double pitch = 0.18;
        double x0 = Math.sqrt(rCam * rCam + a * a) * Math.cos(pitch);
        double y0 = rCam * Math.sin(pitch);
        double z0 = 0.0;

        double[] hl = new double[5];
        KerrMetric.metricScalarAndNullVector(x0, y0, z0, a, hl);
        double h = hl[1];
        double lx = hl[2], ly = hl[3], lz = hl[4];
        double[] u4 = new double[4];
        KerrMetric.doranFourVelocity(x0, y0, z0, a, u4);

        // Verify outward-looking backward-traced ray inside r_+ satisfies H_ham == 0, pt > 0, and escapes to r > 12M
        double lu = u4[0] + lx * u4[1] + ly * u4[2] + lz * u4[3];
        double uCovX = u4[1] + 2.0 * h * lu * lx;
        double uCovY = u4[2] + 2.0 * h * lu * ly;
        double uCovZ = u4[3] + 2.0 * h * lu * lz;

        double alphaU = uCovX * lx + uCovY * ly + uCovZ * lz;
        double w0 = alphaU * u4[0];
        double wx = lx + alphaU * u4[1];
        double wy = ly + alphaU * u4[2];
        double wz = lz + alphaU * u4[3];
        double lw = w0 + lx * wx + ly * wy + lz * wz;
        double wNorm = Math.sqrt(-w0 * w0 + wx * wx + wy * wy + wz * wz + 2.0 * h * lw * lw);
        double e0 = w0 / wNorm, ex = wx / wNorm, ey = wy / wNorm, ez = wz / wNorm;

        double p0Con = -u4[0] + e0;
        double pxCon = -u4[1] + ex;
        double pyCon = -u4[2] + ey;
        double pzCon = -u4[3] + ez;
        double lpCon = p0Con + lx * pxCon + ly * pyCon + lz * pzCon;

        double pt = -p0Con + 2.0 * h * lpCon;
        double px = pxCon + 2.0 * h * lpCon * lx;
        double py = pyCon + 2.0 * h * lpCon * ly;
        double pz = pzCon + 2.0 * h * lpCon * lz;

        assertTrue(pt > 0.5, "Outward-looking backward-traced ray inside r_+ must have pt > 0, got " + pt);
        double ham0 = Math.abs(KerrMetric.hamiltonian(x0, y0, z0, pt, px, py, pz, a));
        assertTrue(ham0 < 1e-12, "Doran interior ray must satisfy exact null condition H_ham=0, got " + ham0);

        double[] state = {x0, y0, z0, px, py, pz};
        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        IntegratorConfig cfg = new IntegratorConfig(1e-9, 1e-11, 0.04, 1e-6, 0.5, 10_000, 0.9);
        final double[] maxR = {rCam};
        dopri.integrate(KerrMetric.nullGeodesicOde(a, pt), 0.0, state, 30.0, cfg, diag, (lam, y, step) -> {
            double r = KerrMetric.kerrRadius(y[0], y[1], y[2], a);
            if (r > maxR[0]) maxR[0] = r;
            return r < 18.0;
        });
        assertTrue(maxR[0] > 12.0, "Backward-traced ray from inside r_+ must cross horizon and reach exterior sky, maxR=" + maxR[0]);
    }

    /** Bisects the equatorial critical impact parameter b_c separating capture from escape at r_cam = 120M. */
    private static double findEquatorialCriticalImpact(double a, double sideSign) {
        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        IntegratorConfig cfg = new IntegratorConfig(1e-9, 1e-11, 0.1, 1e-6, 2.0, 20_000, 0.9);
        double rCam = 120.0;
        double rCapture = KerrMetric.outerHorizonRadius(a) * 1.015;

        double lo = 1.5;
        double hi = 8.0;
        for (int iter = 0; iter < 32; iter++) {
            double mid = 0.5 * (lo + hi);
            double sinA = mid / rCam;
            double cosA = Math.sqrt(1.0 - sinA * sinA);
            double[] state = new double[6];
            // Place observer at (rCam, 0, 0); ray angled by +/- sideSign along +z (prograde has L_y = x*p_z > 0)
            double pt = KerrMetric.initStaticShellRay(rCam, 0.0, 0.0, -cosA, 0.0, sideSign * sinA, a, state);
            OdeSystem ode = KerrMetric.nullGeodesicOde(a, pt);
            final boolean[] captured = {false};
            dopri.integrate(ode, 0.0, state, 400.0, cfg, diag, (lam, y, h) -> {
                double r = KerrMetric.kerrRadius(y[0], y[1], y[2], a);
                if (r <= rCapture) {
                    captured[0] = true;
                    return false;
                }
                return r < rCam * 1.25;
            });
            if (captured[0]) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        // Return exact asymptotic impact parameter |L_y / E| of the critical ray (exact for finite rCam!)
        double critMid = 0.5 * (lo + hi);
        double sinA = critMid / rCam;
        double cosA = Math.sqrt(1.0 - sinA * sinA);
        double[] state = new double[6];
        double pt = KerrMetric.initStaticShellRay(rCam, 0.0, 0.0, -cosA, 0.0, sideSign * sinA, a, state);
        return Math.abs(KerrMetric.conservedLy(state[0], state[2], state[3], state[5]) / pt);
    }
}
