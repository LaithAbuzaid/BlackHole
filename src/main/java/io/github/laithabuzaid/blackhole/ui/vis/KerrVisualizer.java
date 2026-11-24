package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;

/**
 * Mode-specific scientific visualizer for the Kerr Black Hole & Doran Horizon Plunge (Phases 3 & 6).
 *
 * <p>Uses exact Kerr-Schild oblate spheroidal coordinate transforms {@link KerrMetric#oblateToCartesian}
 * for all metric surfaces (never spherical approximations), renders ZAMO frame-dragging vector
 * fields, 7 DOPRI5(4) null geodesics, EHT EVPA polarization glyphs with Faraday disclosure,
 * disk physical colormaps, and Doran free-fall plunge proper-vs-coordinate time diagnostics.</p>
 */
public final class KerrVisualizer {

    private static final int BARDEEN_SAMPLES = 96;
    private static final int GEODESIC_COUNT = 7;
    private static final int MAX_GEO_PTS = 192;

    private final Projection3D proj = new Projection3D();
    private final float[] ptA = new float[2];
    private final float[] xyz = new float[3];
    private final StringBuilder sb = new StringBuilder(140);

    // Cached Bardeen critical curve points in observer sky plane (alpha, +beta)
    private final float[] bardeenAlpha = new float[BARDEEN_SAMPLES];
    private final float[] bardeenBeta = new float[BARDEEN_SAMPLES];
    private int bardeenCount = 0;
    private float cachedBardeenSpin = -1.0f;
    private float cachedBardeenInc = -10.0f;

    // Cached DOPRI5(4) 3D Kerr null geodesic trajectories
    private final float[][] geoX = new float[GEODESIC_COUNT][MAX_GEO_PTS];
    private final float[][] geoY = new float[GEODESIC_COUNT][MAX_GEO_PTS];
    private final float[][] geoZ = new float[GEODESIC_COUNT][MAX_GEO_PTS];
    private final int[] geoLen = new int[GEODESIC_COUNT];
    private final boolean[] geoCaptured = new boolean[GEODESIC_COUNT];
    private float cachedGeoSpin = -1.0f;

    private final DormandPrince54Integrator dopri = new DormandPrince54Integrator();
    private final IntegrationDiagnostics diag = new IntegrationDiagnostics();
    private final IntegratorConfig geoCfg = new IntegratorConfig(1e-7, 1e-9, 0.10, 1e-4, 0.55, 2400, 0.9);
    private final double[] scratchPt2 = new double[2];
    private final double[] scratchState6 = new double[6];

