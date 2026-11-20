package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.SimulationEngine;

/**
 * Entry point for the Relativistic Black Hole Simulator.
 *
 * <p>Pass {@code --capture-showcase} to render five curated 1920x1080 PNG
 * showcase images into {@code docs/media/} and exit with status code 0.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean captureShowcase = false;
        if (args != null) {
            for (String arg : args) {
                if ("--capture-showcase".equals(arg)) {
                    captureShowcase = true;
                }
            }
        }
        new SimulationEngine(captureShowcase).run();
    }
}
