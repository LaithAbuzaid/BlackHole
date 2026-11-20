package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.SimulationEngine;

/**
 * Entry point for the Relativistic Black Hole Simulator. Rendering and lifecycle
 * implementation lives in {@link SimulationEngine}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        new SimulationEngine().run();
    }
}