    public void renderAnalytical(UiRenderer ui, int width, int height,
                                 SimulationState state, CameraController camera, boolean hasTde) {
        float a = state.effectiveSpin();
        float rCam = (float) Math.sqrt(camera.posX * camera.posX
                + camera.posY * camera.posY + camera.posZ * camera.posZ);
        if (rCam < 0.2f) {
            return;
        }
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));
        float aspect = (float) width / Math.max(1, height);

        if (state.visKerrGeometry && rCam >= 2.8f) {
            renderKerrGeometry3d(ui, width, height, aspect, tanHalfFov, a, rCam, camera);
        }
        if (state.visKerrFrameDragging && rCam >= 2.8f) {
            renderFrameDraggingField(ui, width, height, aspect, tanHalfFov, a, camera);
        }
        if (state.visKerrGeodesics && rCam >= 2.8f) {
            renderNullGeodesics(ui, width, height, aspect, tanHalfFov, a, camera);
        }

        // Compact Kerr Legend (left column, below HUD or top-left)
        if (!hasTde && !state.plungeActive) {
            float legX = 16f;
            float legY = (state.hudMode == SimulationState.HUD_FULL) ? 416f
                    : (state.hudMode == SimulationState.HUD_COMPACT ? 54f : 18f);
            if (legY + 188f < height - 100f) {
                renderKerrLegendPanel(ui, legX, legY, a, state);
            }
        }
    }

    /** Renders disk colormap legend, EHT EVPA vector glyphs, and Plunge diagnostics whenever enabled. */
    public void renderAlwaysOnLayers(UiRenderer ui, int width, int height,
                                     SimulationState state, CameraController camera) {
        float a = state.effectiveSpin();
        float aspect = (float) width / Math.max(1, height);
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));

        if (state.kerrDiskVisMode > 0 && state.scientificDiagMode != SimulationState.DIAG_HAMILTONIAN_ERR
                && state.scientificDiagMode != SimulationState.DIAG_STEP_DENSITY) {
            renderDiskVisColorbar(ui, width, state);
        }

        if (state.ehtPolarization && state.visEhtGlyphs && state.showDisk
                && state.scientificDiagMode != SimulationState.DIAG_HAMILTONIAN_ERR
                && state.scientificDiagMode != SimulationState.DIAG_STEP_DENSITY) {
            renderEhtPolarizationGlyphs(ui, width, height, aspect, tanHalfFov, a, state, camera);
        }

        if (state.plungeActive && state.visPlungeDiag
                && (state.hudMode != SimulationState.HUD_CLEAN || state.scientificDiagMode == SimulationState.DIAG_ANALYTICAL_OVERLAY)) {
            renderPlungeDiagnosticsPanel(ui, width, height, state, camera);
        }
    }

    private void renderKerrGeometry3d(UiRenderer ui, int width, int height, float aspect, float tanHalfFov,
                                      float a, float rCam, CameraController camera) {
        float cosThetaObs = Math.max(-0.999f, Math.min(0.999f, camera.posY / rCam));
        float incRad = (float) Math.acos(Math.abs(cosThetaObs));

        // 1. Exact Bardeen (1973) analytical critical shadow curve
        updateBardeenCache(a, incRad);
        if (proj.projectPoint(0f, 0f, 0f, camera, width, height, aspect, tanHalfFov, ptA)) {
            float finiteScale = (float) Math.sqrt(Math.max(0.15, 1.0 - 2.0 / Math.max(2.5, rCam)));
            float halfH = height * 0.5f;
            float invTanFov = 1.0f / Math.max(1e-4f, tanHalfFov);
            float cx = ptA[0];
            float cy = ptA[1];
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = cy;
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
                ui.drawText("BARDEEN SHADOW [E]", 0.5f * (minX + maxX) - 70f, maxY + 10f,
                        0.82f, 0.24f, 0.96f, 0.88f, 0.96f);
            }
        }

        // 2. Exact Kerr-Schild Oblate Surfaces (Cartesian rho_KS = sqrt(r^2 + a^2) sin(theta))
        double rPlus = KerrMetric.outerHorizonRadius(a);
        double rMinus = KerrMetric.innerHorizonRadius(a);
        double rPhPro = KerrMetric.progradePhotonSphereRadius(a);
        double rPhRet = KerrMetric.retrogradePhotonSphereRadius(a);
        double rIscoPro = KerrMetric.progradeIscoRadius(a);
        double rIscoRet = KerrMetric.retrogradeIscoRadius(a);

        // Outer Horizon r_+ (equator + 4 oblate meridians)
        proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f, 1f, rPlus, a, 56, 1.7f, false, 1.00f, 0.34f, 0.32f, 0.88f);
        for (int k = 0; k < 4; k++) {
            proj.drawKerrConstRMeridian(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, rPlus, a, k * Math.PI * 0.25,
                    40, 1.0f, false, 1.00f, 0.34f, 0.32f, 0.42f);
        }

        // Inner Cauchy Horizon r_- (Kerr only)
        if (a >= 0.05f) {
            proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f, 1f, rMinus, a, 40, 1.2f, true, 0.92f, 0.32f, 0.82f, 0.65f);
        }

        // Ergosphere r_ergo(theta) = 1 + sqrt(1 - a^2 cos^2(theta)) (vanishes cleanly at a = 0!)
        if (a >= 0.02f) {
            proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f, 1f, 2.0, a, 56, 1.4f, false, 0.72f, 0.52f, 1.00f, 0.82f);
            for (int k = 0; k < 4; k++) {
                proj.drawKerrErgosphereMeridian(ui, width, height, aspect, tanHalfFov, camera,
                        a, k * Math.PI * 0.25, 44, 1.15f, 0.72f, 0.52f, 1.00f, 0.52f);
            }
        }

        // Photon Region: prograde r_ph+ and retrograde r_ph-
        proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f, 1f, rPhPro, a, 56, 1.5f, false, 1.00f, 0.82f, 0.26f, 0.84f);
        if (a >= 0.02f) {
            proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f, 1f, rPhRet, a, 56, 1.2f, true, 1.00f, 0.56f, 0.24f, 0.74f);
        }

        // Prograde & Retrograde ISCO rings (collapse identically to 6.0M when a = 0!)
        proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f, 1f, rIscoPro, a, 64, 1.5f, false, 0.34f, 0.90f, 1.00f, 0.82f);
        if (a >= 0.02f) {
            proj.drawKerrEquatorialRing(ui, width, height, aspect, tanHalfFov, camera, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f, 1f, rIscoRet, a, 64, 1.3f, true, 0.96f, 0.68f, 0.28f, 0.68f);
        }

        // Equatorial plane coordinate crosshairs (up to max(8.5, rIscoRet + 1))
        float eqAxis = (float) Math.max(8.5, rIscoRet + 1.0);
        proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                -eqAxis, 0f, 0f, eqAxis, 0f, 0f, 1.0f, true, 0.48f, 0.72f, 0.92f, 0.30f);
        proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                0f, 0f, -eqAxis, 0f, 0f, eqAxis, 1.0f, true, 0.48f, 0.72f, 0.92f, 0.30f);

        // Spin Axis J = (0, -a, 0) & Polar Rotation Sense
        proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                0f, 5.2f, 0f, 0f, -5.2f, 0f, 1.3f, true, 0.72f, 0.86f, 1.00f, 0.62f);
        if (a >= 0.02f) {
            proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                    0f, 0f, 0f, 0f, -4.8f, 0f, 2.2f, 8f, 0.42f, 0.94f, 1.00f, 0.92f);
            if (proj.projectPoint(0f, -5.0f, 0f, camera, width, height, aspect, tanHalfFov, ptA)) {
                ui.drawText("SPIN J=(0,-a,0)", ptA[0] - 46f, ptA[1] + 6f, 0.74f, 0.42f, 0.94f, 1.00f, 0.92f);
            }
            if (proj.projectPoint(0f, 5.4f, 0f, camera, width, height, aspect, tanHalfFov, ptA)) {
                ui.drawText("+Y POLE", ptA[0] - 24f, ptA[1] - 12f, 0.72f, 0.72f, 0.86f, 1.00f, 0.86f);
            }
        }
    }

    private void renderFrameDraggingField(UiRenderer ui, int width, int height, float aspect, float tanHalfFov,
                                          float a, CameraController camera) {
        if (a < 0.02f) {
            if (proj.projectPoint(0f, 3.6f, 0f, camera, width, height, aspect, tanHalfFov, ptA)) {
                ui.drawText("a*=0 SCHWARZSCHILD: omega_ZAMO = 0 (NO FRAME DRAGGING)",
                        ptA[0] - 165f, ptA[1] - 12f, 0.76f, 0.78f, 0.88f, 0.98f, 0.88f);
            }
            return;
        }

        double rPlus = KerrMetric.outerHorizonRadius(a);
        double[] radii = {Math.max(rPlus + 0.35, 1.75), 2.85, 4.80};
        double[] thetas = {Math.PI * 0.5, Math.PI * 0.5, Math.PI * 0.5};
        double omegaRef = Math.max(1e-4, KerrMetric.zamoOmega(radii[0], 0.0, a));

        for (int ri = 0; ri < radii.length; ri++) {
            double r = radii[ri];
            double th = thetas[ri];
            double cosT = Math.cos(th);
            double om = KerrMetric.zamoOmega(r, cosT, a);
            float normOm = (float) Math.min(1.0, Math.sqrt(om / omegaRef));
            float arrowLen = 0.45f + 1.35f * normOm;

            int nPhi = (ri == 0) ? 12 : 10;
            for (int p = 0; p < nPhi; p++) {
                double phi = (2.0 * Math.PI * p) / nPhi + ri * 0.22;
                KerrMetric.oblateToCartesian(r, th, phi, a, xyz);
                // Frame-dragging +phi tangent vector = (-sin(phi), 0, +cos(phi))
                float tx = (float) (-Math.sin(phi)) * arrowLen;
                float tz = (float) Math.cos(phi) * arrowLen;
                float cr = 0.24f + 0.74f * normOm;
                float cg = 0.72f + 0.26f * (1.0f - normOm);
                float cb = 1.00f - 0.35f * normOm;
                proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                        xyz[0], xyz[1], xyz[2], xyz[0] + tx, xyz[1], xyz[2] + tz,
                        1.5f, 5.5f, cr, cg, cb, 0.78f);
            }
        }

        // Prograde vs Retrograde orbital velocity comparison at respective ISCOs
        double rIscoP = KerrMetric.progradeIscoRadius(a);
        double rIscoR = KerrMetric.retrogradeIscoRadius(a);
        double vPro = Math.sqrt(1.0 / Math.max(0.5, rIscoP)) * 1.4;
        double vRet = Math.sqrt(1.0 / Math.max(0.5, rIscoR)) * 1.4;
        KerrMetric.oblateToCartesian(rIscoP, Math.PI * 0.5, 0.0, a, xyz);
        proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                xyz[0], 0f, xyz[2], xyz[0], 0f, xyz[2] + (float) (vPro * 2.2),
                2.0f, 6.5f, 0.34f, 0.96f, 1.00f, 0.92f);
        if (proj.projectPoint(xyz[0], 0f, xyz[2] + (float) (vPro * 2.2), camera, width, height, aspect, tanHalfFov, ptA)) {
            ui.drawText("PROGRADE ISCO", ptA[0] + 5f, ptA[1], 0.70f, 0.34f, 0.96f, 1.00f, 0.92f);
        }

        KerrMetric.oblateToCartesian(rIscoR, Math.PI * 0.5, Math.PI, a, xyz);
        proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                xyz[0], 0f, xyz[2], xyz[0], 0f, xyz[2] + (float) (vRet * 2.2),
                2.0f, 6.5f, 0.98f, 0.68f, 0.28f, 0.92f);
        if (proj.projectPoint(xyz[0], 0f, xyz[2] + (float) (vRet * 2.2), camera, width, height, aspect, tanHalfFov, ptA)) {
            ui.drawText("RETROGRADE ISCO", ptA[0] + 5f, ptA[1], 0.70f, 0.98f, 0.68f, 0.28f, 0.92f);
        }
    }

    private void renderNullGeodesics(UiRenderer ui, int width, int height, float aspect, float tanHalfFov,
                                     float a, CameraController camera) {
        updateGeodesicCache(a);
        for (int g = 0; g < GEODESIC_COUNT; g++) {
            int n = geoLen[g];
            boolean cap = geoCaptured[g];
            float cr, cg, cb;
            if (cap) {
                cr = 1.00f; cg = 0.34f; cb = 0.30f;
            } else if (g == 1) {
                cr = 1.00f; cg = 0.86f; cb = 0.24f; // Near-critical photon orbit
            } else if (g == 6) {
                cr = 0.42f; cg = 1.00f; cb = 0.72f; // Inclined 3D ray
            } else if (g == 5) {
                cr = 1.00f; cg = 0.56f; cb = 0.24f; // Retrograde deflected
            } else {
                cr = 0.36f; cg = 0.86f; cb = 1.00f; // Prograde deflected
            }
            for (int i = 0; i < n - 1; i++) {
                proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                        geoX[g][i], geoY[g][i], geoZ[g][i],
                        geoX[g][i + 1], geoY[g][i + 1], geoZ[g][i + 1],
                        (g == 1) ? 1.9f : 1.55f, false, cr, cg, cb, 0.86f);
            }
            if (n >= 4) {
                int k = n - 2;
                proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                        geoX[g][k], geoY[g][k], geoZ[g][k],
                        geoX[g][k + 1], geoY[g][k + 1], geoZ[g][k + 1],
                        1.6f, 5.5f, cr, cg, cb, 0.92f);
            }
        }
    }

    private void renderKerrLegendPanel(UiRenderer ui, float x, float y, float a, SimulationState state) {
        float w = 292f;
        float h = 180f;
        ui.drawGlassPanel(x, y, w, h, 8f, 0.03f, 0.05f, 0.09f, 0.88f, 0.24f, 0.86f, 0.82f, 0.48f);
        PlotWidgets.drawPanelHeader(ui, x, y, w, "KERR-SCHILD OVERLAY", "[E] EXACT KS", 0.30f, 0.96f, 0.88f);

        float ty = y + 25f;
        drawLegendSwatch(ui, x + 10f, ty, 0.24f, 0.96f, 0.88f, "Bardeen Critical Shadow (analytic)"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 1.00f, 0.34f, 0.32f, "Horizon r+ (KS sqrt(r+^2+a^2))"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 0.72f, 0.52f, 1.00f,
                a >= 0.02f ? "Oblate Ergosphere r_ergo(theta)" : "Ergosphere (collapsed at a*=0)"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 1.00f, 0.82f, 0.26f, "Photon Orbits r_ph+ / r_ph- (dash)"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 0.34f, 0.90f, 1.00f, "ISCO Prograde (cyan) / Retro (amb)"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 0.50f, 0.92f, 1.00f, "ZAMO Frame-Dragging Field omega"); ty += 15f;
        drawLegendSwatch(ui, x + 10f, ty, 0.42f, 1.00f, 0.72f, "7 DOPRI5(4) Null Geodesics"); ty += 17f;

        sb.setLength(0);
        sb.append("r+=").append(Math.round(KerrMetric.outerHorizonRadius(a) * 100.0) / 100.0)
          .append("M  ISCO+=").append(Math.round(KerrMetric.progradeIscoRadius(a) * 100.0) / 100.0)
          .append("M  ISCO-=").append(Math.round(KerrMetric.retrogradeIscoRadius(a) * 100.0) / 100.0).append('M');
        ui.drawText(sb, x + 10f, ty, 0.72f, 0.88f, 0.94f, 1.00f, 0.92f);
        ty += 13f;
        ui.drawText("[A/V] DISK: NOVIKOV-THORNE (NON-GRMHD)", x + 10f, ty, 0.68f, 0.98f, 0.78f, 0.40f, 0.90f);
    }

    private void renderDiskVisColorbar(UiRenderer ui, int width, SimulationState state) {
        float bw = 276f;
        float bh = 68f;
        float bx = (width - bw) * 0.5f;
        float by = state.plungeActive ? 120f : 18f;
        String title = switch (state.kerrDiskVisMode) {
            case SimulationState.DISK_VIS_TEMP -> "DISK TEMP T(r) [K] (NOVIKOV-THORNE)";
            case SimulationState.DISK_VIS_DOPPLER -> "DOPPLER SHIFT g = nu_obs / nu_emit";
            default -> "DISK ORBITAL VELOCITY |v|/c";
        };
        String minL = switch (state.kerrDiskVisMode) {
            case SimulationState.DISK_VIS_TEMP -> "1500 K";
            case SimulationState.DISK_VIS_DOPPLER -> "0.35 RED";
            default -> "0.00c";
        };
        String midL = switch (state.kerrDiskVisMode) {
            case SimulationState.DISK_VIS_TEMP -> "10750 K";
            case SimulationState.DISK_VIS_DOPPLER -> "1.00";
            default -> "0.35c";
        };
        String maxL = switch (state.kerrDiskVisMode) {
            case SimulationState.DISK_VIS_TEMP -> "20000 K";
            case SimulationState.DISK_VIS_DOPPLER -> "2.00 BLU";
            default -> "0.70c (ISCO)";
        };
        int cmap = (state.kerrDiskVisMode == SimulationState.DISK_VIS_DOPPLER)
                ? PlotWidgets.CMAP_DOPPLER : PlotWidgets.CMAP_TURBO;
        PlotWidgets.drawColorbar(ui, bx, by, bw, bh, title, minL, midL, maxL, cmap);
        ui.drawText("[V] PRESCRIPTION (NON-GRMHD)", bx + 12f, by + 54f, 0.68f, 0.98f, 0.80f, 0.42f, 0.88f);
    }

    private void renderEhtPolarizationGlyphs(UiRenderer ui, int width, int height, float aspect, float tanHalfFov,
                                             float a, SimulationState state, CameraController camera) {
        double iscoR = KerrMetric.progradeIscoRadius(a);
        double[] ringRadii = {iscoR + 1.15, iscoR + 3.80, iscoR + 7.20};
        double pitchDenom = (state.ehtMagneticModel == SimulationState.EHT_MODEL_SANE)
                ? (2.80 + 3.20 * a) : (0.95 + 1.15 * a);
        double pitchAngle = Math.atan(1.0 / pitchDenom);
        double cosP = Math.cos(pitchAngle);
        double sinP = Math.sin(pitchAngle);

        for (int ri = 0; ri < ringRadii.length; ri++) {
            double r = ringRadii[ri];
            double rhoKs = Math.sqrt(r * r + a * a);
            int nTicks = 12;
            for (int k = 0; k < nTicks; k++) {
                double phi = (2.0 * Math.PI * k) / nTicks + ri * 0.26;
                float px = (float) (rhoKs * Math.cos(phi));
                float pz = (float) (rhoKs * Math.sin(phi));

                // Radial & azimuthal unit vectors in equatorial plane
                float rx = (float) Math.cos(phi), rz = (float) Math.sin(phi);
                float phx = -rz, phz = rx;

                // Co-moving B-field direction (MAD = stronger poloidal/radial spiral; SANE = tight toroidal)
                float bx = (float) (cosP * phx + sinP * rx);
                float bz = (float) (cosP * phz + sinP * rz);

                // View ray from observer to disk element k_hat
                float kx = px - camera.posX, ky = -camera.posY, kz = pz - camera.posZ;
                float kLen = (float) Math.sqrt(kx * kx + ky * ky + kz * kz);
                if (kLen < 1e-4f) continue;
                kx /= kLen; ky /= kLen; kz /= kLen;

                // EVPA linear polarization direction: e_pol ~ k_hat x B
                float ex = ky * bz;
                float ey = kz * bx - kx * bz;
                float ez = -ky * bx;
                float eLen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (eLen > 1e-4f) {
                    ex /= eLen; ey /= eLen; ez /= eLen;
                    float tickHalf = 0.55f;
                    proj.drawWorldLine(ui, width, height, aspect, tanHalfFov, camera,
                            px - ex * tickHalf, -ey * tickHalf, pz - ez * tickHalf,
                            px + ex * tickHalf,  ey * tickHalf, pz + ez * tickHalf,
                            1.85f, false,
                            state.ehtRadioPalette ? 1.00f : 0.36f,
                            state.ehtRadioPalette ? 0.86f : 0.90f,
                            state.ehtRadioPalette ? 0.42f : 1.00f, 0.92f);
                }

                if (ri == 1 && (k & 1) == 0) {
                    proj.drawWorldArrow(ui, width, height, aspect, tanHalfFov, camera,
                            px, 0f, pz, px + bx * 1.15f, 0f, pz + bz * 1.15f,
                            1.4f, 5f, 0.38f, 0.96f, 0.72f, 0.80f);
                }
            }
        }

        // Compact EHT Polarization Model & Faraday Omission Disclosure Card
        float cw = 348f;
        float ch = 64f;
        float cx = width - cw - ((state.hudMode == SimulationState.HUD_FULL) ? 384f : 18f);
        float cy = (state.kerrDiskVisMode > 0) ? 94f : 18f;
        if (cx > 260f) {
            ui.drawGlassPanel(cx, cy, cw, ch, 7f, 0.03f, 0.05f, 0.09f, 0.88f, 0.96f, 0.78f, 0.36f, 0.52f);
            String modelName = (state.ehtMagneticModel == SimulationState.EHT_MODEL_SANE)
                    ? "EHT B-FIELD: SANE (TOROIDAL)" : "EHT B-FIELD: MAD (POLOIDAL+TOR)";
            PlotWidgets.drawPanelHeader(ui, cx, cy, cw, modelName, "[V] OPT-THIN", 0.98f, 0.84f, 0.44f);
            ui.drawText("TICKS: EVPA (k x B)  |  ARROWS: B-FIELD", cx + 10f, cy + 25f, 0.72f, 0.88f, 0.94f, 1.00f, 0.92f);
            ui.drawText("DISCLOSURE: FARADAY ROTATION NOT MODELED", cx + 10f, cy + 41f, 0.70f, 1.00f, 0.62f, 0.38f, 0.95f);
        }
    }

    private void renderPlungeDiagnosticsPanel(UiRenderer ui, int width, int height,
                                              SimulationState state, CameraController camera) {
        if (camera.plungeHistCount() < 2) {
            camera.populateDeterministicPlungeHistory(state, Math.max(8.5, camera.currentKerrRadius() + 6.0));
        }
        int count = camera.plungeHistCount();
        int start = camera.plungeHistStartIdx();
        float[] taus = camera.plungeHistTau();
        float[] tCoords = camera.plungeHistCoordT();
        float[] rs = camera.plungeHistR();

        float pw = Math.min(372f, width - 32f);
        float ph = 208f;
        float px = 16f;
        float py = (state.hudMode == SimulationState.HUD_FULL) ? 416f : 18f;
        if (py + ph > height - 92f) {
            py = Math.max(18f, height - ph - 92f);
        }

        ui.drawGlassPanel(px, py, pw, ph, 9f, 0.04f, 0.04f, 0.09f, 0.88f, 0.96f, 0.48f, 0.94f, 0.56f);
        PlotWidgets.drawPanelHeader(ui, px, py, pw, "HORIZON PLUNGE DIAGNOSTICS", "[E] DORAN TETRAD", 0.98f, 0.56f, 0.96f);

        float innerW = pw - 20f;
        float plotW = (innerW - 8f) * 0.5f;
        float plotH = 68f;
        float pY = py + 26f;

        // Plot 1: Radial trajectory r(tau) with r_ph, r_+, r_- lines
        float rMax = 10f;
        for (int i = 0; i < count; i++) {
            rMax = Math.max(rMax, rs[(start + i) % CameraController.PLUNGE_HIST_LEN]);
        }
        ui.drawGlassPanel(px + 10f, pY, plotW, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.86f, 1f, 1f, 1f, 0.14f);
        float rPlus = (float) state.outerHorizonRadius();
        float rPh = (float) KerrMetric.polarPhotonOrbitRadius(state.effectiveSpin());
        float yPlus = pY + plotH - (rPlus / rMax) * (plotH - 6f) - 3f;
        float yPh = pY + plotH - (rPh / rMax) * (plotH - 6f) - 3f;
        ui.drawDashedLine(px + 13f, yPlus, px + 7f + plotW, yPlus, 1.0f, 3f, 2f, 1.00f, 0.34f, 0.32f, 0.72f);
        ui.drawDashedLine(px + 13f, yPh, px + 7f + plotW, yPh, 1.0f, 3f, 2f, 1.00f, 0.82f, 0.26f, 0.65f);
        PlotWidgets.drawTimeSeriesFloat(ui, px + 13f, pY + 3f, plotW - 6f, plotH - 6f,
                rs, start, count, CameraController.PLUNGE_HIST_LEN, 0f, rMax, 1.75f, false, 0.34f, 0.92f, 1.00f, 0.96f);
        ui.drawText("r(tau) vs r+, r_ph", px + 14f, pY + 4f, 0.68f, 0.82f, 0.92f, 1.00f, 0.92f);

        // Plot 2: Proper time tau (cyan) vs Distant coordinate time t_inf (amber, diverges at r+)
        float tx = px + 10f + plotW + 8f;
        ui.drawGlassPanel(tx, pY, plotW, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.86f, 1f, 1f, 1f, 0.14f);
        float maxTau = Math.max(1f, (float) state.plungeProperTime);
        float maxT = maxTau * 1.8f;
        for (int i = 0; i < count; i++) {
            float tc = tCoords[(start + i) % CameraController.PLUNGE_HIST_LEN];
            if (Float.isFinite(tc) && tc > maxT) {
                maxT = Math.min(maxTau * 6.0f, tc * 1.08f);
            }
        }
        PlotWidgets.drawTimeSeriesFloat(ui, tx + 3f, pY + 3f, plotW - 6f, plotH - 6f,
                taus, start, count, CameraController.PLUNGE_HIST_LEN, 0f, maxT, 1.6f, false, 0.34f, 0.92f, 1.00f, 0.94f);
        if (count >= 2) {
            float stepX = (plotW - 6f) / (count - 1);
            for (int i = 0; i < count - 1; i++) {
                int i0 = (start + i) % CameraController.PLUNGE_HIST_LEN;
                int i1 = (start + i + 1) % CameraController.PLUNGE_HIST_LEN;
                float c0 = tCoords[i0], c1 = tCoords[i1];
                if (Float.isFinite(c0) && Float.isFinite(c1)) {
                    float y0 = pY + plotH - 3f - Math.min(1f, c0 / maxT) * (plotH - 6f);
                    float y1 = pY + plotH - 3f - Math.min(1f, c1 / maxT) * (plotH - 6f);
                    ui.drawLine(tx + 3f + i * stepX, y0, tx + 3f + (i + 1) * stepX, y1, 1.6f, 1.00f, 0.72f, 0.28f, 0.94f);
                }
            }
        }
        ui.drawText("tau(CYN) vs t_inf(AMB)", tx + 4f, pY + 4f, 0.66f, 0.96f, 0.86f, 0.62f, 0.92f);

        // Tidal Stretch vs Compression Schematic + Live Metrics
        float gy = pY + plotH + 8f;
        float boxW = 84f;
        float boxH = 56f;
        ui.drawGlassPanel(px + 10f, gy, boxW, boxH, 5f, 0.02f, 0.03f, 0.06f, 0.86f, 1f, 1f, 1f, 0.14f);
        float tCx = px + 10f + boxW * 0.5f;
        float tCy = gy + boxH * 0.5f;
        float stretch = Math.min(1.8f, 0.4f + (float) Math.sqrt(Math.max(0f, state.plungeTidalGrad)) * 0.45f);
        float rxRad = 8f / Math.max(1f, stretch);
        float ryRad = Math.min(boxH * 0.40f, 8f * stretch);
        ui.drawArrow(tCx, tCy - 4f, tCx, tCy - ryRad, 1.5f, 4f, 1.00f, 0.44f, 0.54f, 0.95f);
        ui.drawArrow(tCx, tCy + 4f, tCx, tCy + ryRad, 1.5f, 4f, 1.00f, 0.44f, 0.54f, 0.95f);
        ui.drawArrow(tCx - 16f, tCy, tCx - rxRad, tCy, 1.3f, 4f, 0.36f, 0.90f, 1.00f, 0.92f);
        ui.drawArrow(tCx + 16f, tCy, tCx + rxRad, tCy, 1.3f, 4f, 0.36f, 0.90f, 1.00f, 0.92f);
        ui.drawText("TIDAL", px + 34f, gy + boxH - 11f, 0.64f, 0.94f, 0.78f, 0.88f, 0.88f);

        float mx = px + 102f;
        float my = gy + 2f;
        sb.setLength(0);
        sb.append("r="); PlotWidgets.appendFixed2(sb, (float) camera.currentKerrRadius());
        sb.append("M  v/c="); PlotWidgets.appendFixed2(sb, state.plungeVelocityC);
        sb.append("  ZONE: ").append(state.plungeZoneName());
        ui.drawText(sb, mx, my, 0.74f, 0.96f, 0.90f, 1.00f, 0.96f); my += 14f;

        sb.setLength(0);
        sb.append("STATIC 1+z=");
        if (state.plungeHorizonCrossed) {
            sb.append("INF (IN r+, DORAN REGULAR)");
        } else {
            PlotWidgets.appendFixed2(sb, camera.plungeRedshiftFactor());
        }
        ui.drawText(sb, mx, my, 0.72f, 0.42f, 0.92f, 1.00f, 0.94f); my += 14f;

        sb.setLength(0);
        sb.append("TIDAL 2GM/r^3=");
        PlotWidgets.appendFixed2(sb, state.plungeTidalGrad);
        sb.append("  tau=");
        PlotWidgets.appendFixed2(sb, (float) state.plungeProperTime);
        sb.append('M');
        ui.drawText(sb, mx, my, 0.72f, 1.00f, 0.78f, 0.42f, 0.94f); my += 14f;

        ui.drawText("FRAME: DORAN E=1, Lz=0 FREE-FALL", mx, my, 0.70f, 0.76f, 0.86f, 0.96f, 0.90f);

        ui.drawText("[E] EXACT KERR-SCHILD / DORAN GEOMETRY", px + 10f, py + ph - 15f, 0.68f, 0.76f, 0.86f, 0.98f, 0.88f);
    }

    private static void drawLegendSwatch(UiRenderer ui, float x, float y, float r, float g, float b, String label) {
        ui.drawRect(x, y + 3f, 12f, 3f, 1.5f, r, g, b, 0.95f);
        ui.drawText(label, x + 18f, y, 0.74f, 0.88f, 0.92f, 0.98f, 0.94f);
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

    private void updateBardeenCache(float a, float incRad) {
        if (Math.abs(a - cachedBardeenSpin) < 1e-4f && Math.abs(incRad - cachedBardeenInc) < 0.015f) {
            return;
        }
        cachedBardeenSpin = a;
        cachedBardeenInc = incRad;
        bardeenCount = 0;

        if (a < 0.005f || Math.sin(incRad) < 0.03) {
            double rad = (a < 0.005f) ? KerrMetric.SCHWARZSCHILD_SHADOW_RADIUS : computePolarShadowRadius(a);
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

        double lo = rMin, hi = rPol;
        for (int iter = 0; iter < 24; iter++) {
            double mid = 0.5 * (lo + hi);
            if (KerrMetric.bardeenShadowPoint(a, incRad, mid, scratchPt2)) hi = mid; else lo = mid;
        }
        double r1 = hi;

        lo = rPol; hi = rMax;
        for (int iter = 0; iter < 24; iter++) {
            double mid = 0.5 * (lo + hi);
            if (KerrMetric.bardeenShadowPoint(a, incRad, mid, scratchPt2)) lo = mid; else hi = mid;
        }
        double r2 = lo;

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

        double[] yStarts = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.5};
        double[] zImpacts = {
                0.60 * bPro,     // 0: Prograde captured
                1.008 * bPro,    // 1: Near-critical prograde photon orbit (multiple turns!)
                1.35 * bPro,     // 2: Prograde strong deflection
                2.05 * bPro,     // 3: Prograde weak deflection
                -0.78 * bRetro,  // 4: Retrograde captured
                -1.06 * bRetro,  // 5: Retrograde near-critical deflected
                1.45 * bPro      // 6: Inclined off-equatorial lensed ray
        };

        for (int g = 0; g < GEODESIC_COUNT; g++) {
            double pt = KerrMetric.initStaticShellRay(xStart, yStarts[g], zImpacts[g], -1.0, 0.0, 0.0, a, scratchState6);
            OdeSystem ode = KerrMetric.nullGeodesicOde(a, pt);

            geoX[g][0] = (float) scratchState6[0];
            geoY[g][0] = (float) scratchState6[1];
            geoZ[g][0] = (float) scratchState6[2];
            final int[] count = {1};
            final boolean[] cap = {false};
            final int idx = g;

            dopri.integrate(ode, 0.0, scratchState6, 74.0, geoCfg, diag, (lam, y, h) -> {
                double r = KerrMetric.kerrRadius(y[0], y[1], y[2], a);
                if (count[0] < MAX_GEO_PTS) {
                    geoX[idx][count[0]] = (float) y[0];
                    geoY[idx][count[0]] = (float) y[1];
                    geoZ[idx][count[0]] = (float) y[2];
                    count[0]++;
                }
                if (r <= rCap) {
                    cap[0] = true;
                    return false;
                }
                return r < 24.5 && count[0] < MAX_GEO_PTS;
            });
            geoLen[g] = count[0];
            geoCaptured[g] = cap[0];
        }
    }
}
