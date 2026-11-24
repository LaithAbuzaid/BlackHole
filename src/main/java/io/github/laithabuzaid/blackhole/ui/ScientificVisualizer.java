package io.github.laithabuzaid.blackhole.ui;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.graphics.TidalDisruptionSystem;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.MorrisThorneMetric;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;
import io.github.laithabuzaid.blackhole.ui.vis.BinaryVisualizer;
import io.github.laithabuzaid.blackhole.ui.vis.KerrVisualizer;
import io.github.laithabuzaid.blackhole.ui.vis.PlotWidgets;
import io.github.laithabuzaid.blackhole.ui.vis.TdeVisualizer;
import io.github.laithabuzaid.blackhole.ui.vis.WormholeVisualizer;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;

/**
 * Mode-aware Scientific Visualization &amp; Diagnostics coordinator ({@code [F3]} + always-on
 * physical colormaps/glyphs).
 *
 * <p>Dispatches exclusively to the visualizer matching the active spacetime mode
 * ({@link BinaryVisualizer}, {@link KerrVisualizer} + {@link TdeVisualizer}, or
 * {@link WormholeVisualizer}) so unrelated physics elements never bleed across scenes,
 * while strictly distinguishing exact metric geometry {@code [E]}, analytical/EOB models
 * {@code [A]}, NR calibration fits {@code [F]}, and procedural plasma models {@code [V]}.</p>
 */
public final class ScientificVisualizer {

    private final BinaryVisualizer binaryVisualizer = new BinaryVisualizer();
    private final KerrVisualizer kerrVisualizer = new KerrVisualizer();
    private final TdeVisualizer tdeVisualizer = new TdeVisualizer();
    private final WormholeVisualizer wormholeVisualizer = new WormholeVisualizer();

    private final StringBuilder sb1 = new StringBuilder(160);
    private final StringBuilder sb2 = new StringBuilder(160);
    private final StringBuilder sb3 = new StringBuilder(160);

    public void render(UiRenderer ui, int width, int height,
                       SimulationState state, CameraController camera,
                       BinaryMergerPhysics binary, HardwareTelemetry telemetry) {
        render(ui, width, height, state, camera, binary, null, null, telemetry);
    }

    public void render(UiRenderer ui, int width, int height,
                       SimulationState state, CameraController camera,
                       BinaryMergerPhysics binary, TidalDisruptionSystem tde,
                       GwReferenceData gwRef, HardwareTelemetry telemetry) {
        // Always-on physical layer legends, EVPA glyphs, hydro grid bounds, and Plunge HUD
        if (!state.binaryMode && !state.wormholeMode) {
            kerrVisualizer.renderAlwaysOnLayers(ui, width, height, state, camera);
            tdeVisualizer.renderAlwaysOnLayers(ui, width, height, state, camera, tde);
        }

        if (state.scientificDiagMode == SimulationState.DIAG_OFF) {
            return;
        }

        if (state.scientificDiagMode == SimulationState.DIAG_ANALYTICAL_OVERLAY) {
            if (state.binaryMode) {
                binaryVisualizer.render(ui, width, height, state, camera, binary, gwRef);
            } else if (state.wormholeMode) {
                wormholeVisualizer.renderAnalytical(ui, width, height, state, camera);
            } else {
                boolean hasTde = state.visTdeOverlay && tde != null
                        && (tde.activeParticleCount() > 0
                            || tde.totalSwallowedCount() > 0
                            || state.tdeHydroVisMode > 0);
                kerrVisualizer.renderAnalytical(ui, width, height, state, camera, hasTde);
                if (hasTde) {
                    tdeVisualizer.renderAnalytical(ui, width, height, state, camera, tde);
                }
            }
        } else if (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR
                || state.scientificDiagMode == SimulationState.DIAG_STEP_DENSITY) {
            renderColorbarLegend(ui, width, state);
        }

        renderScientificStatusBanner(ui, width, height, state, camera, binary, tde, telemetry);
    }

