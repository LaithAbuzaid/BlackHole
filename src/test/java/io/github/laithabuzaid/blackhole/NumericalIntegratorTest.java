package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.math.Rk4Integrator;
import io.github.laithabuzaid.blackhole.math.SymplecticIntegrator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated numerical methods verification: convergence orders, adaptive error control,
 * and symplectic / Boris energy conservation.
 */
class NumericalIntegratorTest {

    @Test
    void rk4ExhibitsFourthOrderGlobalConvergence() {
        // Simple harmonic oscillator: y'' = -y, y(0) = 1, y'(0) = 0 -> y(T) = cos(T)
        OdeSystem sho = (t, y, dy) -> {
            dy[0] = y[1];
            dy[1] = -y[0];
        };
        double tEnd = 10.0; // non-multiple of pi so O(h^4) phase error is directly observed
        double exact = Math.cos(tEnd);
        Rk4Integrator rk4 = new Rk4Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();

        double[] steps = {0.08, 0.04, 0.02};
        double[] errors = new double[steps.length];
        for (int i = 0; i < steps.length; i++) {
            double[] state = {1.0, 0.0};
            rk4.integrate(sho, 0.0, state, tEnd, IntegratorConfig.fixedStep(steps[i], 10_000), diag);
            assertTrue(diag.completed);
            errors[i] = Math.abs(state[0] - exact);
        }

        // Halving h should reduce global error by ~2^4 = 16
        double order1 = Math.log(errors[0] / errors[1]) / Math.log(2.0);
        double order2 = Math.log(errors[1] / errors[2]) / Math.log(2.0);
        assertTrue(order1 > 3.9 && order1 < 4.15, "Expected O(h^4) RK4 order, got " + order1);
        assertTrue(order2 > 3.95 && order2 < 4.10, "Expected O(h^4) RK4 order, got " + order2);
    }

    @Test
    void dormandPrince54AchievesConfiguredToleranceOnKeplerOrbit() {
        // Eccentric Newtonian Kepler orbit (e = 0.6, a = 1, T = 2*pi)
        double ecc = 0.6;
        OdeSystem kepler = (t, y, dy) -> {
            double r2 = y[0] * y[0] + y[1] * y[1];
            double invR3 = 1.0 / (r2 * Math.sqrt(r2));
            dy[0] = y[2];
            dy[1] = y[3];
            dy[2] = -y[0] * invR3;
            dy[3] = -y[1] * invR3;
        };

        double[] state = {1.0 - ecc, 0.0, 0.0, Math.sqrt((1.0 + ecc) / (1.0 - ecc))};
        double e0 = 0.5 * (state[2] * state[2] + state[3] * state[3]) - 1.0 / Math.hypot(state[0], state[1]);
        double l0 = state[0] * state[3] - state[1] * state[2];

        DormandPrince54Integrator dopri = new DormandPrince54Integrator();
        IntegrationDiagnostics diag = new IntegrationDiagnostics();
        IntegratorConfig cfg = new IntegratorConfig(1e-10, 1e-12, 0.05, 1e-7, 0.5, 50_000, 0.9);
        dopri.integrate(kepler, 0.0, state, 2.0 * Math.PI, cfg, diag);

        assertTrue(diag.completed, "DOPRI5(4) should complete one full eccentric Kepler period");
        assertTrue(diag.acceptedSteps > 20 && diag.acceptedSteps < 1500,
                "Adaptive step count should be economical, got " + diag.acceptedSteps);
        assertEquals(1.0 - ecc, state[0], 1e-8, "Periapsis x should return accurately after 1 orbit");
        assertEquals(0.0, state[1], 1e-8, "Periapsis y should return accurately after 1 orbit");

        double e1 = 0.5 * (state[2] * state[2] + state[3] * state[3]) - 1.0 / Math.hypot(state[0], state[1]);
        double l1 = state[0] * state[3] - state[1] * state[2];
        assertEquals(e0, e1, 1e-9, "Orbital energy must be conserved");
        assertEquals(l0, l1, 1e-9, "Orbital angular momentum must be conserved");
    }

