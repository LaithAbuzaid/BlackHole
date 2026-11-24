package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.graphics.TidalDisruptionSystem;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;

/**
 * Mode-specific scientific visualizer for Tidal Disruption Events (Phase 4).
 *
 * <p>Visualizes exact Paczynski-Wiita parabolic launch approach trajectories integrated via
 * {@link DormandPrince54Integrator}, tilted orbital-plane tidal radius {@code r_t} and
 * periapsis {@code r_p = r_t / beta} boundaries, bound/unbound/swallowed particle
 * classification &amp; timeline conservation plots, and calibrated legends/bounding boxes for
 * the 64^3 Eulerian hydrodynamic grid ({@code HydroGridBuffer}).</p>
 */
public final class TdeVisualizer {

    private static final int TRAJ_PTS = 120;

    private final Projection3D proj = new Projection3D();
    private final float[] ptA = new float[2];
    private final StringBuilder sb = new StringBuilder(140);

    private final DormandPrince54Integrator dopri = new DormandPrince54Integrator();
    private final IntegrationDiagnostics diag = new IntegrationDiagnostics();
    private final IntegratorConfig trajCfg = new IntegratorConfig(1e-7, 1e-9, 0.12, 1e-4, 0.45, 2500, 0.9);
    private final double[] launch6 = new double[6];
    private final double[] state6 = new double[6];

    private final OdeSystem paczynskiWiitaOde = (t, y, dydt) -> {
        double x = y[0], py = y[1], z = y[2];
        double r2 = x * x + py * py + z * z;
        double r = Math.max(2.35, Math.sqrt(r2));
        double denom = r - 2.0;
        double accelMag = 1.0 / (r * denom * denom);
        dydt[0] = y[3];
        dydt[1] = y[4];
        dydt[2] = y[5];
        dydt[3] = -accelMag * x;
        dydt[4] = -accelMag * py;
        dydt[5] = -accelMag * z;
    };

    // Cached Paczynski-Wiita parabolic approach trajectory + periapsis location
    private final float[] trajX = new float[TRAJ_PTS];
    private final float[] trajY = new float[TRAJ_PTS];
    private final float[] trajZ = new float[TRAJ_PTS];
    private int trajCount = 0;
    private int periIdx = 0;
    private float periMinR = 0f;
    private int cachedArchOrd = -1;
    private float cachedBeta = -1f;
    private float cachedInc = -999f;
    private float cachedSpin = -1f;
    private int cachedVariant = -1;