    private void renderColorbarLegend(UiRenderer ui, int width, SimulationState state) {
        boolean isErr = (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR);
        float bw = isErr && state.wormholeMode ? 316f : 276f;
        float bh = 58f;
        float bx = (width - bw) * 0.5f;
        float by = 18f;

        String title;
        String minL;
        String midL;
        String maxL;
        if (isErr) {
            title = state.wormholeMode
                    ? "NULL CONSTRAINT log10(|vl^2 + H^2/r^2 - 1|)"
                    : (state.binaryMode
                        ? "BINARY NULL CONSTRAINT log10(|H_eff|)"
                        : "KERR-SCHILD NULL CONSTRAINT log10(|H|)");
            minL = "1e-7";
            midL = "1e-4.5";
            maxL = "1e-2";
        } else {
            title = state.wormholeMode
                    ? "WORMHOLE RK4 STEP DENSITY"
                    : (state.binaryMode
                        ? "BINARY GEODESIC STEP COUNT"
                        : (state.integratorMode == SimulationState.INTEGRATOR_DOPRI54
                            ? "KERR DOPRI5(4) STEP COUNT"
                            : "KERR RK4 STEP COUNT"));
            minL = "0";
            midL = state.binaryMode ? "90" : "100";
            maxL = state.binaryMode ? "180" : "200";
        }

        PlotWidgets.drawColorbar(ui, bx, by, bw, bh, title, minL, midL, maxL, PlotWidgets.CMAP_TURBO);
    }