    @Test
    void symplecticImplicitMidpointShowsZeroSecularEnergyDriftOverLongHorizon() {
        OdeSystem sho = (t, y, dy) -> {
            dy[0] = y[1];
            dy[1] = -y[0];
        };
        SymplecticIntegrator symp = new SymplecticIntegrator();
        Rk4Integrator rk4 = new Rk4Integrator();

        double[] ySymp = {1.0, 0.0};
        double[] yRk4 = {1.0, 0.0};
        double h = 0.15;
        int steps = 4000; // ~95 full oscillations
        double e0 = 0.5;
        double maxSympDrift = 0.0;
        for (int i = 0; i < steps; i++) {
            symp.stepImplicitMidpoint(sho, i * h, ySymp, h, 1e-14, 16);
            rk4.step(sho, i * h, yRk4, h);
            double eSymp = 0.5 * (ySymp[0] * ySymp[0] + ySymp[1] * ySymp[1]);
            maxSympDrift = Math.max(maxSympDrift, Math.abs(eSymp - e0));
        }
        double eRk4End = 0.5 * (yRk4[0] * yRk4[0] + yRk4[1] * yRk4[1]);
        double rk4Drift = Math.abs(eRk4End - e0);

        assertTrue(maxSympDrift < 1e-12, "Implicit midpoint preserves quadratic Hamiltonian to machine precision, got " + maxSympDrift);
        assertTrue(maxSympDrift < rk4Drift * 1e-6, "Symplectic method should avoid RK4 secular energy decay");
    }

    @Test
    void borisLenseThirringRotationConservesKineticEnergyExactly() {
        double[] v = {0.31, -0.17, 0.24};
        double v2Before = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        for (int i = 0; i < 1000; i++) {
            SymplecticIntegrator.borisRotateVelocity(v, 0.45, -1.25, 0.72, 0.35);
        }
        double v2After = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        assertEquals(v2Before, v2After, 1e-14, "Boris rotation must preserve |v|^2 to machine precision");

        // Full Verlet-Boris step in Paczynski-Wiita + Lense-Thirring field (bound orbit well outside ISCO)
        double[] pos = {10.0, 2.0, 0.0};
        double[] vel = {0.0, 0.06, 0.42};
        double[] acc = new double[3];
        double[] omega = new double[3];
        double spinA = 0.92;
        SymplecticIntegrator.ConservativeAcceleration pwGravity = (x, y, z, outA) -> {
            double r = Math.sqrt(x * x + y * y + z * z);
            double rPw = Math.max(0.35, r - 2.0);
            double inv = -1.0 / (rPw * rPw * r);
            outA[0] = x * inv;
            outA[1] = y * inv;
            outA[2] = z * inv;
        };
        SymplecticIntegrator.GravitomagneticField ltField = (x, y, z, outO) -> {
            double r2 = x * x + y * y + z * z;
            double r = Math.sqrt(r2);
            double invR3 = 1.0 / (r2 * r);
            // Lense-Thirring magnetic field B_g = (2/r^3) [3 (J . r_hat) r_hat - J], J = (0, -a, 0)
            double jDotRhat = -spinA * (y / r);
            double c = 2.0 * invR3;
            outO[0] = c * (3.0 * jDotRhat * (x / r));
            outO[1] = c * (3.0 * jDotRhat * (y / r) + spinA);
            outO[2] = c * (3.0 * jDotRhat * (z / r));
        };

        double r0 = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
        double energy0 = 0.5 * (vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]) - 1.0 / (r0 - 2.0);
        double maxDrift = 0.0;
        for (int i = 0; i < 4000; i++) {
            SymplecticIntegrator.stepVerletBoris(pos, vel, 0.05, pwGravity, ltField, acc, omega);
            double r = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
            double e = 0.5 * (vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]) - 1.0 / (r - 2.0);
            maxDrift = Math.max(maxDrift, Math.abs(e - energy0));
        }
        assertTrue(maxDrift < 2e-5, "Verlet-Boris must conserve PW+Lense-Thirring energy cleanly, maxDrift=" + maxDrift);
    }
}
