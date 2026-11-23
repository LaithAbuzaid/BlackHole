package io.github.laithabuzaid.blackhole.ui;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;

/**
 * Zero-allocation Scientific Visualization &amp; Diagnostics renderer ({@code [F3]} mode).
 *
 * <p>Every graphical element rendered here corresponds strictly to a computed mathematical quantity:
 * <ul>
 *   <li><b>Analytical Kerr Boundaries ({@code DIAG_ANALYTICAL_OVERLAY}):</b>
 *       Exact Bardeen (1973) critical shadow curve {@code (alpha(r_p), beta(r_p))}, outer horizon
 *       {@code r_+}, ergosphere {@code r_ergo(theta)}, prograde photon sphere {@code r_{ph+}}, and
 *       4 live equatorial null-geodesic photon trajectories integrated via
 *       {@link DormandPrince54Integrator} + {@link KerrMetric#nullGeodesicOde}.</li>
 *   <li><b>GPU Heatmap Legends ({@code DIAG_HAMILTONIAN_ERR} / {@code DIAG_STEP_DENSITY}):</b>
 *       Calibrated Turbo colorbar for per-ray {@code log10(|H_ham|)} or accepted step counts.</li>
 *   <li><b>Scientific Status Strip:</b> Active coordinate system, integrator &amp; tolerance,
 *       exact metric radii, conservation quantities, and explicit approximation disclosure.</li>
 * </ul>
 */
public final class ScientificVisualizer {

    private static final int BARDEEN_SAMPLES = 96;
    private static final int GEODESIC_COUNT = 4;
    private static final int MAX_GEO_PTS = 160;
    private static final int RING_SEGS = 64;

    // Cached Bardeen critical curve points in observer sky plane (alpha, +beta)
    private final float[] bardeenAlpha = new float[BARDEEN_SAMPLES];
    private final float[] bardeenBeta = new float[BARDEEN_SAMPLES];
    private int bardeenCount = 0;
    private float cachedBardeenSpin = -1.0f;
    private float cachedBardeenInc = -10.0f;

    // Cached DOPRI5(4) equatorial null geodesic trajectories (zero per-frame allocation)
    private final float[][] geoX = new float[GEODESIC_COUNT][MAX_GEO_PTS];
    private final float[][] geoZ = new float[GEODESIC_COUNT][MAX_GEO_PTS];
    private final int[] geoLen = new int[GEODESIC_COUNT];
    private final boolean[] geoCaptured = new boolean[GEODESIC_COUNT];
    private float cachedGeoSpin = -1.0f;

    private final DormandPrince54Integrator dopri = new DormandPrince54Integrator();
    private final IntegrationDiagnostics diag = new IntegrationDiagnostics();
    private final IntegratorConfig geoCfg = new IntegratorConfig(1e-7, 1e-9, 0.12, 1e-4, 0.6, 2000, 0.9);
    private final double[] scratchPt2 = new double[2];
    private final double[] scratchState6 = new double[6];
    private final float[] projA = new float[2];
    private final float[] projB = new float[2];
    private final StringBuilder sb1 = new StringBuilder(140);
    private final StringBuilder sb2 = new StringBuilder(140);
    private final StringBuilder sb3 = new StringBuilder(140);

    public void render(UiRenderer ui, int width, int height,
                       SimulationState state, CameraController camera,
                       BinaryMergerPhysics binary, HardwareTelemetry telemetry) {
        if (state.scientificDiagMode == SimulationState.DIAG_OFF) {
            return;
        }

        if (state.scientificDiagMode == SimulationState.DIAG_ANALYTICAL_OVERLAY
                && !state.wormholeMode && !state.binaryMode) {
            renderAnalyticalKerrOverlay(ui, width, height, state, camera);
        }

        if (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR
                || state.scientificDiagMode == SimulationState.DIAG_STEP_DENSITY) {
            renderColorbarLegend(ui, width, height, state);
        }

        renderScientificStatusBanner(ui, width, height, state, binary, telemetry);
    }

