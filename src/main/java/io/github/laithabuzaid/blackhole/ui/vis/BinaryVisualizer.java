package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.math.BinarySpacetimeMetric;
import io.github.laithabuzaid.blackhole.math.GravitationalWaveDetector;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;

/**
 * Mode-specific scientific visualizer for the EOB Binary Black Hole Merger (Phase 2A-H).
 *
 * <p>Visualizes 3D orbital trajectories, velocities, L_hat, oblate component/remnant horizons
 * and aligned spins, multipolar GW propagation, individual mode waveforms + mode power +
 * time-frequency chirp spectrogram, detector antenna pattern + polarization ellipse + true
 * SI strains (h+, hx, h_det), and energy/angular-momentum conservation.</p>
 */
public final class BinaryVisualizer {

    private final Projection3D proj = new Projection3D();
    private final float[] ptA = new float[2];
    private final float[] ptB = new float[2];
    private final StringBuilder sb = new StringBuilder(160);
    private final StringBuilder sb2 = new StringBuilder(120);

    public void render(UiRenderer ui, int width, int height,
                       SimulationState state, CameraController camera,
                       BinaryMergerPhysics binary, GwReferenceData gwRef) {
        if (binary == null) {
            return;
        }
        float aspect = (float) width / Math.max(1, height);
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));

        // 1. 3D Spacetime Geometry, Trajectory, Horizons, Spins & GW Propagation Overlays
        render3dOverlays(ui, width, height, aspect, tanHalfFov, state, camera, binary);

        // 2. 2D Scientific Diagnostic Panels (non-overlapping layout respecting HUD_FULL)
        boolean fullHud = (state.hudMode == SimulationState.HUD_FULL);
        float bottomGuard = (state.hudMode == SimulationState.HUD_CLEAN) ? 84f : 124f;
        float sideW = Math.min(392f, Math.max(270f, width * 0.265f));

        if (state.visBinGwModes) {
            float topY = fullHud ? 416f : (state.hudMode == SimulationState.HUD_COMPACT ? 54f : 18f);
            float availH = (height - bottomGuard) - topY;
            if (availH >= 200f) {
                float ph = Math.min(410f, availH);
                renderModesAndSpectrogramPanel(ui, 16f, topY, sideW, ph, state, binary);
            }
        }

        if (state.visBinDetector) {
            float topY = fullHud ? 520f : 18f;
            float availH = (height - bottomGuard) - topY;
            if (availH >= 200f) {
                float ph = Math.min(420f, availH);
                renderDetectorAndSiStrainPanel(ui, width - sideW - 16f, topY, sideW, ph, state, binary, gwRef);
            }
        }

        if (state.visBinEnergy && width >= 1040) {
            float centerLeft = fullHud ? 416f : (sideW + 32f);
            float centerRight = width - (fullHud ? 388f : (sideW + 32f));
            float cw = centerRight - centerLeft;
            if (cw >= 340f) {
                float cy = (state.plungeActive && fullHud) ? 120f : 16f;
                renderEnergyAndAngMomPanel(ui, centerLeft, cy, cw, 114f, binary);
            }
        }
    }

    private void render3dOverlays(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                  SimulationState state, CameraController cam,
                                  BinaryMergerPhysics binary) {
        float e1x = binary.orbE1X, e1y = binary.orbE1Y, e1z = binary.orbE1Z;
        float lHx = binary.orbLHX, lHy = binary.orbLHY, lHz = binary.orbLHZ;
        float e3x = binary.orbE3X, e3y = binary.orbE3Y, e3z = binary.orbE3Z;

        // Orbital plane reference grid, coordinate axes & inclination indicator
        if (state.visBinTrajectory || state.visBinHorizonsSpins) {
            float planeR = Math.max(10.5f, (float) binary.parameters().initialSepM * 0.72f);
            proj.drawBasisRing(ui, w, h, aspect, tanHalfFov, cam, 0f, 0f, 0f,
                    e1x, e1y, e1z, e3x, e3y, e3z, planeR, 56, 1.0f, true, 0.34f, 0.62f, 0.88f, 0.32f);
            proj.drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                    -planeR * e1x, -planeR * e1y, -planeR * e1z,
                    planeR * e1x, planeR * e1y, planeR * e1z, 1.0f, true, 0.42f, 0.70f, 0.94f, 0.28f);
            proj.drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                    -planeR * e3x, -planeR * e3y, -planeR * e3z,
                    planeR * e3x, planeR * e3y, planeR * e3z, 1.0f, true, 0.42f, 0.70f, 0.94f, 0.28f);

            // Orbital inclination label at +e3 edge
            if (proj.projectPoint(planeR * e3x, planeR * e3y, planeR * e3z, cam, w, h, aspect, tanHalfFov, ptA)) {
                sb.setLength(0);
                sb.append("ORBIT PLANE (i_orb=");
                int inc = Math.round((float) binary.parameters().orbitalIncDeg);
                if (inc > 0) sb.append('+');
                sb.append(inc).append(" deg)");
                ui.drawText(sb, ptA[0] - 74f, ptA[1] + 6f, 0.76f, 0.54f, 0.80f, 1.00f, 0.86f);
            }
        }

        // Actual EOB Orbital Trails, Velocities, Angular Momentum L_hat & Merger Location
        if (state.visBinTrajectory) {
            int count = binary.visHistCount();
            int start = binary.visHistStartIdx();
            float[] t1x = binary.trail1X(), t1y = binary.trail1Y(), t1z = binary.trail1Z();
            float[] t2x = binary.trail2X(), t2y = binary.trail2Y(), t2z = binary.trail2Z();
            byte[] tph = binary.trailPhase();

            for (int i = 0; i < count - 1; i++) {
                int i0 = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
                int i1 = (start + i + 1) % BinaryMergerPhysics.VIS_HISTORY_LEN;
                float age = (i + 1) / (float) Math.max(1, count);
                boolean plungeSeg = tph[i1] >= BinaryMergerPhysics.Phase.PLUNGE.ordinal();
                float aLine = (0.18f + 0.68f * age) * (plungeSeg ? 1.0f : 0.85f);
                proj.drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                        t1x[i0], t1y[i0], t1z[i0], t1x[i1], t1y[i1], t1z[i1],
                        plungeSeg ? 2.0f : 1.5f, false,
                        plungeSeg ? 0.22f : 0.30f, 0.92f, 1.00f, aLine);
                proj.drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                        t2x[i0], t2y[i0], t2z[i0], t2x[i1], t2y[i1], t2z[i1],
                        plungeSeg ? 2.0f : 1.5f, false,
                        1.00f, plungeSeg ? 0.44f : 0.74f, 0.28f, aLine);
            }

            // Separation chord & radial motion readout
            if (binary.mergeBlend < 0.85f) {
                proj.drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                        binary.pos1X, binary.pos1Y, binary.pos1Z,
                        binary.pos2X, binary.pos2Y, binary.pos2Z,
                        1.3f, true, 0.86f, 0.92f, 1.00f, 0.48f);

                // Velocity vectors v1, v2
                float vScale = 4.6f;
                proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                        binary.pos1X, binary.pos1Y, binary.pos1Z,
                        binary.pos1X + binary.vel1X * vScale, binary.pos1Y + binary.vel1Y * vScale, binary.pos1Z + binary.vel1Z * vScale,
                        1.9f, 7f, 0.24f, 0.96f, 0.86f, 0.92f);
                proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                        binary.pos2X, binary.pos2Y, binary.pos2Z,
                        binary.pos2X + binary.vel2X * vScale, binary.pos2Y + binary.vel2Y * vScale, binary.pos2Z + binary.vel2Z * vScale,
                        1.9f, 7f, 1.00f, 0.80f, 0.32f, 0.92f);

                if (proj.projectPoint(
                        0.5f * (binary.pos1X + binary.pos2X),
                        0.5f * (binary.pos1Y + binary.pos2Y) + 0.45f,
                        0.5f * (binary.pos1Z + binary.pos2Z),
                        cam, w, h, aspect, tanHalfFov, ptA)) {
                    sb.setLength(0);
                    sb.append("r=");
                    PlotWidgets.appendFixed2(sb, binary.currentSeparation);
                    sb.append("M  dr/dt=");
                    PlotWidgets.appendSigned2(sb, binary.radialVel);
                    sb.append("  Om=");
                    PlotWidgets.appendFixed3(sb, binary.orbitalOmegaGeom);
                    if (binary.parameters().eccentricity > 0.005) {
                        sb.append(" e0=");
                        PlotWidgets.appendFixed2(sb, (float) binary.parameters().eccentricity);
                    }
                    ui.drawText(sb, ptA[0] - 88f, ptA[1] - 14f, 0.78f, 0.92f, 0.96f, 1.00f, 0.92f);
                }
            }

            // Orbital angular momentum vector L_hat from COM
            float lLen = 3.8f;
            proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                    0f, 0f, 0f, lHx * lLen, lHy * lLen, lHz * lLen,
                    2.2f, 8f, 0.36f, 0.98f, 0.62f, 0.92f);
            if (proj.projectPoint(lHx * (lLen + 0.4f), lHy * (lLen + 0.4f), lHz * (lLen + 0.4f),
                    cam, w, h, aspect, tanHalfFov, ptA)) {
                ui.drawText("L_orb (ALIGNED AXIS)", ptA[0] - 54f, ptA[1] - 12f, 0.78f, 0.36f, 0.98f, 0.62f, 0.94f);
            }

            // Merger location marker
            if (binary.hasMergerOccurred) {
                if (proj.projectPoint(binary.mergerComX, binary.mergerComY, binary.mergerComZ, cam, w, h, aspect, tanHalfFov, ptA)) {
                    ui.drawLine(ptA[0] - 7f, ptA[1] - 7f, ptA[0] + 7f, ptA[1] + 7f, 1.8f, 1.00f, 0.38f, 0.76f, 0.92f);
                    ui.drawLine(ptA[0] - 7f, ptA[1] + 7f, ptA[0] + 7f, ptA[1] - 7f, 1.8f, 1.00f, 0.38f, 0.76f, 0.92f);
                    sb.setLength(0);
                    sb.append("MERGER @ t=");
                    PlotWidgets.appendFixed1(sb, (float) binary.mergerGeomTime);
                    sb.append('M');
                    ui.drawText(sb, ptA[0] + 10f, ptA[1] - 6f, 0.78f, 1.00f, 0.48f, 0.82f, 0.95f);
                }
            }
        }

        // Individual Kerr-Schild Horizons & Aligned Spin Vectors (or Remnant Horizon + Spin)
        if (state.visBinHorizonsSpins) {
            if (binary.mergeBlend < 0.92f) {
                double rPlus1 = BinarySpacetimeMetric.componentHorizonRadius(binary.mass1, binary.chi1);
                double a1 = binary.mass1 * binary.chi1;
                double rPlus2 = BinarySpacetimeMetric.componentHorizonRadius(binary.mass2, binary.chi2);
                double a2 = binary.mass2 * binary.chi2;

                drawComponentHorizonAndSpin(ui, w, h, aspect, tanHalfFov, cam,
                        binary.pos1X, binary.pos1Y, binary.pos1Z,
                        e1x, e1y, e1z, lHx, lHy, lHz, e3x, e3y, e3z,
                        rPlus1, a1, binary.mass1, binary.chi1, "BH1",
                        0.32f, 0.90f, 1.00f);
                drawComponentHorizonAndSpin(ui, w, h, aspect, tanHalfFov, cam,
                        binary.pos2X, binary.pos2Y, binary.pos2Z,
                        e1x, e1y, e1z, lHx, lHy, lHz, e3x, e3y, e3z,
                        rPlus2, a2, binary.mass2, binary.chi2, "BH2",
                        1.00f, 0.74f, 0.34f);
            }
            if (binary.mergeBlend > 0.12f) {
                double rPlusRem = BinarySpacetimeMetric.componentHorizonRadius(binary.remnantMass, binary.remnantSpin);
                double aRem = binary.remnantMass * binary.remnantSpin;
                float blendAlpha = 0.25f + 0.72f * binary.mergeBlend;
                proj.drawKerrEquatorialRing(ui, w, h, aspect, tanHalfFov, cam, 0f, 0f, 0f,
                        e1x, e1y, e1z, e3x, e3y, e3z, rPlusRem, aRem, 40, 1.8f, false,
                        0.34f, 0.98f, 0.76f, blendAlpha);
                proj.drawKerrConstRMeridian(ui, w, h, aspect, tanHalfFov, cam, 0f, 0f, 0f,
                        e1x, e1y, e1z, lHx, lHy, lHz, e3x, e3y, e3z, rPlusRem, aRem, 0.0, 28, 1.4f, false,
                        0.34f, 0.98f, 0.76f, blendAlpha * 0.82f);
                float sLen = 1.4f + 2.4f * Math.abs(binary.remnantSpin);
                float sDir = (binary.remnantSpin >= 0f) ? 1f : -1f;
                proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam, 0f, 0f, 0f,
                        lHx * sLen * sDir, lHy * sLen * sDir, lHz * sLen * sDir,
                        2.1f, 8f, 0.38f, 1.00f, 0.80f, blendAlpha);
                if (proj.projectPoint(lHx * (sLen + 0.35f) * sDir, lHy * (sLen + 0.35f) * sDir, lHz * (sLen + 0.35f) * sDir,
                        cam, w, h, aspect, tanHalfFov, ptA)) {
                    sb.setLength(0);
                    sb.append("REMNANT Mf=");
                    PlotWidgets.appendFixed3(sb, binary.remnantMass);
                    sb.append(" af=");
                    PlotWidgets.appendSigned3(sb, binary.remnantSpin);
                    ui.drawText(sb, ptA[0] - 60f, ptA[1] + 6f, 0.78f, 0.38f, 1.00f, 0.80f, blendAlpha);
                }
            }
        }

        // Wave propagation & explicit scale disclosure
        if (state.visBinPropagation && state.binaryGwOverlay) {
            float rIn = Math.max(4.0f, binary.currentSeparation * 1.15f);
            float rOut = rIn + 4.8f;
            proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                    e1x * rIn, e1y * rIn, e1z * rIn, e1x * rOut, e1y * rOut, e1z * rOut,
                    1.7f, 7f, 0.24f, 0.88f, 1.00f, 0.84f);
            proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                    -e1x * rIn, -e1y * rIn, -e1z * rIn, -e1x * rOut, -e1y * rOut, -e1z * rOut,
                    1.7f, 7f, 0.24f, 0.88f, 1.00f, 0.84f);
            proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                    e3x * rIn, e3y * rIn, e3z * rIn, e3x * rOut, e3y * rOut, e3z * rOut,
                    1.7f, 7f, 0.72f, 0.40f, 1.00f, 0.84f);
            proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam,
                    -e3x * rIn, -e3y * rIn, -e3z * rIn, -e3x * rOut, -e3y * rOut, -e3z * rOut,
                    1.7f, 7f, 0.72f, 0.40f, 1.00f, 0.84f);
            if (proj.projectPoint(e1x * (rOut + 0.5f), e1y * (rOut + 0.5f), e1z * (rOut + 0.5f),
                    cam, w, h, aspect, tanHalfFov, ptA)) {
                sb.setLength(0);
                sb.append("GW PROPAGATION (+CYAN / -VIOLET, ").append(state.binGwModeLabel()).append(", SCALE=1.0x VIS)");
                ui.drawText(sb, ptA[0] - 90f, ptA[1] - 12f, 0.74f, 0.36f, 0.90f, 1.00f, 0.90f);
            }
        }
    }

    private void drawComponentHorizonAndSpin(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                             CameraController cam,
                                             float bx, float by, float bz,
                                             float e1x, float e1y, float e1z,
                                             float lHx, float lHy, float lHz,
                                             float e3x, float e3y, float e3z,
                                             double rPlus, double aPhys, float mass, float chi, String name,
                                             float r, float g, float b) {
        proj.drawKerrEquatorialRing(ui, w, h, aspect, tanHalfFov, cam, bx, by, bz,
                e1x, e1y, e1z, e3x, e3y, e3z, rPlus, aPhys, 28, 1.5f, false, r, g, b, 0.86f);
        proj.drawKerrConstRMeridian(ui, w, h, aspect, tanHalfFov, cam, bx, by, bz,
                e1x, e1y, e1z, lHx, lHy, lHz, e3x, e3y, e3z, rPlus, aPhys, 0.0, 20, 1.2f, false, r, g, b, 0.65f);

        float spinDir = (chi >= 0f) ? 1f : -1f;
        float sLen = (float) rPlus * (1.25f + 1.85f * Math.abs(chi));
        proj.drawWorldArrow(ui, w, h, aspect, tanHalfFov, cam, bx, by, bz,
                bx + lHx * sLen * spinDir, by + lHy * sLen * spinDir, bz + lHz * sLen * spinDir,
                1.8f, 6.5f, r, g, b, 0.94f);

        if (proj.projectPoint(bx, by + (float) rPlus + 0.5f, bz, cam, w, h, aspect, tanHalfFov, ptA)) {
            sb.setLength(0);
            sb.append(name).append(" m=").append(Math.round(mass * 100f) / 100f).append(" chi=");
            PlotWidgets.appendSigned2(sb, chi);
            float yOff = "BH1".equals(name) ? -22f : 16f;
            float xOff = "BH1".equals(name) ? -68f : 10f;
            ui.drawText(sb, ptA[0] + xOff, ptA[1] + yOff, 0.72f, r, g, b, 0.94f);
        }
    }

    private void renderModesAndSpectrogramPanel(UiRenderer ui, float x, float y, float w, float h,
                                                SimulationState state, BinaryMergerPhysics binary) {
        ui.drawGlassPanel(x, y, w, h, 9f, 0.03f, 0.05f, 0.09f, 0.88f, 0.32f, 0.86f, 1.00f, 0.48f);
        PlotWidgets.drawPanelHeader(ui, x, y, w, "MULTIPOLAR GW & CHIRP", "[A]+[F] EOB/QNM", 0.36f, 0.90f, 1.00f);

        float px = x + 10f;
        float pw = w - 20f;
        float py = y + 23f;

        int count = binary.visHistCount();
        int start = binary.visHistStartIdx();
        float[] m22 = binary.histMode22(), m21 = binary.histMode21();
        float[] m33 = binary.histMode33(), m44 = binary.histMode44();
        float[] hTot = binary.histGeomHPlus();
        float[] a22 = binary.histAmp22(), a21 = binary.histAmp21(), a33 = binary.histAmp33(), a44 = binary.histAmp44();

        float maxScale = 0.06f;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
            maxScale = Math.max(maxScale, Math.abs(hTot[idx]));
            maxScale = Math.max(maxScale, Math.abs(m22[idx]));
        }
        maxScale *= 1.15f;

        // Stacked Mode Tracks (2,2), (2,1), (3,3), (4,4), Total Reconstructed h+
        float trackH = Math.max(18f, (h - 196f) / 5f);
        drawModeTrack(ui, px, py, pw, trackH, "(2,2)", m22, start, count, maxScale,
                state.binGwModeFilter == 0 || state.binGwModeFilter == 1, 0.28f, 0.90f, 1.00f);
        py += trackH + 3f;
        drawModeTrack(ui, px, py, pw, trackH, "(2,1)", m21, start, count, maxScale,
                state.binGwModeFilter == 0 || state.binGwModeFilter == 2, 1.00f, 0.78f, 0.32f);
        py += trackH + 3f;
        drawModeTrack(ui, px, py, pw, trackH, "(3,3)", m33, start, count, maxScale,
                state.binGwModeFilter == 0 || state.binGwModeFilter == 3, 1.00f, 0.42f, 0.74f);
        py += trackH + 3f;
        drawModeTrack(ui, px, py, pw, trackH, "(4,4)", m44, start, count, maxScale,
                state.binGwModeFilter == 0 || state.binGwModeFilter == 4, 0.70f, 0.52f, 1.00f);
        py += trackH + 3f;
        drawModeTrack(ui, px, py, pw, trackH, "TOTAL", hTot, start, count, maxScale,
                true, 0.42f, 0.98f, 0.68f);
        py += trackH + 6f;

        // Mode Power Breakdown Bars
        ui.drawText("INSTANTANEOUS MODE POWER", px, py, 0.74f, 0.74f, 0.84f, 0.94f, 0.90f);
        py += 11f;
        float barW = (pw - 8f) * 0.5f;
        drawCompactPowerBar(ui, px, py, barW, "(2,2)", (float) binary.powerFrac22, 0.28f, 0.90f, 1.00f);
        drawCompactPowerBar(ui, px + barW + 8f, py, barW, "(2,1)", (float) binary.powerFrac21, 1.00f, 0.78f, 0.32f);
        py += 13f;
        drawCompactPowerBar(ui, px, py, barW, "(3,3)", (float) binary.powerFrac33, 1.00f, 0.42f, 0.74f);
        drawCompactPowerBar(ui, px + barW + 8f, py, barW, "(4,4)", (float) binary.powerFrac44, 0.70f, 0.52f, 1.00f);
        py += 16f;

        // Time-Frequency Chirp & Ringdown Spectrogram
        float specH = Math.max(46f, (y + h - 22f) - py);
        ui.drawGlassPanel(px, py, pw, specH, 5f, 0.02f, 0.03f, 0.06f, 0.90f, 1f, 1f, 1f, 0.14f);
        float qnm220Hz = (float) GravitationalWaveDetector.geomOmegaToHz(
                binary.qnmFreqGeom(), binary.parameters().detectorMassSolar);
        float fMaxHz = Math.max(260f, qnm220Hz * 1.45f);
        sb.setLength(0);
        sb.append("CHIRP SPECTROGRAM (0-").append(Math.round(fMaxHz)).append(" Hz)");
        ui.drawText(sb, px + 5f, py + 4f, 0.72f, 0.80f, 0.88f, 0.96f, 0.90f);

        float plotY = py + 15f;
        float plotH = specH - 18f;
        // Horizontal QNM f220 target line
        float qnmY = plotY + plotH * (1.0f - Math.min(1f, qnm220Hz / fMaxHz));
        ui.drawDashedLine(px + 3f, qnmY, px + pw - 3f, qnmY, 1.0f, 4f, 3f, 0.38f, 0.98f, 0.72f, 0.50f);

        if (count >= 2) {
            float[] freqs = binary.histGwFreqHz();
            float[] times = binary.histGeomTime();
            float colW = Math.max(1.5f, (pw - 6f) / (count - 1));
            float peakA = 0.04f;
            for (int i = 0; i < count; i++) {
                int idx = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
                peakA = Math.max(peakA, a22[idx]);
            }
            for (int i = 0; i < count; i++) {
                int idx = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
                float cx = px + 3f + i * colW;
                float f22 = freqs[idx];
                drawSpecCell(ui, cx, plotY, plotH, colW, f22, fMaxHz, a22[idx] / peakA, 0.25f, 0.88f, 1.00f);
                if (a21[idx] > 1e-4f) {
                    drawSpecCell(ui, cx, plotY, plotH, colW, f22 * 0.5f, fMaxHz, Math.min(1f, a21[idx] / peakA * 3f), 1.00f, 0.78f, 0.30f);
                }
                if (a33[idx] > 1e-4f) {
                    drawSpecCell(ui, cx, plotY, plotH, colW, f22 * 1.5f, fMaxHz, Math.min(1f, a33[idx] / peakA * 3f), 1.00f, 0.42f, 0.76f);
                }
                if (a44[idx] > 1e-4f) {
                    drawSpecCell(ui, cx, plotY, plotH, colW, f22 * 2.0f, fMaxHz, Math.min(1f, a44[idx] / peakA * 3f), 0.72f, 0.52f, 1.00f);
                }
                if (binary.hasMergerOccurred && i > 0) {
                    int prevIdx = (start + i - 1) % BinaryMergerPhysics.VIS_HISTORY_LEN;
                    if (times[prevIdx] < binary.mergerGeomTime && times[idx] >= binary.mergerGeomTime) {
                        ui.drawLine(cx, plotY, cx, plotY + plotH, 1.2f, 1.00f, 0.45f, 0.80f, 0.72f);
                    }
                }
            }
        }
        py += specH + 5f;

        sb.setLength(0);
        sb.append("fGW=");
        PlotWidgets.appendFixed1(sb, binary.gwFreqHz);
        sb.append("Hz  f220=");
        PlotWidgets.appendFixed1(sb, qnm220Hz);
        sb.append("Hz  tau220=");
        PlotWidgets.appendFixed1(sb, (float) binary.qnmTauGeom());
        sb.append("M  [").append(binary.phase().label()).append(']');
        ui.drawText(sb, px, py, 0.74f, 0.86f, 0.94f, 1.00f, 0.94f);
    }

    private void drawModeTrack(UiRenderer ui, float x, float y, float w, float h,
                               String label, float[] ring, int start, int count,
                               float maxAbs, boolean emphasized, float r, float g, float b) {
        ui.drawGlassPanel(x, y, w, h, 4f, 0.02f, 0.03f, 0.06f, 0.80f, r, g, b, emphasized ? 0.34f : 0.14f);
        ui.drawRect(x + 42f, y + h * 0.5f, w - 46f, 1f, 0f, 1f, 1f, 1f, 0.12f);
        ui.drawText(label, x + 4f, y + h * 0.5f - 4f, 0.74f, r, g, b, emphasized ? 0.98f : 0.48f);
        PlotWidgets.drawTimeSeriesFloat(ui, x + 44f, y + 2f, w - 48f, h - 4f,
                ring, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, -maxAbs, maxAbs,
                emphasized ? 1.55f : 1.0f, false, r, g, b, emphasized ? 0.95f : 0.42f);
    }

    private void drawCompactPowerBar(UiRenderer ui, float x, float y, float w, String label, float frac,
                                     float r, float g, float b) {
        sb2.setLength(0);
        PlotWidgets.appendFixed1(sb2, frac * 100f);
        sb2.append('%');
        ui.drawText(label, x, y, 0.72f, r, g, b, 0.94f);
        float bx = x + 38f;
        float bw = Math.max(24f, w - 84f);
        ui.drawProgressBar(bx, y + 2f, bw, 5.5f, frac, r, g, b);
        ui.drawText(sb2, bx + bw + 4f, y, 0.72f, 0.88f, 0.92f, 0.98f, 0.92f);
    }

    private static void drawSpecCell(UiRenderer ui, float cx, float plotY, float plotH, float colW,
                                     float freqHz, float fMaxHz, float normAmp, float r, float g, float b) {
        if (freqHz <= 0f || freqHz > fMaxHz || normAmp <= 0.02f) {
            return;
        }
        float fy = plotY + plotH * (1.0f - freqHz / fMaxHz);
        float intensity = Math.min(1f, 0.25f + 0.75f * (float) Math.sqrt(normAmp));
        ui.drawRect(cx, fy - 2f, Math.max(2f, colW + 0.5f), 4f, 1f, r, g, b, intensity * 0.85f);
    }

    private void renderDetectorAndSiStrainPanel(UiRenderer ui, float x, float y, float w, float h,
                                                SimulationState state, BinaryMergerPhysics binary,
                                                GwReferenceData gwRef) {
        ui.drawGlassPanel(x, y, w, h, 9f, 0.03f, 0.05f, 0.09f, 0.88f, 0.36f, 0.92f, 0.72f, 0.48f);
        PlotWidgets.drawPanelHeader(ui, x, y, w, "DETECTOR & SI STRAIN", "[E] -2Ylm+JKS", 0.42f, 0.96f, 0.74f);

        float px = x + 10f;
        float pw = w - 20f;
        float py = y + 24f;

        // Top row: Antenna Pattern Polar Diagram + Inclination Polarization Ellipse + Detector Params
        float diagSize = 86f;
        float antCx = px + diagSize * 0.5f;
        float antCy = py + diagSize * 0.5f;
        ui.drawGlassPanel(px, py, diagSize, diagSize, 6f, 0.02f, 0.03f, 0.06f, 0.88f, 1f, 1f, 1f, 0.16f);
        float lobeR = diagSize * 0.40f;
        ui.drawCircleOutline(antCx, antCy, lobeR, 28, 1.0f, 1f, 1f, 1f, 0.14f);
        // Interferometer L-arms
        ui.drawLine(antCx, antCy, antCx + lobeR * 0.78f, antCy, 1.6f, 0.85f, 0.90f, 1.00f, 0.72f);
        ui.drawLine(antCx, antCy, antCx, antCy - lobeR * 0.78f, 1.6f, 0.85f, 0.90f, 1.00f, 0.72f);

        BinaryParameters bp = binary.parameters();
        double sinT = Math.sin(bp.skyThetaRad);
        double psi = bp.polarizationPsiRad;
        float prevPx = 0f, prevPy = 0f;
        for (int i = 0; i <= 48; i++) {
            double phi = (2.0 * Math.PI * i) / 48.0;
            double fp = GravitationalWaveDetector.antennaFPlus(bp.skyThetaRad, phi, psi);
            double fx = GravitationalWaveDetector.antennaFCross(bp.skyThetaRad, phi, psi);
            double resp = Math.sqrt(fp * fp + fx * fx);
            float lx = antCx + (float) (lobeR * resp * Math.cos(phi));
            float ly = antCy - (float) (lobeR * resp * Math.sin(phi));
            if (i > 0) {
                ui.drawLine(prevPx, prevPy, lx, ly, 1.4f, 0.36f, 0.90f, 1.00f, 0.90f);
            }
            prevPx = lx;
            prevPy = ly;
        }
        // Source sky direction vector on polar diagram
        double activeResp = Math.hypot(binary.antennaFPlus, binary.antennaFCross);
        float srcX = antCx + (float) (lobeR * (0.25 + 0.75 * sinT) * Math.cos(bp.skyPhiRad));
        float srcY = antCy - (float) (lobeR * (0.25 + 0.75 * sinT) * Math.sin(bp.skyPhiRad));
        ui.drawArrow(antCx, antCy, srcX, srcY, 1.6f, 5f, 1.00f, 0.82f, 0.34f, 0.96f);
        ui.drawIndicatorDot(srcX, srcY, 2.5f, 1.00f, 0.82f, 0.34f, 1.0f);
        ui.drawText("ANTENNA", px + 18f, py + diagSize - 11f, 0.66f, 0.72f, 0.84f, 0.96f, 0.86f);

        // Polarization Ellipse (shows h+ vs hx for current observer inclination iota)
        float polX = px + diagSize + 6f;
        ui.drawGlassPanel(polX, py, diagSize, diagSize, 6f, 0.02f, 0.03f, 0.06f, 0.88f, 1f, 1f, 1f, 0.16f);
        float polCx = polX + diagSize * 0.5f;
        float polCy = py + diagSize * 0.5f;
        float polR = diagSize * 0.36f;
        ui.drawRect(polX + 6f, polCy, diagSize - 12f, 1f, 0f, 1f, 1f, 1f, 0.14f);
        ui.drawRect(polCx, py + 6f, 1f, diagSize - 12f, 0f, 1f, 1f, 1f, 0.14f);
        double iotaRad = Math.toRadians(bp.observerIncDeg);
        double cosI = Math.cos(iotaRad);
        double pScale = 0.5 * (1.0 + cosI * cosI);
        double cScale = cosI;
        for (int i = 0; i <= 36; i++) {
            double ph = (2.0 * Math.PI * i) / 36.0;
            float ex = polCx + (float) (polR * pScale * Math.cos(ph));
            float ey = polCy - (float) (polR * cScale * Math.sin(ph));
            if (i > 0) {
                ui.drawLine(prevPx, prevPy, ex, ey, 1.5f, 0.82f, 0.54f, 1.00f, 0.92f);
            }
            prevPx = ex;
            prevPy = ey;
        }
        // Instantaneous strain vector dot
        double maxPol = Math.max(1e-30, binary.geomAmplitude);
        float curPx = polCx + (float) (polR * (binary.geomHPlus / Math.max(0.05, maxPol)) * 0.85);
        float curPy = polCy - (float) (polR * (binary.geomHCross / Math.max(0.05, maxPol)) * 0.85);
        ui.drawIndicatorDot(curPx, curPy, 2.8f, 0.36f, 0.98f, 0.82f, 0.98f);
        ui.drawText("POL (h+,hx)", polX + 6f, py + diagSize - 11f, 0.66f, 0.82f, 0.64f, 1.00f, 0.86f);

        // Detector & Cosmological Readouts
        float infoX = polX + diagSize + 8f;
        float iy = py + 2f;
        ui.drawText(bp.detectorPreset.label(), infoX, iy, 0.78f, 0.42f, 0.96f, 0.74f, 0.98f); iy += 13f;
        sb.setLength(0); sb.append("F+="); PlotWidgets.appendSigned2(sb, (float) binary.antennaFPlus);
        ui.drawText(sb, infoX, iy, 0.74f, 0.86f, 0.92f, 0.98f, 0.92f); iy += 12f;
        sb.setLength(0); sb.append("Fx="); PlotWidgets.appendSigned2(sb, (float) binary.antennaFCross);
        ui.drawText(sb, infoX, iy, 0.74f, 0.86f, 0.92f, 0.98f, 0.92f); iy += 12f;
        sb.setLength(0); sb.append("iota=").append(Math.round(bp.observerIncDeg)).append(" deg");
        ui.drawText(sb, infoX, iy, 0.74f, 0.96f, 0.82f, 0.50f, 0.92f); iy += 12f;
        sb.setLength(0); sb.append("DL=").append(Math.round(bp.luminosityDistMpc)).append("Mpc");
        ui.drawText(sb, infoX, iy, 0.74f, 0.78f, 0.88f, 0.98f, 0.92f); iy += 12f;
        sb.setLength(0); sb.append("z="); PlotWidgets.appendFixed3(sb, (float) bp.redshiftZ);
        ui.drawText(sb, infoX, iy, 0.74f, 0.78f, 0.88f, 0.98f, 0.92f); iy += 12f;
        sb.setLength(0); sb.append("R=").append(Math.round(activeResp * 100.0)).append('%');
        ui.drawText(sb, infoX, iy, 0.72f, 0.65f, 0.84f, 0.95f, 0.88f);

        py += diagSize + 8f;

        // Three Separate Physical SI Strain Plots: h+(t), hx(t), h_det(t)
        int count = binary.visHistCount();
        int start = binary.visHistStartIdx();
        double[] hp = binary.histPhysHPlus();
        double[] hx = binary.histPhysHCross();
        double[] hd = binary.histPhysHDet();

        double maxSi = 1e-24;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
            maxSi = Math.max(maxSi, Math.abs(hp[idx]));
            maxSi = Math.max(maxSi, Math.abs(hx[idx]));
            maxSi = Math.max(maxSi, Math.abs(hd[idx]));
        }
        maxSi *= 1.15;

        float plotH = Math.max(36f, ((y + h - 10f) - py - 10f) / 3f);
        drawSiStrainPlot(ui, px, py, pw, plotH, "h_+ (t) [SI]", hp, start, count, maxSi,
                binary.physHPlus, null, false, 0.28f, 0.90f, 1.00f);
        py += plotH + 5f;
        drawSiStrainPlot(ui, px, py, pw, plotH, "h_x (t) [SI]", hx, start, count, maxSi,
                binary.physHCross, null, false, 0.76f, 0.48f, 1.00f);
        py += plotH + 5f;
        drawSiStrainPlot(ui, px, py, pw, plotH, "h_det = F+h+ + Fxhx [SI]", hd, start, count, maxSi,
                binary.detectorStrain, gwRef, state.showGwReference, 0.36f, 0.98f, 0.68f);
    }

    private void drawSiStrainPlot(UiRenderer ui, float x, float y, float w, float h,
                                  String label, double[] ring, int start, int count,
                                  double maxAbs, double curVal, GwReferenceData gwRef, boolean drawRef,
                                  float r, float g, float b) {
        ui.drawGlassPanel(x, y, w, h, 5f, 0.02f, 0.03f, 0.06f, 0.86f, r, g, b, 0.28f);
        sb.setLength(0);
        sb.append(label).append(" = ");
        PlotWidgets.appendSignedSci1(sb, curVal);
        ui.drawText(sb, x + 5f, y + 4f, 0.72f, r, g, b, 0.96f);

        sb2.setLength(0);
        sb2.append("+/-");
        PlotWidgets.appendSci1(sb2, maxAbs);
        float sw = sb2.length() * 8f * 0.68f;
        ui.drawText(sb2, x + w - sw - 5f, y + 4f, 0.68f, 0.72f, 0.80f, 0.90f, 0.88f);

        float py = y + 14f;
        float ph = h - 17f;
        ui.drawRect(x + 4f, py + ph * 0.5f, w - 8f, 1f, 0f, 1f, 1f, 1f, 0.14f);
        PlotWidgets.drawTimeSeriesDouble(ui, x + 4f, py, w - 8f, ph,
                ring, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, -maxAbs, maxAbs,
                1.65f, r, g, b, 0.95f);

        if (drawRef && gwRef != null && gwRef.sampleCount() >= 2) {
            int nRef = 64;
            float stepX = (w - 8f) / (nRef - 1);
            for (int i = 0; i < nRef - 1; i++) {
                float u0 = i / (float) (nRef - 1);
                float u1 = (i + 1) / (float) (nRef - 1);
                float v0 = gwRef.sampleStrainAtNormalized(u0);
                float v1 = gwRef.sampleStrainAtNormalized(u1);
                float y0 = py + ph * 0.5f - v0 * (ph * 0.44f);
                float y1 = py + ph * 0.5f - v1 * (ph * 0.44f);
                ui.drawDashedLine(x + 4f + i * stepX, y0, x + 4f + (i + 1) * stepX, y1,
                        1.1f, 3f, 3f, 1.00f, 0.78f, 0.34f, 0.68f);
            }
            ui.drawText("REF (NORM)", x + 6f, y + h - 10f, 0.64f, 1.00f, 0.78f, 0.34f, 0.85f);
        }
    }

    private void renderEnergyAndAngMomPanel(UiRenderer ui, float x, float y, float w, float h,
                                            BinaryMergerPhysics binary) {
        ui.drawGlassPanel(x, y, w, h, 8f, 0.03f, 0.05f, 0.09f, 0.88f, 0.96f, 0.78f, 0.36f, 0.48f);
        PlotWidgets.drawPanelHeader(ui, x, y, w, "ENERGY & ANGULAR MOMENTUM EVOLUTION", "[A]+[F] EOB/BMR12", 0.96f, 0.80f, 0.40f);

        int count = binary.visHistCount();
        int start = binary.visHistStartIdx();
        float[] eOrb = binary.histOrbitalEnergy();
        float[] eRad = binary.histRadiatedEnergy();
        float[] lOrb = binary.histOrbitalAngMom();
        float[] jRad = binary.histRadiatedAngMom();

        float halfW = (w - 28f) * 0.5f;
        float plotY = y + 23f;
        float plotH = h - 54f;

        // Left: Energy Evolution (E_bind & E_rad)
        float ex = x + 10f;
        ui.drawGlassPanel(ex, plotY, halfW, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.84f, 1f, 1f, 1f, 0.14f);
        float eMin = -0.15f, eMax = 0.08f;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % BinaryMergerPhysics.VIS_HISTORY_LEN;
            eMin = Math.min(eMin, eOrb[idx] - 0.01f);
            eMax = Math.max(eMax, eRad[idx] + 0.01f);
        }
        PlotWidgets.drawTimeSeriesFloat(ui, ex + 4f, plotY + 4f, halfW - 8f, plotH - 8f,
                eOrb, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, eMin, eMax, 1.55f, false, 0.32f, 0.88f, 1.00f, 0.94f);
        PlotWidgets.drawTimeSeriesFloat(ui, ex + 4f, plotY + 4f, halfW - 8f, plotH - 8f,
                eRad, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, eMin, eMax, 1.55f, false, 1.00f, 0.74f, 0.32f, 0.94f);

        sb.setLength(0);
        sb.append("E0="); PlotWidgets.appendFixed3(sb, (float) binary.initialBindingEnergy());
        sb.append(" Erad="); PlotWidgets.appendFixed4(sb, binary.radiatedEnergy);
        sb.append(" Mf="); PlotWidgets.appendFixed3(sb, binary.remnantMass);
        ui.drawText(sb, ex, plotY + plotH + 5f, 0.72f, 0.86f, 0.92f, 0.98f, 0.92f);

        sb2.setLength(0);
        sb2.append("E_bind(CYAN) E_rad(AMB) | BAL_ERR=");
        PlotWidgets.appendSci1(sb2, binary.eobEnergyBalanceErr);
        ui.drawText(sb2, ex, plotY + plotH + 17f, 0.68f, 0.74f, 0.84f, 0.94f, 0.90f);

        // Right: Angular Momentum Evolution (L_orb & J_rad)
        float lx = ex + halfW + 8f;
        ui.drawGlassPanel(lx, plotY, halfW, plotH, 5f, 0.02f, 0.03f, 0.06f, 0.84f, 1f, 1f, 1f, 0.14f);
        float lMax = Math.max(1.1f, (float) binary.initialTotalAngMom() * 1.1f);
        PlotWidgets.drawTimeSeriesFloat(ui, lx + 4f, plotY + 4f, halfW - 8f, plotH - 8f,
                lOrb, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, 0f, lMax, 1.55f, false, 0.38f, 0.96f, 0.68f, 0.94f);
        PlotWidgets.drawTimeSeriesFloat(ui, lx + 4f, plotY + 4f, halfW - 8f, plotH - 8f,
                jRad, start, count, BinaryMergerPhysics.VIS_HISTORY_LEN, 0f, lMax, 1.55f, false, 1.00f, 0.44f, 0.74f, 0.94f);

        sb.setLength(0);
        sb.append("J0="); PlotWidgets.appendFixed3(sb, (float) binary.initialTotalAngMom());
        sb.append(" Lorb="); PlotWidgets.appendFixed3(sb, binary.orbitalAngularMomentum);
        sb.append(" Jrad="); PlotWidgets.appendFixed3(sb, binary.radiatedAngularMomentum);
        ui.drawText(sb, lx, plotY + plotH + 5f, 0.72f, 0.86f, 0.92f, 0.98f, 0.92f);

        sb2.setLength(0);
        sb2.append("L_orb(GRN) J_rad(PNK) | af=");
        PlotWidgets.appendSigned3(sb2, binary.remnantSpin);
        sb2.append(" chi=").append(Math.round(binary.chi1 * 100f) / 100f).append('/').append(Math.round(binary.chi2 * 100f) / 100f);
        ui.drawText(sb2, lx, plotY + plotH + 17f, 0.68f, 0.74f, 0.84f, 0.94f, 0.90f);
    }
}
