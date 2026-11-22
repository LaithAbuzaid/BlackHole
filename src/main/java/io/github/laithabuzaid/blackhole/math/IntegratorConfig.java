package io.github.laithabuzaid.blackhole.math;

/**
 * Immutable integration tolerances and step-size bounds for fixed and adaptive ODE solvers.
 */
public record IntegratorConfig(
        double rtol,
        double atol,
        double hInit,
        double hMin,
        double hMax,
        int maxSteps,
        double safetyFactor
) {
    public IntegratorConfig {
        if (!(rtol > 0.0) || !Double.isFinite(rtol)) {
            throw new IllegalArgumentException("rtol must be positive finite: " + rtol);
        }
        if (!(atol > 0.0) || !Double.isFinite(atol)) {
            throw new IllegalArgumentException("atol must be positive finite: " + atol);
        }
        if (!(hMin > 0.0) || !Double.isFinite(hMin)) {
            throw new IllegalArgumentException("hMin must be positive finite: " + hMin);
        }
        if (!(hMax >= hMin) || !Double.isFinite(hMax)) {
            throw new IllegalArgumentException("hMax must be >= hMin: " + hMax);
        }
        if (!(hInit > 0.0) || !Double.isFinite(hInit)) {
            throw new IllegalArgumentException("hInit must be positive finite: " + hInit);
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1: " + maxSteps);
        }
        if (!(safetyFactor > 0.1 && safetyFactor < 1.0)) {
            throw new IllegalArgumentException("safetyFactor must be in (0.1, 1.0): " + safetyFactor);
        }
    }

    public static IntegratorConfig defaultAdaptive() {
        return new IntegratorConfig(1e-8, 1e-10, 0.05, 1e-6, 1.0, 20_000, 0.9);
    }

    public static IntegratorConfig highPrecision() {
        return new IntegratorConfig(1e-11, 1e-13, 0.02, 1e-8, 0.5, 200_000, 0.9);
    }

    public static IntegratorConfig fixedStep(double h, int maxSteps) {
        return new IntegratorConfig(1e-6, 1e-8, h, h, h, maxSteps, 0.9);
    }
}