    /** Renders active hydro grid bounds/colorbar and particle color legend even outside [F3]. */
    public void renderAlwaysOnLayers(UiRenderer ui, int width, int height,
                                     SimulationState state, CameraController camera,
                                     TidalDisruptionSystem tde) {
        if (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR
                || state.scientificDiagMode == SimulationState.DIAG_STEP_DENSITY) {
            return;
        }
        float aspect = (float) width / Math.max(1, height);
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));
        float topY = (state.kerrDiskVisMode > 0) ? 92f : 18f;

        if (state.tdeHydroVisMode > 0) {
            if (state.visTdeOverlay) {
                proj.drawBoxWireframe(ui, width, height, aspect, tanHalfFov, camera,
                        24.0f, 1.15f, 0.28f, 0.84f, 1.00f, 0.44f);
                if (proj.projectPoint(-24.0f, 24.0f, 0.0f, camera, width, height, aspect, tanHalfFov, ptA)) {
                    ui.drawText("64^3 HYDRO GRID [-24M..+24M]", ptA[0] + 4f, ptA[1], 0.70f,
                            0.36f, 0.90f, 1.00f, 0.88f);
                }
            }
            topY = renderHydroColorbar(ui, width, topY, state);
        }

        if (state.tdeParticleColorMode == SimulationState.TDE_COLOR_VELOCITY
                || state.tdeParticleColorMode == SimulationState.TDE_COLOR_TEMP) {
            renderParticleScalarColorbar(ui, width, topY, state);
        } else if (state.tdeParticleColorMode == SimulationState.TDE_COLOR_BOUND_UNBOUND
                && (state.scientificDiagMode != SimulationState.DIAG_ANALYTICAL_OVERLAY || !state.visTdeOverlay)) {
            renderCompactBoundLegend(ui, width, topY);
        }
    }

    /** Renders full TDE orbital boundaries, approach curve, and 2D debris timeline panel ([F3] ANALYTICAL). */
    public void renderAnalytical(UiRenderer ui, int width, int height,
                                 SimulationState state, CameraController camera,
                                 TidalDisruptionSystem tde) {
        if (!state.visTdeOverlay || tde == null) {
            return;
        }
        boolean hasTde = tde.activeParticleCount() > 0
                || tde.totalSwallowedCount() > 0
                || state.tdeHydroVisMode > 0;
        if (!hasTde) {
            return;
        }

        boolean fromLiveLaunch = (tde.activeParticleCount() > 0 || tde.totalSwallowedCount() > 0);
        StellarArchetype arch = fromLiveLaunch
                ? tde.lastArchetype() : StellarArchetype.fromIndex(state.tdeArchetypeIndex);
        float beta = fromLiveLaunch ? tde.lastBeta() : state.tdeBeta;
        float incDeg = fromLiveLaunch ? tde.lastInclinationDeg() : state.tdeInclinationDeg;
        float spinA = fromLiveLaunch ? tde.lastSpinA() : state.effectiveSpin();
        int variant = fromLiveLaunch ? tde.lastLaunchVariant() : 0;

        float aspect = (float) width / Math.max(1, height);
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));

        renderTde3dOverlays(ui, width, height, aspect, tanHalfFov, camera, arch, beta, incDeg, spinA, variant);
        renderTdeDiagnosticsPanel(ui, width, height, state, tde, arch, beta, incDeg, spinA);
    }

    private void renderTde3dOverlays(UiRenderer ui, int width, int height, float aspect, float tanHalfFov,
                                     CameraController camera, StellarArchetype arch,
                                     float beta, float incDeg, float spinA, int variant) {
        updateTrajectoryCache(arch, beta, incDeg, spinA, variant);

        double az = 0.52 + (variant % 5) * 1.18;
        double incRad = Math.toRadians(Math.max(-65.0, Math.min(65.0, incDeg)));
        double cAz = Math.cos(az), sAz = Math.sin(az);
        double cInc = Math.cos(incRad), sInc = Math.sin(incRad);

        float u1x = (float) cAz, u1y = 0f, u1z = (float) sAz;
        float u2x = (float) (-sAz * cInc), u2y = (float) sInc, u2z = (float) (cAz * cInc);

        double rPlus = KerrMetric.outerHorizonRadius(spinA);
        float rTidal = arch.tidalRadius;
        float rPeri = (float) arch.computePeriapsis(beta, rPlus);

        // Tidal Disruption Sphere / Orbital Ring r_t (amber dashed)
        proj.drawBasisRing(ui, width, height, aspect, tanHalfFov, camera,
                0f, 0f, 0f, u1x, u1y, u1z, u2x, u2y, u2z,
                rTidal, 56, 1.5f, true, 1.00f, 0.72f, 0.24f, 0.84f);
        if (proj.projectPoint(u1x * rTidal, u1y * rTidal, u1z * rTidal, camera, width, height, aspect, tanHalfFov, ptA)) {
            sb.setLength(0);
            sb.append("r_t=");
            PlotWidgets.appendFixed1(sb, rTidal);
            sb.append("M (TIDAL)");
            ui.drawText(sb, ptA[0] + 5f, ptA[1], 0.72f, 1.00f, 0.78f, 0.28f, 0.94f);
        }

        // Periapsis Ring r_p = r_t / beta (cyan dashed)
        proj.drawBasisRing(ui, width, height, aspect, tanHalfFov, camera,
                0f, 0f, 0f, u1x, u1y, u1z, u2x, u2y, u2z,
                rPeri, 48, 1.5f, true, 0.30f, 0.92f, 1.00f, 0.88f);

        // Integrated Paczynski-Wiita reference approach & tidal stream path
        for (int i = 0; i < trajCount - 1; i++) {
            boolean postPeri = i >= periIdx;
            float cr = postPeri ? 1.00f : 0.34f;
            float cg = postPeri ? 0.62f : 0.92f;
            float cb = postPeri ? 0.22f : 1.00f;
            proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                    trajX[i], trajY[i], trajZ[i],
                    trajX[i + 1], trajY[i + 1], trajZ[i + 1],
                    1.65f, (i & 1) == 1, cr, cg, cb, 0.82f);
        }

        if (trajCount > 0) {
            float vx = (float) launch6[3] * 8.0f;
            float vy = (float) launch6[4] * 8.0f;
            float vz = (float) launch6[5] * 8.0f;
            proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                    trajX[0], trajY[0], trajZ[0],
                    trajX[0] + vx, trajY[0] + vy, trajZ[0] + vz,
                    1.9f, 6.0f, 0.44f, 1.00f, 0.76f, 0.94f);
            if (proj.projectPoint(trajX[0], trajY[0], trajZ[0], camera, width, height, aspect, tanHalfFov, ptA)) {
                sb.setLength(0);
                sb.append("LAUNCH: ").append(arch.shortLabel);
                ui.drawText(sb, ptA[0] + 6f, ptA[1] - 10f, 0.72f, 0.44f, 1.00f, 0.76f, 0.96f);
            }
            if (proj.projectPoint(trajX[periIdx], trajY[periIdx], trajZ[periIdx],
                    camera, width, height, aspect, tanHalfFov, ptA)) {
                ui.drawCircleOutline(ptA[0], ptA[1], 5f, 16, 1.5f, 0.30f, 0.94f, 1.00f, 0.94f);
                sb.setLength(0);
                sb.append("r_p=");
                PlotWidgets.appendFixed2(sb, Math.max(rPeri, periMinR));
                sb.append("M (beta=");
                PlotWidgets.appendFixed2(sb, beta);
                sb.append(')');
                ui.drawText(sb, ptA[0] + 8f, ptA[1] - 4f, 0.72f, 0.34f, 0.94f, 1.00f, 0.96f);
            }
        }
    }

    private void renderTdeDiagnosticsPanel(UiRenderer ui, int width, int height,
                                           SimulationState state, TidalDisruptionSystem tde,
                                           StellarArchetype arch, float beta, float incDeg, float spinA) {
        boolean fullHud = (state.hudMode == SimulationState.HUD_FULL);
        float bottomGuard = (state.hudMode == SimulationState.HUD_CLEAN) ? 84f : 124f;
        float pw = Math.min(352f, Math.max(264f, width * 0.25f));
        float px = 16f;
        float py = fullHud ? 416f : (state.hudMode == SimulationState.HUD_COMPACT ? 54f : 18f);
        float availH = (height - bottomGuard) - py;
        if (availH < 210f) {
            return;
        }
        float ph = Math.min(386f, availH);

        ui.drawGlassPanel(px, py, pw, ph, 9f, 0.04f, 0.05f, 0.09f, 0.88f, 1.00f, 0.64f, 0.28f, 0.56f);
        PlotWidgets.drawPanelHeader(ui, px, py, pw, "TDE HYDRO & DEBRIS LAB",
                state.tdeHydroEnabled ? "[A] PW+HYDRO" : "[A] BALLISTIC", 1.00f, 0.72f, 0.34f);

        float ty = py + 24f;
        double rPlus = KerrMetric.outerHorizonRadius(spinA);
        float rPeri = (float) arch.computePeriapsis(beta, rPlus);
        sb.setLength(0);
        sb.append(arch.shortLabel).append(" | beta=");
        PlotWidgets.appendFixed2(sb, beta);
        sb.append(" (").append(StellarArchetype.betaRegimeLabel(beta)).append(')');
        ui.drawText(sb, px + 10f, ty, 0.76f, 0.94f, 0.96f, 1.00f, 0.96f);
        ty += 14f;

        sb.setLength(0);
        sb.append("r_t=");
        PlotWidgets.appendFixed2(sb, arch.tidalRadius);
        sb.append("M  r_p=");
        PlotWidgets.appendFixed2(sb, rPeri);
        sb.append("M  inc=");
        PlotWidgets.appendSigned1(sb, incDeg);
        sb.append(" deg");
        ui.drawText(sb, px + 10f, ty, 0.72f, 0.78f, 0.88f, 0.98f, 0.92f);
        ty += 16f;

        // Live Debris State Classification Counts & Fraction Bars
        int active = tde.activeParticleCount();
        int swall = tde.totalSwallowedCount();
        int totalRef = Math.max(1, active + swall);
        float boundFrac = (float) tde.boundParticleCount() / totalRef;
        float unboundFrac = (float) tde.unboundParticleCount() / totalRef;
        float swallFrac = (float) swall / totalRef;

        ui.drawText("DEBRIS CLASSIFICATION (" + active + " ACT / " + swall + " SWALL):",
                px + 10f, ty, 0.70f, 0.96f, 0.82f, 0.44f, 0.94f);
        ty += 13f;

        sb.setLength(0);
        sb.append(Math.round(boundFrac * 100f)).append('%');
        PlotWidgets.drawHorizontalBar(ui, px + 10f, ty, pw - 20f, 7f, boundFrac, "BOUND", sb, 0.24f, 0.72f, 1.00f);
        ty += 13f;

        sb.setLength(0);
        sb.append(Math.round(unboundFrac * 100f)).append('%');
        PlotWidgets.drawHorizontalBar(ui, px + 10f, ty, pw - 20f, 7f, unboundFrac, "UNBND", sb, 1.00f, 0.56f, 0.16f);
        ty += 13f;

        sb.setLength(0);
        sb.append(Math.round(swallFrac * 100f)).append('%');
        PlotWidgets.drawHorizontalBar(ui, px + 10f, ty, pw - 20f, 7f, swallFrac, "ACCR ", sb, 0.98f, 0.36f, 0.88f);
        ty += 15f;

        // Color swatch legend for Bound/Unbound visualization mode
        if (state.tdeParticleColorMode == SimulationState.TDE_COLOR_BOUND_UNBOUND && ph >= 320f) {
            drawLegendDot(ui, px + 10f, ty, 1.00f, 0.34f, 1.00f, "HOT SHEAR r<8.5M");
            drawLegendDot(ui, px + pw * 0.5f, ty, 0.20f, 0.98f, 0.72f, "INFALLING e<0");
            ty += 12f;
            drawLegendDot(ui, px + 10f, ty, 0.24f, 0.68f, 1.00f, "BOUND FALLBACK");
            drawLegendDot(ui, px + pw * 0.5f, ty, 1.00f, 0.56f, 0.16f, "UNBOUND ESCAPE");
            ty += 14f;
        }

        // Timeline Plots from TidalDisruptionSystem ring buffer
        float plotH = Math.max(46f, (py + ph - ty - 62f) * 0.48f);
        int count = tde.histCount();
        int start = tde.histStartIdx();

        ui.drawGlassPanel(px + 10f, ty, pw - 20f, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.86f, 1f, 1f, 1f, 0.14f);
        PlotWidgets.drawTimeSeriesFloat(ui, px + 13f, ty + 3f, pw - 26f, plotH - 6f,
                tde.histBoundFrac(), start, count, TidalDisruptionSystem.TDE_HIST_LEN, 0f, 1f, 1.55f, false,
                0.24f, 0.72f, 1.00f, 0.95f);
        PlotWidgets.drawTimeSeriesFloat(ui, px + 13f, ty + 3f, pw - 26f, plotH - 6f,
                tde.histUnboundFrac(), start, count, TidalDisruptionSystem.TDE_HIST_LEN, 0f, 1f, 1.55f, false,
                1.00f, 0.56f, 0.16f, 0.95f);
        PlotWidgets.drawTimeSeriesFloat(ui, px + 13f, ty + 3f, pw - 26f, plotH - 6f,
                tde.histSwallowedFrac(), start, count, TidalDisruptionSystem.TDE_HIST_LEN, 0f, 1f, 1.35f, true,
                0.98f, 0.36f, 0.88f, 0.90f);
        ui.drawText("DEBRIS FRAC(t): BND(CYN) UNB(ORG) ACC(MAG)", px + 14f, ty + 4f, 0.66f, 0.88f, 0.94f, 1.00f, 0.92f);
        ty += plotH + 6f;

        // Plot 2: Mean Specific Energy <E> over Time
        ui.drawGlassPanel(px + 10f, ty, pw - 20f, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.86f, 1f, 1f, 1f, 0.14f);
        float zeroY = ty + plotH * 0.5f;
        ui.drawDashedLine(px + 13f, zeroY, px + pw - 13f, zeroY, 1.0f, 3f, 3f, 1f, 1f, 1f, 0.22f);
        PlotWidgets.drawTimeSeriesFloat(ui, px + 13f, ty + 3f, pw - 26f, plotH - 6f,
                tde.histMeanEnergy(), start, count, TidalDisruptionSystem.TDE_HIST_LEN,
                -0.12f, 0.12f, 1.55f, false, 0.32f, 0.96f, 0.72f, 0.95f);
        ui.drawText("MEAN ENERGY <eps>(t) [-0.12 .. +0.12]", px + 14f, ty + 4f, 0.66f, 0.42f, 0.98f, 0.78f, 0.92f);
        ty += plotH + 6f;

        sb.setLength(0);
        sb.append("<eps>=");
        PlotWidgets.appendSigned3(sb, tde.meanSpecificEnergy());
        sb.append(" <Ly>=");
        PlotWidgets.appendFixed2(sb, tde.meanAngularMomentumY());
        sb.append(" Tp=").append(tde.peakTemperatureK() / 1000).append("kK");
        ui.drawText(sb, px + 10f, ty, 0.70f, 0.88f, 0.94f, 1.00f, 0.94f);
        ty += 13f;

        sb.setLength(0);
        sb.append("dN/dt=");
        PlotWidgets.appendFixed1(sb, tde.lastSwallowRatePerSec());
        sb.append("/s | SUBSTEPS=").append(tde.lastSubstepsPerFrame())
          .append(" | HYDRO:").append(state.tdeHydroEnabled ? "ON" : "OFF");
        ui.drawText(sb, px + 10f, ty, 0.70f, 0.74f, 0.86f, 0.98f, 0.90f);

        ui.drawText("[A] PW + BORIS LT + 64^3 POLYTROPIC HYDRO (NON-GRMHD)",
                px + 10f, py + ph - 14f, 0.64f, 0.98f, 0.78f, 0.40f, 0.90f);
    }

    private float renderHydroColorbar(UiRenderer ui, int width, float y, SimulationState state) {
        float bw = 294f;
        float bh = 74f;
        float bx = (width - bw) * 0.5f;
        String title = switch (state.tdeHydroVisMode) {
            case SimulationState.TDE_HYDRO_VIS_DENSITY -> "HYDRO 64^3 LOG DENSITY [V]";
            case SimulationState.TDE_HYDRO_VIS_PRESSURE -> "HYDRO 64^3 POLYTROPIC P [V]";
            case SimulationState.TDE_HYDRO_VIS_VELOCITY -> "HYDRO 64^3 BULK VEL |v|/c [V]";
            case SimulationState.TDE_HYDRO_VIS_SHOCK -> "HYDRO 64^3 SHOCK -div(v) [V]";
            default -> "HYDRO 64^3 3D VOL DENS [V]";
        };
        String minL = switch (state.tdeHydroVisMode) {
            case SimulationState.TDE_HYDRO_VIS_VELOCITY -> "0.00c";
            case SimulationState.TDE_HYDRO_VIS_SHOCK -> "0.0 (LAM)";
            default -> "0.0";
        };
        String midL = switch (state.tdeHydroVisMode) {
            case SimulationState.TDE_HYDRO_VIS_VELOCITY -> "0.40c";
            case SimulationState.TDE_HYDRO_VIS_PRESSURE -> "K*rho^5/3";
            default -> "MID";
        };
        String maxL = switch (state.tdeHydroVisMode) {
            case SimulationState.TDE_HYDRO_VIS_VELOCITY -> ">0.80c";
            case SimulationState.TDE_HYDRO_VIS_SHOCK -> "PEAK COMP";
            default -> "PEAK";
        };
        PlotWidgets.drawColorbar(ui, bx, y, bw, bh, title, minL, midL, maxL, PlotWidgets.CMAP_TURBO);
        String sub = (state.tdeHydroVisMode == SimulationState.TDE_HYDRO_VIS_VOLUME)
                ? "44-STEP VOL RAYMARCH | dx=0.75M | [-24M,+24M]^3"
                : ("SLICE: " + (state.tdeHydroSlicePlane == SimulationState.TDE_SLICE_XY ? "XY (z=0)" : "XZ (y=0)")
                        + " | dx=0.75M (NON-GRMHD)");
        ui.drawText(sub, bx + 10f, y + 57f, 0.66f, 0.98f, 0.80f, 0.44f, 0.90f);
        return y + bh + 8f;
    }

    private void renderParticleScalarColorbar(UiRenderer ui, int width, float y, SimulationState state) {
        float bw = 278f;
        float bh = 66f;
        float bx = (width - bw) * 0.5f;
        if (state.tdeParticleColorMode == SimulationState.TDE_COLOR_VELOCITY) {
            PlotWidgets.drawColorbar(ui, bx, y, bw, bh, "TDE PARTICLE SPEED |v|/c",
                    "0.00c", "0.42c", ">0.85c", PlotWidgets.CMAP_TURBO);
        } else {
            PlotWidgets.drawColorbar(ui, bx, y, bw, bh, "TDE STREAM TEMP T [K]",
                    "3000 K", "20500 K", "38000 K", PlotWidgets.CMAP_TURBO);
        }
        ui.drawText("[V] PARTICLE COLOR OVERRIDE", bx + 12f, y + 52f, 0.66f, 0.82f, 0.92f, 1.00f, 0.88f);
    }

    private void renderCompactBoundLegend(UiRenderer ui, int width, float y) {
        float bw = 312f;
        float bh = 54f;
        float bx = (width - bw) * 0.5f;
        ui.drawGlassPanel(bx, y, bw, bh, 7f, 0.03f, 0.05f, 0.09f, 0.88f, 0.34f, 0.86f, 1.00f, 0.52f);
        PlotWidgets.drawPanelHeader(ui, bx, y, bw, "TDE PARTICLE ENERGY STATE", "[A] PW eps", 0.38f, 0.92f, 1.00f);
        drawLegendDot(ui, bx + 10f, y + 23f, 0.24f, 0.68f, 1.00f, "BOUND e<0");
        drawLegendDot(ui, bx + 112f, y + 23f, 0.20f, 0.98f, 0.72f, "INFALL e<0");
        drawLegendDot(ui, bx + 214f, y + 23f, 1.00f, 0.56f, 0.16f, "UNBOUND");
        drawLegendDot(ui, bx + 10f, y + 37f, 1.00f, 0.34f, 1.00f, "HOT SHEAR r<8.5M, T>10.5kK");
    }

    private static void drawLegendDot(UiRenderer ui, float x, float y, float r, float g, float b, String text) {
        ui.drawRect(x, y + 2.5f, 7f, 7f, 2f, r, g, b, 0.96f);
        ui.drawText(text, x + 11f, y, 0.66f, 0.88f, 0.94f, 0.98f, 0.92f);
    }

    private void updateTrajectoryCache(StellarArchetype arch, float beta, float incDeg, float spinA, int variant) {
        if (arch.ordinal() == cachedArchOrd && Math.abs(beta - cachedBeta) < 1e-3f
                && Math.abs(incDeg - cachedInc) < 0.05f && Math.abs(spinA - cachedSpin) < 1e-3f
                && variant == cachedVariant && trajCount > 0) {
            return;
        }
        cachedArchOrd = arch.ordinal();
        cachedBeta = beta;
        cachedInc = incDeg;
        cachedSpin = spinA;
        cachedVariant = variant;

        TdeInitialConditions.computeLaunchOrbit(arch, beta, incDeg, spinA, variant, launch6);
        System.arraycopy(launch6, 0, state6, 0, 6);

        trajX[0] = (float) state6[0];
        trajY[0] = (float) state6[1];
        trajZ[0] = (float) state6[2];
        double startR = Math.sqrt(state6[0] * state6[0] + state6[1] * state6[1] + state6[2] * state6[2]);

        final int[] idx = {1};
        final int[] bestIdx = {0};
        final double[] bestR = {startR};
        final boolean[] passedPeri = {false};

        dopri.integrate(paczynskiWiitaOde, 0.0, state6, 130.0, trajCfg, diag, (t, y, h) -> {
            double r = Math.sqrt(y[0] * y[0] + y[1] * y[1] + y[2] * y[2]);
            double prevR = Math.sqrt(trajX[idx[0] - 1] * trajX[idx[0] - 1]
                    + trajY[idx[0] - 1] * trajY[idx[0] - 1]
                    + trajZ[idx[0] - 1] * trajZ[idx[0] - 1]);
            double vr = y[0] * y[3] + y[1] * y[4] + y[2] * y[5];
            if (vr > 0.0 && prevR < startR * 0.9) {
                passedPeri[0] = true;
            }
            // Sample densely enough without running out before exit
            if (idx[0] < TRAJ_PTS && ((idx[0] & 1) == 0 || r < bestR[0] + 2.0 || (passedPeri[0] && r > startR * 1.05))) {
                int i = idx[0];
                trajX[i] = (float) y[0];
                trajY[i] = (float) y[1];
                trajZ[i] = (float) y[2];
                if (r < bestR[0]) {
                    bestR[0] = r;
                    bestIdx[0] = i;
                }
                idx[0]++;
            }
            return idx[0] < TRAJ_PTS && r > 2.28 && !(passedPeri[0] && r > startR * 1.25);
        });
        trajCount = idx[0];
        periIdx = bestIdx[0];
        periMinR = (float) bestR[0];
    }
}
