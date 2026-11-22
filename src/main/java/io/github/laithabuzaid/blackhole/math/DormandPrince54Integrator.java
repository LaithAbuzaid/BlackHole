package io.github.laithabuzaid.blackhole.math;

/**
 * Embedded 7-stage FSAL Dormand-Prince 5(4) adaptive ODE integrator
 * (Dormand &amp; Prince 1980; Hairer, Nørsett &amp; Wanner, Solving ODEs I, Table 5.2).
 *
 * <p>Uses a standard PI step-size controller with configurable relative/absolute tolerances
 * and zero per-step heap allocations.</p>
 */
public final class DormandPrince54Integrator implements OdeIntegrator {

    // Exact Dormand-Prince 5(4) Butcher tableau coefficients
    private static final double C2 = 1.0 / 5.0;
    private static final double C3 = 3.0 / 10.0;
    private static final double C4 = 4.0 / 5.0;
    private static final double C5 = 8.0 / 9.0;

    private static final double A21 = 1.0 / 5.0;

    private static final double A31 = 3.0 / 40.0;
    private static final double A32 = 9.0 / 40.0;

    private static final double A41 = 44.0 / 45.0;
    private static final double A42 = -56.0 / 15.0;
    private static final double A43 = 32.0 / 9.0;

    private static final double A51 = 19372.0 / 6561.0;
    private static final double A52 = -25360.0 / 2187.0;
    private static final double A53 = 64448.0 / 6561.0;
    private static final double A54 = -212.0 / 729.0;

    private static final double A61 = 9017.0 / 3168.0;
    private static final double A62 = -355.0 / 33.0;
    private static final double A63 = 46732.0 / 5247.0;
    private static final double A64 = 49.0 / 176.0;
    private static final double A65 = -5103.0 / 18656.0;

    // 5th-order solution weights (also A7i for FSAL)
    private static final double B1 = 35.0 / 384.0;
    private static final double B3 = 500.0 / 1113.0;
    private static final double B4 = 125.0 / 192.0;
    private static final double B5 = -2187.0 / 6784.0;
    private static final double B6 = 11.0 / 84.0;

    // Error estimator weights E_i = B_i - B*_i (5th minus 4th order)
    private static final double E1 = 71.0 / 57600.0;
    private static final double E3 = -71.0 / 16695.0;
    private static final double E4 = 71.0 / 1920.0;
    private static final double E5 = -17253.0 / 339200.0;
    private static final double E6 = 22.0 / 525.0;
    private static final double E7 = -1.0 / 40.0;

    private double[] k1 = new double[0];
    private double[] k2 = new double[0];
    private double[] k3 = new double[0];
    private double[] k4 = new double[0];
    private double[] k5 = new double[0];
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 5.0;
    private double[] k6 = new double[0];
    private double[] k7 = new double[0];
    private double[] yTmp = new double[0];
    private double[] yNext = new double[0];

