package io.github.laithabuzaid.blackhole.math;

/**
 * Common interface for reusable ODE integrators (fixed-step RK4, adaptive DOPRI5(4), symplectic).
 */
public interface OdeIntegrator {

    /**
     * Optional per-step callback used to inspect state, record constraints, or stop early
     * (e.g., horizon capture or escape radius reached).
     */
    @FunctionalInterface
    interface StepObserver {
        /** @return {@code true} to continue integration, {@code false} to terminate early. */
        boolean onStep(double t, double[] y, double hUsed);
    }

    /**
     * Integrates {@code y} in-place from {@code t0} toward {@code tEnd}.
     *
     * @return final value of independent variable {@code t}.
     */
    double integrate(
            OdeSystem system,
            double t0,
            double[] y,
            double tEnd,
            IntegratorConfig config,
            IntegrationDiagnostics diagnostics,
            StepObserver observer
    );

    default double integrate(
            OdeSystem system,
            double t0,
            double[] y,
            double tEnd,
            IntegratorConfig config,
            IntegrationDiagnostics diagnostics
    ) {
        return integrate(system, t0, y, tEnd, config, diagnostics, null);
    }
}
