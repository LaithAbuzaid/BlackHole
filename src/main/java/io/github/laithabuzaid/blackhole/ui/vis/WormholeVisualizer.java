package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.DormandPrince54Integrator;
import io.github.laithabuzaid.blackhole.math.IntegrationDiagnostics;
import io.github.laithabuzaid.blackhole.math.IntegratorConfig;
import io.github.laithabuzaid.blackhole.math.MorrisThorneMetric;
import io.github.laithabuzaid.blackhole.math.OdeSystem;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;

/**
 * Mode-specific scientific visualizer for the Morris-Thorne / Dneg Wormhole (Phase 5).
 *
 * <p>Visualizes the angularly exact 3D throat portal rim ({@code sin(theta_crit) = rho / r(l_cam)}),
 * the exact Flamm/Dneg isometric embedding profile {@code z(r(l))} with throat cylinder
 * {@code |l| <= a} and flare-out curvature, and 6 DOPRI5(4) null geodesics showing
 * through-throat traversal vs external reflection and exact null constraint residuals.</p>
 */
public final class WormholeVisualizer {

    private static final int EMBED_SAMPLES = 85;
    private static final int RAY_COUNT = 6;
    private static final int MAX_RAY_PTS = 128;

    private static final double[] H_RATIO_PRESETS = {0.25, 0.65, 0.92, 1.08, 1.45, 2.10};

    private final StringBuilder sb = new StringBuilder(140);
    private final DormandPrince54Integrator dopri = new DormandPrince54Integrator();
    private final IntegrationDiagnostics diag = new IntegrationDiagnostics();
    private final IntegratorConfig rayCfg = new IntegratorConfig(1e-10, 1e-12, 0.06, 1e-7, 0.4, 10_000, 0.9);
    private final double[] rayState3 = new double[3];

    // Cached isometric embedding profile z(l) vs r(l)
    private final float[] embL = new float[EMBED_SAMPLES];
    private final float[] embR = new float[EMBED_SAMPLES];
    private final float[] embZ = new float[EMBED_SAMPLES];
    private float cachedRho = -1f;
    private float cachedA = -1f;
    private float cachedMw = -1f;
    private float maxEmbZ = 14f;
    private float maxEmbR = 14f;

    // Cached DOPRI5(4) wormhole null geodesics: l(lambda), r(lambda), z(lambda), phi(lambda)
    private final float[][] rayR = new float[RAY_COUNT][MAX_RAY_PTS];
    private final float[][] rayZ = new float[RAY_COUNT][MAX_RAY_PTS];
    private final int[] rayLen = new int[RAY_COUNT];
    private final boolean[] rayTraversed = new boolean[RAY_COUNT];
    private final float[] rayDeflectDeg = new float[RAY_COUNT];
    private final double[] rayMaxNullErr = new double[RAY_COUNT];

    public void renderAnalytical(UiRenderer ui, int width, int height,
                                 SimulationState state, CameraController camera) {
        if (!state.visWormholeEmbedding) {
            return;
        }
        float rho = state.wormholeRho;
        float a = state.wormholeA;
        float mw = state.wormholeM;
        updateCache(rho, a, mw);

        renderThroatPortalRim(ui, width, height, state, camera, rho, a, mw);
        renderEmbeddingAndRaysPanel(ui, width, height, state, camera, rho, a, mw);
    }

    private void renderThroatPortalRim(UiRenderer ui, int width, int height,
                                       SimulationState state, CameraController camera,
                                       float rho, float a, float mw) {
        double lCam = camera.currentWormholeL();
        double rCam = MorrisThorneMetric.radius(lCam, rho, a, mw);
        double sinCrit = MorrisThorneMetric.criticalThroatSinAngle(lCam, rho, a, mw);

        // Direction from observer toward the throat l = 0:
        // Universe A (l >= 0) looks inward along -e_r; Universe B (l < 0) increases l toward 0 along +e_r.
        float dirSign = (lCam >= 0.0) ? -1.0f : 1.0f;
        float tox = dirSign * camera.radialX;
        float toy = dirSign * camera.radialY;
        float toz = dirSign * camera.radialZ;

        float z = tox * camera.forwardX + toy * camera.forwardY + toz * camera.forwardZ;
        if (z > 0.15f && sinCrit < 0.992) {
            float aspect = (float) width / Math.max(1, height);
            float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));
            float x = tox * camera.rightX + toy * camera.rightY + toz * camera.rightZ;
            float y = tox * camera.upX + toy * camera.upY + toz * camera.upZ;
            float ndcX = x / (z * aspect * tanHalfFov);
            float ndcY = y / (z * tanHalfFov);
            float cx = (ndcX * 0.5f + 0.5f) * width;
            float cy = (0.5f - ndcY * 0.5f) * height;