    private void renderAnalyticalKerrOverlay(UiRenderer ui, int width, int height,
                                             SimulationState state, CameraController camera) {
        float a = state.effectiveSpin();
        float rCam = (float) Math.sqrt(camera.posX * camera.posX
                + camera.posY * camera.posY + camera.posZ * camera.posZ);
        if (rCam < 3.0f) {
            return;
        }
        float cosThetaObs = Math.max(-0.999f, Math.min(0.999f, camera.posY / rCam));
        float incRad = (float) Math.acos(Math.abs(cosThetaObs));
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));
        float aspect = (float) width / Math.max(1, height);

        // 1. Project exact Bardeen (1973) analytical critical shadow curve over the raytraced shadow
        updateBardeenCache(a, incRad);
        if (projectOrigin(camera, width, height, aspect, tanHalfFov, projA)) {
            // Exact static-shell finite-distance angular mapping: sin(psi) = b * sqrt(1 - 2/rCam) / rCam
            float finiteScale = (float) Math.sqrt(Math.max(0.15, 1.0 - 2.0 / Math.max(2.5, rCam)));
            float halfH = height * 0.5f;
            float invTanFov = 1.0f / Math.max(1e-4f, tanHalfFov);
            float cx = projA[0];
            float cy = projA[1];

            // Spin J = (0, -a, 0) puts prograde approaching limb on screen-left (-alpha), retrograde on screen-right (+alpha)
            float minX = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = cy;
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < bardeenCount - 1; i++) {
                    float x0 = projectBardeenCoord(cx, bardeenAlpha[i], bardeenBeta[i], finiteScale, rCam, halfH, invTanFov, true, 1);
                    float y0 = projectBardeenCoord(cy, bardeenAlpha[i], bardeenBeta[i], finiteScale, rCam, halfH, invTanFov, false, side);
                    float x1 = projectBardeenCoord(cx, bardeenAlpha[i + 1], bardeenBeta[i + 1], finiteScale, rCam, halfH, invTanFov, true, 1);
                    float y1 = projectBardeenCoord(cy, bardeenAlpha[i + 1], bardeenBeta[i + 1], finiteScale, rCam, halfH, invTanFov, false, side);
                    ui.drawLine(x0, y0, x1, y1, 2.0f, 0.24f, 0.96f, 0.88f, 0.92f);
                    if (x0 < minX) minX = x0;
                    if (x0 > maxX) maxX = x0;
                    if (y0 > maxY) maxY = y0;
                }
            }
            if (bardeenCount > 1 && minX < maxX) {
                float midX = 0.5f * (minX + maxX);
                ui.drawText("BARDEEN SHADOW (ANALYTIC)", midX - 92f, maxY + 12f,
                        0.92f, 0.24f, 0.96f, 0.88f, 0.96f);
            }
        }

        // 2. Project exact equatorial rings: Outer Horizon r_+, Prograde Photon Sphere r_{ph+}, Ergosphere r_ergo = 2M
        float rPlus = (float) KerrMetric.outerHorizonRadius(a);
        float rPh = (float) KerrMetric.progradePhotonSphereRadius(a);
        drawEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, rPlus, 1.5f, 1.00f, 0.34f, 0.32f, 0.82f);
        drawEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, rPh,   1.4f, 1.00f, 0.80f, 0.26f, 0.80f);
        if (a > 0.05f) {
            drawEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 2.0f, 1.3f, 0.72f, 0.52f, 1.00f, 0.74f);
        }

        // 3. Project 4 live DOPRI5(4)-integrated equatorial null-geodesic photon trajectories
        updateGeodesicCache(a);
        for (int g = 0; g < GEODESIC_COUNT; g++) {
            int n = geoLen[g];
            boolean cap = geoCaptured[g];
            float cr = cap ? 1.00f : (g == 1 ? 1.00f : (g == 2 ? 0.34f : 0.48f));
            float cg = cap ? 0.34f : (g == 1 ? 0.86f : (g == 2 ? 0.86f : 0.66f));
            float cb = cap ? 0.30f : (g == 1 ? 0.24f : (g == 2 ? 1.00f : 1.00f));
            for (int i = 0; i < n - 1; i++) {
                if (projectPoint(geoX[g][i], 0.0f, geoZ[g][i], camera, width, height, aspect, tanHalfFov, projA)
                        && projectPoint(geoX[g][i + 1], 0.0f, geoZ[g][i + 1], camera, width, height, aspect, tanHalfFov, projB)) {
                    ui.drawLine(projA[0], projA[1], projB[0], projB[1], 1.7f, cr, cg, cb, 0.86f);
                }
            }
        }

        // 4. Compact scientific curve legend (top-left, clear of HUD telemetry pill)
        float legX = 20f;
        float legY = (state.hudMode == SimulationState.HUD_CLEAN) ? 20f : 124f;
        float legW = 248f;
        float legH = 112f;
        ui.drawGlassPanel(legX, legY, legW, legH, 8f, 0.03f, 0.05f, 0.09f, 0.86f, 0.24f, 0.86f, 0.82f, 0.48f);
        ui.drawText("ANALYTICAL OVERLAY LEGEND", legX + 12f, legY + 10f, 0.86f, 0.72f, 0.86f, 0.96f, 0.94f);
        drawLegendItem(ui, legX + 12f, legY + 29f, 0.24f, 0.96f, 0.88f, "Bardeen Critical Shadow");
        drawLegendItem(ui, legX + 12f, legY + 45f, 1.00f, 0.34f, 0.32f, "Outer Horizon r_+ / Captured");
        drawLegendItem(ui, legX + 12f, legY + 61f, 1.00f, 0.80f, 0.26f, "Photon Sphere r_ph+ / Critical");
        drawLegendItem(ui, legX + 12f, legY + 77f, 0.72f, 0.52f, 1.00f, "Equatorial Ergosphere (r=2M)");
        drawLegendItem(ui, legX + 12f, legY + 93f, 0.38f, 0.84f, 1.00f, "DOPRI5(4) Deflected Geodesics");
    }

    private static void drawLegendItem(UiRenderer ui, float x, float y, float r, float g, float b, String label) {
        ui.drawRect(x, y + 3f, 14f, 3f, 1.5f, r, g, b, 0.95f);
        ui.drawText(label, x + 22f, y, 0.84f, 0.88f, 0.92f, 0.96f, 0.92f);
    }

    private static float projectBardeenCoord(float center, float alpha, float beta,
                                             float finiteScale, float rCam, float halfH,
                                             float invTanFov, boolean isX, int side) {
        float sx = (alpha * finiteScale) / rCam;
        float sy = (beta * finiteScale) / rCam;
        float sin2 = Math.min(0.95f, sx * sx + sy * sy);
        float invCos = (float) (1.0 / Math.sqrt(Math.max(0.05, 1.0 - sin2)));
        float val = isX ? (sx * invCos) : (-side * sy * invCos);
        return center + val * invTanFov * halfH;
    }

    private void drawEquatorialRing(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                    CameraController cam, float radius, float thickness,
                                    float r, float g, float b, float a) {
        for (int i = 0; i < RING_SEGS; i++) {
            double phi0 = (2.0 * Math.PI * i) / RING_SEGS;
            double phi1 = (2.0 * Math.PI * (i + 1)) / RING_SEGS;
            float x0 = (float) (radius * Math.cos(phi0));
            float z0 = (float) (radius * Math.sin(phi0));
            float x1 = (float) (radius * Math.cos(phi1));
            float z1 = (float) (radius * Math.sin(phi1));
            if (projectPoint(x0, 0f, z0, cam, w, h, aspect, tanHalfFov, projA)
                    && projectPoint(x1, 0f, z1, cam, w, h, aspect, tanHalfFov, projB)) {
                ui.drawLine(projA[0], projA[1], projB[0], projB[1], thickness, r, g, b, a);
            }
        }
    }

    private void renderColorbarLegend(UiRenderer ui, int width, int height, SimulationState state) {
        float bw = 240f;
        float bh = 58f;
        float bx = (width - bw) * 0.5f;
        float by = 18f;
        ui.drawGlassPanel(bx, by, bw, bh, 8f, 0.04f, 0.06f, 0.10f, 0.88f, 0.35f, 0.72f, 0.96f, 0.52f);

        String title = (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR)
                ? "NULL CONSTRAINT ERROR log10(|H|)"
                : "GEODESIC INTEGRATOR STEP COUNT";
        ui.drawText(title, bx + 10f, by + 9f, 0.92f, 0.88f, 0.94f, 1.00f, 0.96f);

        int segs = 32;
        float barX = bx + 12f;
        float barY = by + 26f;
        float barW = bw - 24f;
        float segW = barW / segs;
        for (int i = 0; i < segs; i++) {
            float t = i / (float) (segs - 1);
            float r, g, b;
            if (t < 0.25f) {
                float u = t * 4f;
                r = 0.11f + (0.09f - 0.11f) * u; g = 0.08f + (0.56f - 0.08f) * u; b = 0.34f + (0.78f - 0.34f) * u;
            } else if (t < 0.50f) {
                float u = (t - 0.25f) * 4f;
                r = 0.09f + (0.55f - 0.09f) * u; g = 0.56f + (0.88f - 0.56f) * u; b = 0.78f + (0.24f - 0.78f) * u;
            } else if (t < 0.75f) {
                float u = (t - 0.50f) * 4f;
                r = 0.55f + (0.98f - 0.55f) * u; g = 0.88f + (0.55f - 0.88f) * u; b = 0.24f + (0.09f - 0.24f) * u;
            } else {
                float u = (t - 0.75f) * 4f;
                r = 0.98f + (0.84f - 0.98f) * u; g = 0.55f + (0.12f - 0.55f) * u; b = 0.09f + (0.14f - 0.09f) * u;
            }
            ui.drawRect(barX + i * segW, barY, segW + 0.5f, 10f, 0f, r, g, b, 0.96f);
        }

        if (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR) {
            ui.drawText("1e-7", barX, barY + 14f, 0.86f, 0.65f, 0.82f, 0.95f, 0.90f);
            ui.drawText("1e-4.5", barX + barW * 0.42f, barY + 14f, 0.86f, 0.78f, 0.92f, 0.62f, 0.90f);
            ui.drawText("1e-2", barX + barW - 28f, barY + 14f, 0.86f, 0.98f, 0.46f, 0.36f, 0.90f);
        } else {
            ui.drawText("0", barX, barY + 14f, 0.86f, 0.65f, 0.82f, 0.95f, 0.90f);
            ui.drawText("100", barX + barW * 0.45f, barY + 14f, 0.86f, 0.78f, 0.92f, 0.62f, 0.90f);
            ui.drawText("200", barX + barW - 22f, barY + 14f, 0.86f, 0.98f, 0.46f, 0.36f, 0.90f);
        }
    }

    private void renderScientificStatusBanner(UiRenderer ui, int width, int height,
                                              SimulationState state, BinaryMergerPhysics binary,
                                              HardwareTelemetry telemetry) {
        float w = Math.min(780f, width - 40f);
        float h = 64f;
        float x = (width - w) * 0.5f;
        float y = height - 118f;

        ui.drawGlassPanel(x, y, w, h, 8f, 0.03f, 0.05f, 0.09f, 0.88f, 0.24f, 0.86f, 0.82f, 0.55f);

        sb1.setLength(0);
        sb1.append("[F3] SCI MODE: ").append(state.scientificDiagLabel());
        if (state.binaryMode) {
            sb1.append("  |  EOB 3PN+NLO-SPIN + EFFECTIVE BOOSTED KERR-SCHILD (")
               .append(state.integratorMode == SimulationState.INTEGRATOR_DOPRI54 ? "DOPRI5(4)" : "RK4")
               .append(')');
        } else if (state.wormholeMode) {
            sb1.append("  |  EXACT MORRIS-THORNE DNEG METRIC (EXOTIC NEC VIOLATION)");
        } else {
            sb1.append("  |  EXACT KERR-SCHILD NULL GEODESICS (G=c=M=1)");
        }

        sb2.setLength(0);
        if (state.binaryMode && binary != null) {
            sb2.append("q=");
            appendFixed2(sb2, binary.massRatioQ);
            sb2.append(" eta=");
            appendFixed3(sb2, binary.symMassRatio);
            sb2.append(" chi=");
            appendFixed2(sb2, binary.chi1);
            sb2.append('/');
            appendFixed2(sb2, binary.chi2);
            sb2.append(" r=");
            appendFixed2(sb2, binary.currentSeparation);
            sb2.append("M Om=");
            appendFixed3(sb2, binary.orbitalOmegaGeom);
            sb2.append(" Eb=");
            appendFixed3(sb2, binary.orbitalEnergy);
            sb2.append(" Er=");
            appendFixed4(sb2, binary.radiatedEnergy);
            sb2.append(" L=");
            appendFixed3(sb2, binary.orbitalAngularMomentum);
        } else if (state.wormholeMode) {
            sb2.append("rho=");
            appendFixed2(sb2, state.wormholeRho);
            sb2.append("  a=");
            appendFixed2(sb2, state.wormholeA);
            sb2.append("  Mw=");
            appendFixed2(sb2, state.wormholeM);
            sb2.append("  |  INTEGRATOR: RK4 + NULL REPROJ");
        } else {
            float a = state.effectiveSpin();
            sb2.append("SOLVER: ").append(state.integratorMode == SimulationState.INTEGRATOR_DOPRI54
                    ? "DOPRI5(4) tol=2.5e-4" : "VGPR-RK4");
            sb2.append("  a*=").append(Math.round(a * 1000f) / 1000f);
            sb2.append("  r+=").append(Math.round(KerrMetric.outerHorizonRadius(a) * 100.0) / 100.0);
            sb2.append("M  r_ph+=").append(Math.round(KerrMetric.progradePhotonSphereRadius(a) * 100.0) / 100.0);
            sb2.append("M  ISCO=").append(Math.round(KerrMetric.progradeIscoRadius(a) * 100.0) / 100.0).append('M');
        }

        sb3.setLength(0);
        if (state.binaryMode && binary != null) {
            sb3.append("Mf=");
            appendFixed3(sb3, binary.remnantMass);
            sb3.append(" af=");
            appendFixed3(sb3, binary.remnantSpin);
            sb3.append(" fGW=");
            appendFixed1(sb3, binary.gwFreqHz);
            sb3.append("Hz HM=");
            appendFixed1(sb3, (float) (binary.higherModePowerFrac * 100.0));
            sb3.append("% | EOB_BAL=");
            appendFixed4(sb3, (float) binary.eobEnergyBalanceErr);
            sb3.append(" | GPU P95=").append(Math.round(telemetry.gpuP95Ms * 100f) / 100f).append("ms");
        } else {
            sb3.append("GPU P95=").append(Math.round(telemetry.gpuP95Ms * 100f) / 100f)
                    .append("ms  P99=").append(Math.round(telemetry.gpuP99Ms * 100f) / 100f)
                    .append("ms  |  RENDER ALLOC=").append(telemetry.renderAllocBytesLastFrame)
                    .append(" B/f  |  TDE: ").append(state.tdeHydroEnabled ? "64^3 HYDRO" : "PURE GRAV");
            if (telemetry.tdeActiveParticles > 0) {
                sb3.append(" <E>=").append(Math.round(telemetry.tdeMeanEnergy * 1000f) / 1000f)
                        .append(" <Ly>=").append(Math.round(telemetry.tdeMeanAngMomY * 100f) / 100f);
            }
        }

        ui.drawText(sb1, x + 12f, y + 10f, 0.92f, 0.30f, 0.96f, 0.88f, 0.98f);
        ui.drawText(sb2, x + 12f, y + 27f, 0.92f, 0.92f, 0.95f, 0.98f, 0.92f);
        ui.drawText(sb3, x + 12f, y + 44f, 0.88f, 0.74f, 0.84f, 0.94f, 0.88f);
    }

    private void updateBardeenCache(float a, float incRad) {
        if (Math.abs(a - cachedBardeenSpin) < 1e-4f && Math.abs(incRad - cachedBardeenInc) < 0.015f) {
            return;
        }
        cachedBardeenSpin = a;
        cachedBardeenInc = incRad;
        bardeenCount = 0;

        if (a < 0.005f || Math.sin(incRad) < 0.03) {
            double rad = (a < 0.005f) ? KerrMetric.SCHWARZSCHILD_SHADOW_RADIUS
                    : computePolarShadowRadius(a);
            for (int i = 0; i < BARDEEN_SAMPLES; i++) {
                double ang = (Math.PI * i) / (BARDEEN_SAMPLES - 1);
                bardeenAlpha[i] = (float) (rad * Math.cos(ang));
                bardeenBeta[i] = (float) (rad * Math.sin(ang));
            }
            bardeenCount = BARDEEN_SAMPLES;
            return;
        }

        double rMin = KerrMetric.progradePhotonSphereRadius(a) + 1e-5;
        double rMax = KerrMetric.retrogradePhotonSphereRadius(a) - 1e-5;
        double rPol = KerrMetric.polarPhotonOrbitRadius(a);

        // Bisect exact visible-curve endpoints [r1, r2] where beta^2(rp, incRad) == 0 so the D-curve closes seamlessly
        double lo = rMin, hi = rPol;
        for (int iter = 0; iter < 24; iter++) {
            double mid = 0.5 * (lo + hi);
            if (KerrMetric.bardeenShadowPoint(a, incRad, mid, scratchPt2)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        double r1 = hi;

        lo = rPol;
        hi = rMax;
        for (int iter = 0; iter < 24; iter++) {
            double mid = 0.5 * (lo + hi);
            if (KerrMetric.bardeenShadowPoint(a, incRad, mid, scratchPt2)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double r2 = lo;

        // Cosine-clustered sampling gives dense resolution along the steep flattened prograde D-limb
        for (int i = 0; i < BARDEEN_SAMPLES; i++) {
            double u = 0.5 * (1.0 - Math.cos(Math.PI * i / (double) (BARDEEN_SAMPLES - 1)));
            double rp = r1 + (r2 - r1) * u;
            if (KerrMetric.bardeenShadowPoint(a, incRad, rp, scratchPt2)) {
                bardeenAlpha[bardeenCount] = (float) scratchPt2[0];
                bardeenBeta[bardeenCount] = (i == 0 || i == BARDEEN_SAMPLES - 1) ? 0.0f : (float) scratchPt2[1];
                bardeenCount++;
            }
        }
    }

    private double computePolarShadowRadius(float a) {
        double rPol = KerrMetric.polarPhotonOrbitRadius(a);
        if (KerrMetric.bardeenShadowPoint(a, 0.0, rPol, scratchPt2)) {
            return scratchPt2[1];
        }
        return KerrMetric.SCHWARZSCHILD_SHADOW_RADIUS;
    }

    private void updateGeodesicCache(float a) {
        if (Math.abs(a - cachedGeoSpin) < 1e-4f) {
            return;
        }
        cachedGeoSpin = a;
        double xStart = 20.0;
        double rCap = KerrMetric.outerHorizonRadius(a) * 1.03;

        // Compute exact equatorial critical impact parameters bPro (>0, prograde) and bRetro (<0, retrograde)
        double bPro = 5.196;
        double bRetro = 5.196;
        if (a > 0.01f) {
            if (KerrMetric.bardeenShadowPoint(a, Math.PI * 0.5, KerrMetric.progradePhotonSphereRadius(a) + 1e-4, scratchPt2)) {
                bPro = Math.max(1.8, Math.abs(scratchPt2[0]));
            }
            if (KerrMetric.bardeenShadowPoint(a, Math.PI * 0.5, KerrMetric.retrogradePhotonSphereRadius(a) - 1e-4, scratchPt2)) {
                bRetro = Math.max(5.2, Math.abs(scratchPt2[0]));
            }
        }

        // Parallel equatorial photon bundle incoming along -x from x = +20M (z > 0 is prograde L_y > 0 for J=(0,-a,0)):
        // 0: prograde captured, 1: near-critical prograde photon-sphere orbit, 2: prograde deflected, 3: retrograde deflected
        double[] zImpacts = {0.62 * bPro, 1.045 * bPro, 1.85 * bPro, -1.16 * bRetro};

        for (int g = 0; g < GEODESIC_COUNT; g++) {
            double z0 = zImpacts[g];
            double pt = KerrMetric.initStaticShellRay(xStart, 0.0, z0, -1.0, 0.0, 0.0, a, scratchState6);
            OdeSystem ode = KerrMetric.nullGeodesicOde(a, pt);

            geoX[g][0] = (float) scratchState6[0];
            geoZ[g][0] = (float) scratchState6[2];
            final int[] count = {1};
            final boolean[] cap = {false};
            final int idx = g;

            dopri.integrate(ode, 0.0, scratchState6, 68.0, geoCfg, diag, (lam, y, h) -> {
                double r = KerrMetric.kerrRadius(y[0], y[1], y[2], a);
                if (count[0] < MAX_GEO_PTS) {
                    geoX[idx][count[0]] = (float) y[0];
                    geoZ[idx][count[0]] = (float) y[2];
                    count[0]++;
                }
                if (r <= rCap) {
                    cap[0] = true;
                    return false;
                }
                return r < 24.0 && count[0] < MAX_GEO_PTS;
            });
            geoLen[g] = count[0];
            geoCaptured[g] = cap[0];
        }
    }

    private static boolean projectOrigin(CameraController cam, int w, int h, float aspect,
                                         float tanHalfFov, float[] out) {
        return projectPoint(0f, 0f, 0f, cam, w, h, aspect, tanHalfFov, out);
    }

    private static boolean projectPoint(float wx, float wy, float wz,
                                        CameraController cam, int w, int h, float aspect,
                                        float tanHalfFov, float[] out) {
        float rx = wx - cam.posX;
        float ry = wy - cam.posY;
        float rz = wz - cam.posZ;
        float z = rx * cam.forwardX + ry * cam.forwardY + rz * cam.forwardZ;
        if (z <= 0.15f) {
            return false;
        }
        float x = rx * cam.rightX + ry * cam.rightY + rz * cam.rightZ;
        float y = rx * cam.upX + ry * cam.upY + rz * cam.upZ;
        float ndcX = x / (z * aspect * tanHalfFov);
        float ndcY = y / (z * tanHalfFov);
        if (ndcX < -1.3f || ndcX > 1.3f || ndcY < -1.3f || ndcY > 1.3f) {
            return false;
        }
        out[0] = (ndcX * 0.5f + 0.5f) * w;
        out[1] = (0.5f - ndcY * 0.5f) * h;
        return true;
    }

    private static void appendFixed1(StringBuilder sb, float v) {
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 10f);
        sb.append(s / 10).append('.').append(s % 10);
    }

    private static void appendFixed2(StringBuilder sb, float v) {
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 100f);
        sb.append(s / 100).append('.');
        int f = s % 100;
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    private static void appendFixed3(StringBuilder sb, float v) {
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 1000f);
        sb.append(s / 1000).append('.');
        int f = s % 1000;
        if (f < 100) sb.append('0');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    private static void appendFixed4(StringBuilder sb, float v) {
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 10000f);
        sb.append(s / 10000).append('.');
        int f = s % 10000;
        if (f < 1000) sb.append('0');
        if (f < 100) sb.append('0');
        if (f < 10) sb.append('0');
        sb.append(f);
    }
}
