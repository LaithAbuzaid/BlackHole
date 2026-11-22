package io.github.laithabuzaid.blackhole.math;

/**
 * Symplectic and energy-preserving geometric integrators for long-term Hamiltonian and orbital dynamics:
 * <ul>
 *   <li><b>2nd-order Implicit Midpoint (Gauss-Legendre 1-stage):</b> symplectic and time-reversible for
 *       general non-separable Hamiltonians (such as Kerr-Schild geodesics).</li>
 *   <li><b>Velocity Verlet + Boris Lense-Thirring Rotation:</b> symplectic for separable potentials and
 *       strictly kinetic-energy-preserving under velocity-dependent gravitomagnetic forces
 *       {@code a_LT = v x Omega_LT}.</li>
 * </ul>
 */
public final class SymplecticIntegrator {

    @FunctionalInterface
    public interface ConservativeAcceleration {
        void compute(double x, double y, double z, double[] outA);
    }

    @FunctionalInterface
    public interface GravitomagneticField {
        /** Computes {@code Omega_LT} such that the Lense-Thirring acceleration is {@code v x Omega_LT}. */
        void computeOmega(double x, double y, double z, double[] outOmega);
    }

    private double[] yMid = new double[0];
    private double[] kMid = new double[0];
    private double[] yNext = new double[0];

    private void ensureCapacity(int n) {
        if (yMid.length < n) {
            yMid = new double[n];
            kMid = new double[n];
            yNext = new double[n];
        }
    }

    /**
     * Advances {@code y} by one step {@code h} using the 2nd-order symplectic implicit midpoint rule:
     * {@code y_{n+1} = y_n + h * f(t + h/2, (y_n + y_{n+1}) / 2)}, solved via fixed-point iteration.
     *
     * @return number of fixed-point iterations performed.
     */
    public int stepImplicitMidpoint(
            OdeSystem system,
            double t,
            double[] y,
            double h,
            double tol,
            int maxIterations
    ) {
        int n = y.length;
        ensureCapacity(n);
        double tMid = t + 0.5 * h;
        double halfH = 0.5 * h;

        // Explicit midpoint predictor
        system.evaluate(t, y, kMid);
        for (int i = 0; i < n; i++) {
            yMid[i] = y[i] + halfH * kMid[i];
        }

        int iter = 0;
        for (; iter < maxIterations; iter++) {
            system.evaluate(tMid, yMid, kMid);
            double maxDiff = 0.0;
            for (int i = 0; i < n; i++) {
                double newMid = y[i] + halfH * kMid[i];
                double diff = Math.abs(newMid - yMid[i]);
                if (diff > maxDiff) maxDiff = diff;
                yMid[i] = newMid;
            }
            if (maxDiff <= tol) {
                iter++;
                break;
            }
        }
        system.evaluate(tMid, yMid, kMid);
        for (int i = 0; i < n; i++) {
            y[i] += h * kMid[i];
        }
        return iter;
    }

    /**
     * Exact kinetic-energy-preserving Boris rotation for a velocity-dependent gravitomagnetic force
     * {@code dv/dt = v x Omega}. Preserves {@code |v|} to machine precision for arbitrary step size {@code dt}.
     */
    public static void borisRotateVelocity(double[] v, double ox, double oy, double oz, double dt) {
        double tx = 0.5 * dt * ox;
        double ty = 0.5 * dt * oy;
        double tz = 0.5 * dt * oz;
        double t2 = tx * tx + ty * ty + tz * tz;
        double sFactor = 2.0 / (1.0 + t2);
        double sx = tx * sFactor;
        double sy = ty * sFactor;
        double sz = tz * sFactor;

        double vx = v[0], vy = v[1], vz = v[2];
        // v' = v + v x t
        double vpx = vx + (vy * tz - vz * ty);
        double vpy = vy + (vz * tx - vx * tz);
        double vpz = vz + (vx * ty - vy * tx);

        // v_new = v + v' x s
        v[0] = vx + (vpy * sz - vpz * sy);
        v[1] = vy + (vpz * sx - vpx * sz);
        v[2] = vz + (vpx * sy - vpy * sx);
    }

    /**
     * Time-reversible Velocity Verlet combined with an exact energy-conserving Boris rotation
     * for gravitomagnetic Lense-Thirring forces ({@code dv/dt = a_grav(x) + v x Omega_LT(x)}).
     *
     * @param pos 3-element position array updated in place
     * @param vel 3-element velocity array updated in place
     * @param scratchA 3-element scratch array for acceleration
     * @param scratchOmega 3-element scratch array for Lense-Thirring angular frequency
     */
    public static void stepVerletBoris(
            double[] pos,
            double[] vel,
            double dt,
            ConservativeAcceleration gravity,
            GravitomagneticField lenseThirring,
            double[] scratchA,
            double[] scratchOmega
    ) {
        double halfDt = 0.5 * dt;
        // 1. Half gravitational kick at x_n
        gravity.compute(pos[0], pos[1], pos[2], scratchA);
        vel[0] += halfDt * scratchA[0];
        vel[1] += halfDt * scratchA[1];
        vel[2] += halfDt * scratchA[2];

        // 2. Half drift to midpoint
        pos[0] += halfDt * vel[0];
        pos[1] += halfDt * vel[1];
        pos[2] += halfDt * vel[2];

        // 3. Exact energy-conserving Boris Lense-Thirring rotation at midpoint x_{n+1/2}
        if (lenseThirring != null) {
            lenseThirring.computeOmega(pos[0], pos[1], pos[2], scratchOmega);
            borisRotateVelocity(vel, scratchOmega[0], scratchOmega[1], scratchOmega[2], dt);
        }

        // 4. Second half drift to x_{n+1}
        pos[0] += halfDt * vel[0];
        pos[1] += halfDt * vel[1];
        pos[2] += halfDt * vel[2];

        // 5. Second half gravitational kick at x_{n+1}
        gravity.compute(pos[0], pos[1], pos[2], scratchA);
        vel[0] += halfDt * scratchA[0];
        vel[1] += halfDt * scratchA[1];
        vel[2] += halfDt * scratchA[2];
    }
}
