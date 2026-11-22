package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.SimulationEngine;

import java.nio.file.Path;

/**
 * Entry point for the Relativistic Black Hole Simulator ({@code v2.0.0}).
 *
 * <p>Supported CLI flags:
 * <ul>
 *   <li>{@code --capture-showcase}: Render 8 curated 1920x1080 showcase PNGs into {@code docs/media/} and exit.</li>
 *   <li>{@code --benchmark}: Execute repeatable 1080p multi-scenario GPU/CPU/zero-GC benchmarks and exit.</li>
 *   <li>{@code --gw-data <path>}: Load an external GWOSC CSV strain file for Binary Merger reference overlay.</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean captureShowcase = false;
        boolean runBenchmark = false;
        Path gwDataPath = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--capture-showcase" -> captureShowcase = true;
                    case "--benchmark" -> runBenchmark = true;
                    case "--gw-data" -> {
                        if (i + 1 < args.length) {
                            gwDataPath = Path.of(args[++i]);
                        } else {
                            System.err.println("Missing file path after --gw-data");
                            return;
                        }
                    }
                    case "--help", "-h" -> {
                        System.out.println("BlackHole v2.0.0 — General-Relativistic Black Hole Observatory");
                        System.out.println("Usage: java --enable-native-access=ALL-UNNAMED -jar blackhole-2.0.0.jar [options]");
                        System.out.println("  --capture-showcase   Render 8 curated 1920x1080 showcase PNGs into docs/media/");
                        System.out.println("  --benchmark          Run repeatable 1080p GPU/CPU/zero-GC benchmark suite");
                        System.out.println("  --gw-data <file>     Load an external GWOSC strain CSV for LIGO overlay comparison");
                        return;
                    }
                    default -> System.err.println("[BlackHole] Ignoring unknown argument: " + arg);
                }
            }
        }
        new SimulationEngine(captureShowcase, runBenchmark, gwDataPath).run();
    }
}