    private void ensureCapacity(int n) {
        if (k1.length < n) {
            k1 = new double[n];
            k2 = new double[n];
            k3 = new double[n];
            k4 = new double[n];
            k5 = new double[n];
            k6 = new double[n];
            k7 = new double[n];
            yTmp = new double[n];
            yNext = new double[n];
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

        int n = y.length;
        ensureCapacity(n);

        double t = t0;
        double hMag = Math.max(config.hMin(), Math.min(config.hMax(), config.hInit()));
        system.evaluate(t, y, k1);
        if (diagnostics != null) diagnostics.functionEvaluations++;

        double prevErr = 1.0;
        int totalAttempts = 0;
        int maxAttempts = config.maxSteps() * 4;

        while (totalAttempts < maxAttempts) {
            double remaining = (tEnd - t) * dir;
            if (remaining <= 0.0) {
                if (diagnostics != null) diagnostics.completed = true;
                return t;
            }
            if (diagnostics != null && diagnostics.acceptedSteps >= config.maxSteps()) {
                return t;
            }

            boolean hittingEnd = hMag >= remaining;
            double stepMag = hittingEnd ? remaining : hMag;
            double h = dir * stepMag;

            // Stages 2..6
            for (int i = 0; i < n; i++) {
                yTmp[i] = y[i] + h * A21 * k1[i];
            }
            system.evaluate(t + C2 * h, yTmp, k2);

            for (int i = 0; i < n; i++) {
                yTmp[i] = y[i] + h * (A31 * k1[i] + A32 * k2[i]);
            }
            system.evaluate(t + C3 * h, yTmp, k3);

            for (int i = 0; i < n; i++) {
                yTmp[i] = y[i] + h * (A41 * k1[i] + A42 * k2[i] + A43 * k3[i]);
            }
            system.evaluate(t + C4 * h, yTmp, k4);

            for (int i = 0; i < n; i++) {
                yTmp[i] = y[i] + h * (A51 * k1[i] + A52 * k2[i] + A53 * k3[i] + A54 * k4[i]);
            }
            system.evaluate(t + C5 * h, yTmp, k5);

            for (int i = 0; i < n; i++) {
                yTmp[i] = y[i] + h * (A61 * k1[i] + A62 * k2[i] + A63 * k3[i] + A64 * k4[i] + A65 * k5[i]);
            }
            system.evaluate(t + h, yTmp, k6);

            // 5th-order candidate yNext
            for (int i = 0; i < n; i++) {
                yNext[i] = y[i] + h * (B1 * k1[i] + B3 * k3[i] + B4 * k4[i] + B5 * k5[i] + B6 * k6[i]);
            }

            // Stage 7 (FSAL: becomes next step's k1 if accepted)
            double tNext = hittingEnd ? tEnd : (t + h);
            system.evaluate(tNext, yNext, k7);
            if (diagnostics != null) diagnostics.functionEvaluations += 6;
            totalAttempts++;

            // RMS scaled error norm
            double errSumSq = 0.0;
            boolean finite = true;
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(yNext[i])) {
                    finite = false;
                    break;
                }
                double errI = h * (E1 * k1[i] + E3 * k3[i] + E4 * k4[i]
                        + E5 * k5[i] + E6 * k6[i] + E7 * k7[i]);
                double sc = config.atol() + config.rtol() * Math.max(Math.abs(y[i]), Math.abs(yNext[i]));
                double ratio = errI / sc;
                errSumSq += ratio * ratio;
            }
            double errNorm = finite ? Math.sqrt(errSumSq / n) : Double.POSITIVE_INFINITY;

            if (errNorm <= 1.0 || stepMag <= config.hMin() * (1.0 + 1e-12)) {
                // Accept step
                System.arraycopy(yNext, 0, y, 0, n);
                System.arraycopy(k7, 0, k1, 0, n); // FSAL reuse
                t = tNext;
                if (diagnostics != null) {
                    diagnostics.recordAcceptedStep(stepMag, finite ? errNorm : 1.0);
                }

                // PI step-size controller (beta = 0.04, alpha = 0.2 - 0.75 * beta = 0.17)
                double safeErr = Math.max(errNorm, 1e-10);
                double scale = config.safetyFactor()
                        * Math.pow(safeErr, -0.17)
                        * Math.pow(Math.max(prevErr, 1e-4), 0.04);
                scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
                if (!hittingEnd) {
                    hMag = Math.max(config.hMin(), Math.min(config.hMax(), stepMag * scale));
                }
                prevErr = Math.max(errNorm, 1e-4);

                if (observer != null && !observer.onStep(t, y, h)) {
                    return t;
                }
                if (hittingEnd) {
                    if (diagnostics != null) diagnostics.completed = true;
                    return t;
                }
            } else {
                // Reject step and shrink
                if (diagnostics != null) diagnostics.rejectedSteps++;
                double scale = finite
                        ? Math.max(MIN_SCALE, Math.min(0.8, config.safetyFactor() * Math.pow(errNorm, -0.2)))
                        : 0.25;
                hMag = Math.max(config.hMin(), stepMag * scale);
            }
        }

        if (diagnostics != null) {
            diagnostics.completed = (tEnd - t) * dir <= 1e-14;
        }
        return t;
    }
}
