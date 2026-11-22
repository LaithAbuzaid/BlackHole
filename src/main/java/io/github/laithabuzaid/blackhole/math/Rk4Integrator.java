package io.github.laithabuzaid.blackhole.math;

/**
 * Classical 4th-order Runge-Kutta (RK4) integrator with reusable scratch buffers.
 * Exact local truncation error is O(h^5) and global error is O(h^4).
 */
public final class Rk4Integrator implements OdeIntegrator {
    private double[] k1 = new double[0];
    private double[] k2 = new double[0];
    private double[] k3 = new double[0];
    private double[] k4 = new double[0];
    private double[] yTmp = new double[0];

    private void ensureCapacity(int n) {
        if (k1.length < n) {
            k1 = new double[n];
            k2 = new double[n];
            k3 = new double[n];
            k4 = new double[n];
            yTmp = new double[n];
        }
    }

    /** Executes a single RK4 step of size {@code h}, updating {@code y} in place. */
    public void step(OdeSystem system, double t, double[] y, double h) {
        int n = y.length;
        ensureCapacity(n);

        system.evaluate(t, y, k1);
        double halfH = 0.5 * h;
        for (int i = 0; i < n; i++) yTmp[i] = y[i] + halfH * k1[i];
        system.evaluate(t + halfH, yTmp, k2);

        for (int i = 0; i < n; i++) yTmp[i] = y[i] + halfH * k2[i];
        system.evaluate(t + halfH, yTmp, k3);

        for (int i = 0; i < n; i++) yTmp[i] = y[i] + h * k3[i];
        system.evaluate(t + h, yTmp, k4);

        double h6 = h / 6.0;
        for (int i = 0; i < n; i++) {
            y[i] += h6 * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]);
        }
    }

    @Override
    public double integrate(
            OdeSystem system,
            double t0,
            double[] y,
            double tEnd,
            IntegratorConfig config,
            IntegrationDiagnostics diagnostics,
            StepObserver observer
    ) {
        if (diagnostics != null) diagnostics.reset();
        double dir = Math.signum(tEnd - t0);
        if (dir == 0.0) {
            if (diagnostics != null) diagnostics.completed = true;
            return t0;
        }
        ensureCapacity(y.length);

        double t = t0;
        double hMag = Math.max(config.hMin(), Math.min(config.hMax(), config.hInit()));
        for (int s = 0; s < config.maxSteps(); s++) {
            double remaining = (tEnd - t) * dir;
            if (remaining <= 0.0) {
                if (diagnostics != null) diagnostics.completed = true;
                return t;
            }
            double stepMag = Math.min(hMag, remaining);
            double h = dir * stepMag;
            step(system, t, y, h);
            t = (stepMag == remaining) ? tEnd : (t + h);
            if (diagnostics != null) {
                diagnostics.functionEvaluations += 4;
                diagnostics.recordAcceptedStep(stepMag, 0.0);
            }
            if (observer != null && !observer.onStep(t, y, h)) {
                return t;
            }
        }
        if (diagnostics != null) {
            diagnostics.completed = (tEnd - t) * dir <= 1e-14;
        }
        return t;
    }
}
