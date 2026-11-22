package io.github.laithabuzaid.blackhole.math;

/**
 * First-order ordinary differential equation system {@code dy/dt = f(t, y)}.
 * Implementations must write into the caller-supplied {@code dydt} array without allocating.
 */
@FunctionalInterface
public interface OdeSystem {
    void evaluate(double t, double[] y, double[] dydt);
}