    private void renderScientificStatusBanner(UiRenderer ui, int width, int height,
                                              SimulationState state, CameraController camera,
                                              BinaryMergerPhysics binary, TidalDisruptionSystem tde,
                                              HardwareTelemetry telemetry) {
        float w = Math.min(760f, width - 40f);
        float h = 64f;
        float x = (width - w) * 0.5f;
        float y = height - 118f;

        ui.drawGlassPanel(x, y, w, h, 8f, 0.03f, 0.05f, 0.09f, 0.88f, 0.24f, 0.86f, 0.82f, 0.55f);

        sb1.setLength(0);
        sb1.append("[F3] ").append(state.scientificDiagLabel()).append(" | ");
        if (state.binaryMode) {
            sb1.append("[A] EOB 3PN+NLO + [F] REMNANT/QNM + [A] BOOSTED KERR-SCHILD (")
               .append(state.integratorMode == SimulationState.INTEGRATOR_DOPRI54 ? "DOPRI5(4)" : "RK4")
               .append(')');
        } else if (state.wormholeMode) {
            sb1.append("[E] MORRIS-THORNE DNEG METRIC (ds^2 = -dt^2 + dl^2 + r(l)^2 dOm^2)");
        } else if (state.plungeActive) {
            sb1.append("[E] DORAN HORIZON-PENETRATING TETRAD + [A] NOVIKOV-THORNE DISK");
        } else {
            sb1.append("[E] EXACT KERR-SCHILD (")
               .append(state.integratorMode == SimulationState.INTEGRATOR_DOPRI54 ? "DOPRI5(4)" : "VGPR-RK4")
               .append(") + [A] N-T DISK");
        }

        sb2.setLength(0);
        if (state.binaryMode && binary != null) {
            sb2.append("q=");
            PlotWidgets.appendFixed2(sb2, binary.massRatioQ);
            sb2.append(" eta=");
            PlotWidgets.appendFixed3(sb2, binary.symMassRatio);
            sb2.append(" chi=");
            PlotWidgets.appendSigned2(sb2, binary.chi1);
            sb2.append('/');
            PlotWidgets.appendSigned2(sb2, binary.chi2);
            sb2.append(" r=");
            PlotWidgets.appendFixed2(sb2, binary.currentSeparation);
            sb2.append("M Om=");
            PlotWidgets.appendFixed3(sb2, binary.orbitalOmegaGeom);
            sb2.append(" Eb=");
            PlotWidgets.appendFixed3(sb2, binary.orbitalEnergy);
            sb2.append(" Er=");
            PlotWidgets.appendFixed4(sb2, binary.radiatedEnergy);
        } else if (state.wormholeMode) {
            double lCam = camera.currentWormholeL();
            double rCam = MorrisThorneMetric.radius(lCam, state.wormholeRho, state.wormholeA, state.wormholeM);
            sb2.append("rho=");
            PlotWidgets.appendFixed2(sb2, state.wormholeRho);
            sb2.append("M  a=");
            PlotWidgets.appendFixed2(sb2, state.wormholeA);
            sb2.append("M  Mw=");
            PlotWidgets.appendFixed2(sb2, state.wormholeM);
            sb2.append("M  l_cam=");
            PlotWidgets.appendSigned2(sb2, (float) lCam);
            sb2.append("M  r(l)=");
            PlotWidgets.appendFixed2(sb2, (float) rCam);
            sb2.append('M');
        } else {
            float a = state.effectiveSpin();
            sb2.append("a*=");
            PlotWidgets.appendFixed3(sb2, a);
            sb2.append("  r+=");
            PlotWidgets.appendFixed2(sb2, (float) KerrMetric.outerHorizonRadius(a));
            sb2.append("M  r_ph+=");
            PlotWidgets.appendFixed2(sb2, (float) KerrMetric.progradePhotonSphereRadius(a));
            sb2.append("M  ISCO+=");
            PlotWidgets.appendFixed2(sb2, (float) KerrMetric.progradeIscoRadius(a));
            sb2.append("M  ISCO-=");
            PlotWidgets.appendFixed2(sb2, (float) KerrMetric.retrogradeIscoRadius(a));
            sb2.append('M');
        }

        sb3.setLength(0);
        if (state.binaryMode && binary != null) {
            sb3.append("Mf=");
            PlotWidgets.appendFixed3(sb3, binary.remnantMass);
            sb3.append(" af=");
            PlotWidgets.appendFixed3(sb3, binary.remnantSpin);
            sb3.append(" fGW=");
            PlotWidgets.appendFixed1(sb3, binary.gwFreqHz);
            sb3.append("Hz HM=");
            PlotWidgets.appendFixed1(sb3, (float) (binary.higherModePowerFrac * 100.0));
            sb3.append("% | E_BAL=");
            PlotWidgets.appendSci1(sb3, Math.abs(binary.eobEnergyBalanceErr));
            sb3.append(" | NON-PRECESSING ALIGNED | [V] PLASMA");
        } else if (state.wormholeMode) {
            sb3.append("SOLVER: RK4 + NULL REPROJ | GPU P95=");
            PlotWidgets.appendFixed2(sb3, telemetry.gpuP95Ms);
            sb3.append("ms | ULTRASTATIC g_tt=-1 (ZERO REDSHIFT) | EXOTIC NEC VIOLATION");
        } else {
            sb3.append("GPU P95=");
            PlotWidgets.appendFixed2(sb3, telemetry.gpuP95Ms);
            sb3.append("ms  P99=");
            PlotWidgets.appendFixed2(sb3, telemetry.gpuP99Ms);
            sb3.append("ms  ALLOC=").append(telemetry.renderAllocBytesLastFrame).append("B/f");
            if (tde != null && (tde.activeParticleCount() > 0 || state.tdeHydroVisMode > 0)) {
                sb3.append(" | TDE ").append(state.tdeHydroEnabled ? "64^3 HYDRO" : "BALLISTIC")
                   .append(" <eps>=");
                PlotWidgets.appendSigned3(sb3, tde.meanSpecificEnergy());
                sb3.append(" [NON-GRMHD]");
            } else {
                sb3.append(" | [V] PRESCRIPTION DISK (NON-GRMHD)");
                if (state.ehtPolarization) {
                    sb3.append(" | NO FARADAY ROT");
                }
            }
        }

        ui.drawText(sb1, x + 12f, y + 10f, 0.84f, 0.30f, 0.96f, 0.88f, 0.98f);
        ui.drawText(sb2, x + 12f, y + 27f, 0.84f, 0.92f, 0.95f, 0.98f, 0.92f);
        ui.drawText(sb3, x + 12f, y + 44f, 0.78f, 0.76f, 0.86f, 0.96f, 0.88f);
    }
}