            double tanCrit = sinCrit / Math.sqrt(Math.max(1e-6, 1.0 - sinCrit * sinCrit));
            float radPx = (float) ((tanCrit / Math.max(1e-4, tanHalfFov)) * (height * 0.5f));
            if (radPx > 6f && radPx < Math.max(width, height) * 1.4f) {
                ui.drawCircleOutline(cx, cy, radPx, 72, 1.8f, 0.24f, 0.96f, 0.88f, 0.88f);
                sb.setLength(0);
                sb.append("CRITICAL THROAT RIM (sin th=").append(Math.round(sinCrit * 100.0) / 100.0).append(')');
                ui.drawText(sb, cx - 122f, cy + radPx + 10f, 0.78f, 0.24f, 0.96f, 0.88f, 0.96f);
            }
        }

        // Top-center orientation / side pill
        String sideText;
        float sr, sg, sbCol;
        if (Math.abs(lCam) <= a) {
            sideText = "INSIDE THROAT CYLINDER |l| <= a (r = rho)";
            sr = 0.98f; sg = 0.56f; sbCol = 0.98f;
        } else if (lCam > 0.0) {
            sideText = "OBSERVER: UNIVERSE A (l > 0) -> APERTURE SHOWS UNIVERSE B";
            sr = 0.34f; sg = 0.92f; sbCol = 1.00f;
        } else {
            sideText = "OBSERVER: UNIVERSE B (l < 0) -> APERTURE SHOWS UNIVERSE A";
            sr = 1.00f; sg = 0.74f; sbCol = 0.30f;
        }
        float bw = 410f;
        float bx = (width - bw) * 0.5f;
        float by = (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR
                || state.scientificDiagMode == SimulationState.DIAG_STEP_DENSITY) ? 88f : 18f;
        ui.drawGlassPanel(bx, by, bw, 36f, 7f, 0.03f, 0.05f, 0.09f, 0.86f, sr, sg, sbCol, 0.54f);
        ui.drawText(sideText, bx + 12f, by + 6f, 0.76f, sr, sg, sbCol, 0.96f);
        sb.setLength(0);
        sb.append("l_cam=");
        PlotWidgets.appendSigned2(sb, (float) lCam);
        sb.append("M   r(l_cam)=");
        PlotWidgets.appendFixed2(sb, (float) rCam);
        sb.append("M   rho=");
        PlotWidgets.appendFixed2(sb, rho);
        sb.append("M");
        ui.drawText(sb, bx + 12f, by + 20f, 0.74f, 0.90f, 0.94f, 1.00f, 0.92f);
    }

    private void renderEmbeddingAndRaysPanel(UiRenderer ui, int width, int height,
                                             SimulationState state, CameraController camera,
                                             float rho, float a, float mw) {
        boolean fullHud = (state.hudMode == SimulationState.HUD_FULL);
        float bottomGuard = (state.hudMode == SimulationState.HUD_CLEAN) ? 84f : 124f;
        float pw = Math.min(392f, Math.max(290f, width * 0.28f));
        float px = 16f;
        float py = fullHud ? 416f : (state.hudMode == SimulationState.HUD_COMPACT ? 54f : 18f);
        float availH = (height - bottomGuard) - py;
        if (availH < 230f) {
            return;
        }
        float ph = Math.min(410f, availH);

        ui.drawGlassPanel(px, py, pw, ph, 9f, 0.03f, 0.05f, 0.09f, 0.88f, 0.28f, 0.92f, 0.96f, 0.56f);
        PlotWidgets.drawPanelHeader(ui, px, py, pw, "FLAMM/DNEG EMBEDDING & RAYS", "[E] EXACT METRIC", 0.34f, 0.94f, 0.98f);

        // Embedding diagram viewport: vertical axis z(l) [+14..-14 -> Univ A top, Univ B bottom], horizontal +/- r(l)
        float diagX = px + 10f;
        float diagY = py + 24f;
        float diagW = pw - 20f;
        float diagH = Math.max(115f, ph - 168f);
        ui.drawGlassPanel(diagX, diagY, diagW, diagH, 5f, 0.02f, 0.03f, 0.06f, 0.88f, 1f, 1f, 1f, 0.14f);

        float cx = diagX + diagW * 0.5f;
        float cy = diagY + diagH * 0.5f;
        float scaleX = (diagW * 0.44f) / Math.max(1f, maxEmbR);
        float scaleZ = (diagH * 0.43f) / Math.max(1f, maxEmbZ);

        // Throat l = 0 axis and Throat Cylinder |l| <= a band
        ui.drawDashedLine(diagX + 4f, cy, diagX + diagW - 4f, cy, 1.0f, 3f, 3f, 0.98f, 0.42f, 0.86f, 0.42f);
        ui.drawDashedLine(cx, diagY + 4f, cx, diagY + diagH - 4f, 1.0f, 3f, 3f, 1f, 1f, 1f, 0.16f);

        // Draw left & right embedding silhouette curves (-r(l), z(l)) and (+r(l), z(l))
        for (int i = 0; i < EMBED_SAMPLES - 1; i++) {
            boolean inThroat = Math.abs(embL[i]) <= a && Math.abs(embL[i + 1]) <= a;
            float cr = inThroat ? 0.98f : 0.64f;
            float cg = inThroat ? 0.46f : 0.78f;
            float cb = inThroat ? 0.94f : 0.92f;
            float thk = inThroat ? 2.2f : 1.5f;

            float z0 = cy - embZ[i] * scaleZ;
            float z1 = cy - embZ[i + 1] * scaleZ;
            ui.drawLine(cx + embR[i] * scaleX, z0, cx + embR[i + 1] * scaleX, z1, thk, cr, cg, cb, 0.92f);
            ui.drawLine(cx - embR[i] * scaleX, z0, cx - embR[i + 1] * scaleX, z1, thk, cr, cg, cb, 0.92f);
        }

        // Overlay horizontal throat rings at l = 0 and flare lips l = +/- a
        float throatHalfW = rho * scaleX;
        ui.drawLine(cx - throatHalfW, cy, cx + throatHalfW, cy, 1.4f, 0.98f, 0.46f, 0.94f, 0.85f);
        float zA = a * scaleZ;
        ui.drawDashedLine(cx - throatHalfW, cy - zA, cx + throatHalfW, cy - zA, 1.0f, 2f, 2f, 0.98f, 0.76f, 0.34f, 0.65f);
        ui.drawDashedLine(cx - throatHalfW, cy + zA, cx + throatHalfW, cy + zA, 1.0f, 2f, 2f, 0.98f, 0.76f, 0.34f, 0.65f);

        // Plot 6 cached DOPRI5(4) null rays projected onto (x = r(l)*cos(phi), z(l)) across the funnel
        for (int g = 0; g < RAY_COUNT; g++) {
            boolean pass = rayTraversed[g];
            float cr = pass ? 0.24f : 1.00f;
            float cg = pass ? 0.94f : 0.62f;
            float cb = pass ? 1.00f : 0.22f;
            for (int i = 0; i < rayLen[g] - 1; i++) {
                float x0 = cx + rayR[g][i] * scaleX;
                float y0 = cy - rayZ[g][i] * scaleZ;
                float x1 = cx + rayR[g][i + 1] * scaleX;
                float y1 = cy - rayZ[g][i + 1] * scaleZ;
                if (y0 >= diagY + 2f && y0 <= diagY + diagH - 2f && y1 >= diagY + 2f && y1 <= diagY + diagH - 2f) {
                    ui.drawLine(x0, y0, x1, y1, 1.55f, cr, cg, cb, 0.90f);
                }
            }
        }

        // Live observer marker on the embedding surface
        double lCam = camera.currentWormholeL();
        double rCam = MorrisThorneMetric.radius(lCam, rho, a, mw);
        double zCam = MorrisThorneMetric.embeddingZ(lCam, a, mw);
        float camPx = cx + (float) rCam * scaleX;
        float camPy = cy - (float) zCam * scaleZ;
        if (camPy >= diagY + 3f && camPy <= diagY + diagH - 3f) {
            ui.drawCircleOutline(camPx, camPy, 4.5f, 16, 1.8f, 0.42f, 1.00f, 0.62f, 0.98f);
            ui.drawText("CAM", camPx + 6f, camPy - 4f, 0.68f, 0.42f, 1.00f, 0.62f, 0.96f);
        }

        ui.drawText("UNIV A (l>0) | z(l)=int sqrt(1-r'^2)dl", diagX + 5f, diagY + 4f, 0.66f, 0.36f, 0.92f, 1.00f, 0.90f);
        ui.drawText("THROAT |l|<=a: r=rho", cx + throatHalfW + 4f, cy - 5f, 0.66f, 0.98f, 0.54f, 0.94f, 0.92f);
        ui.drawText("UNIV B (l<0) | THRU(CYN) REFL(AMB)", diagX + 5f, diagY + diagH - 13f, 0.66f, 1.00f, 0.74f, 0.34f, 0.90f);

        // DOPRI5(4) Ray Telemetry Table below diagram
        float ty = diagY + diagH + 7f;
        ui.drawText("H/rho  FATE   DEFLECT dPhi  MAX|NULL ERR|", px + 10f, ty, 0.68f, 0.78f, 0.90f, 1.00f, 0.94f);
        ty += 12f;
        for (int g = 0; g < RAY_COUNT; g++) {
            sb.setLength(0);
            PlotWidgets.appendFixed2(sb, (float) H_RATIO_PRESETS[g]);
            sb.append(rayTraversed[g] ? "  THRU   " : "  REFL   ");
            PlotWidgets.appendFixed1(sb, rayDeflectDeg[g]);
            sb.append(" deg    ");
            PlotWidgets.appendSci1(sb, rayMaxNullErr[g]);
            ui.drawText(sb, px + 10f, ty, 0.68f,
                    rayTraversed[g] ? 0.32f : 1.00f,
                    rayTraversed[g] ? 0.94f : 0.72f,
                    rayTraversed[g] ? 1.00f : 0.28f, 0.92f);
            ty += 12f;
        }

        // Honest Ultrastatic g_tt = -1 & NEC Exotic Matter Disclosure
        ui.drawText("[E] g_tt=-1 (ZERO GRAV REDSHIFT) | NEC VIOLATION",
                px + 10f, py + ph - 14f, 0.66f, 1.00f, 0.62f, 0.94f, 0.94f);
    }

    private void updateCache(float rho, float a, float mw) {
        if (Math.abs(rho - cachedRho) < 1e-3f && Math.abs(a - cachedA) < 1e-3f
                && Math.abs(mw - cachedMw) < 1e-3f) {
            return;
        }
        cachedRho = rho;
        cachedA = a;
        cachedMw = mw;

        double lBound = 14.0;
        maxEmbR = 1.0f;
        maxEmbZ = 1.0f;
        for (int i = 0; i < EMBED_SAMPLES; i++) {
            double l = -lBound + (2.0 * lBound * i) / (EMBED_SAMPLES - 1);
            double r = MorrisThorneMetric.radius(l, rho, a, mw);
            double z = MorrisThorneMetric.embeddingZ(l, a, mw);
            embL[i] = (float) l;
            embR[i] = (float) r;
            embZ[i] = (float) z;
            maxEmbR = Math.max(maxEmbR, (float) r);
            maxEmbZ = Math.max(maxEmbZ, (float) Math.abs(z));
        }

        double lStart = 12.0;
        double rStart = MorrisThorneMetric.radius(lStart, rho, a, mw);
        for (int g = 0; g < RAY_COUNT; g++) {
            double hParam = H_RATIO_PRESETS[g] * rho;
            if (hParam >= rStart * 0.999) {
                hParam = rStart * 0.995;
            }
            double vl0 = -Math.sqrt(Math.max(1e-6, 1.0 - (hParam * hParam) / (rStart * rStart)));
            rayState3[0] = lStart;
            rayState3[1] = vl0;
            rayState3[2] = 0.0;

            double phiStart = (g % 2 == 0) ? 0.25 : (Math.PI - 0.25);
            double phiSign = (g % 2 == 0) ? 1.0 : -1.0;
            rayR[g][0] = (float) (rStart * Math.cos(phiStart));
            rayZ[g][0] = (float) MorrisThorneMetric.embeddingZ(lStart, a, mw);

            final int idx = g;
            final int[] count = {1};
            final boolean[] thru = {false};
            final double[] maxErr = {0.0};
            final double hConst = hParam;

            OdeSystem ode = MorrisThorneMetric.nullGeodesicOde(rho, a, mw, hParam);
            dopri.integrate(ode, 0.0, rayState3, 38.0, rayCfg, diag, (lam, y, stepH) -> {
                double lCur = y[0];
                double vlCur = y[1];
                double phiCur = y[2];
                double err = Math.abs(MorrisThorneMetric.nullResidual(lCur, vlCur, rho, a, mw, hConst));
                if (err > maxErr[0]) {
                    maxErr[0] = err;
                }
                if (lCur < 0.0) {
                    thru[0] = true;
                }
                if (count[0] < MAX_RAY_PTS) {
                    int k = count[0];
                    double rCur = MorrisThorneMetric.radius(lCur, rho, a, mw);
                    rayR[idx][k] = (float) (rCur * Math.cos(phiStart + phiSign * phiCur));
                    rayZ[idx][k] = (float) MorrisThorneMetric.embeddingZ(lCur, a, mw);
                    count[0]++;
                }
                return count[0] < MAX_RAY_PTS && Math.abs(lCur) <= 13.5;
            });

            rayLen[g] = count[0];
            rayTraversed[g] = thru[0];
            if (thru[0]) {
                rayDeflectDeg[g] = (float) Math.abs(Math.toDegrees(rayState3[2]));
            } else {
                rayDeflectDeg[g] = (float) Math.abs(Math.toDegrees(rayState3[2]) - 180.0);
            }
            rayMaxNullErr[g] = maxErr[0];
        }
    }
}
