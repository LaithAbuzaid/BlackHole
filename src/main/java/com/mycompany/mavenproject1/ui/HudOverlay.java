package com.mycompany.mavenproject1.ui;

import com.mycompany.mavenproject1.core.CameraController;
import com.mycompany.mavenproject1.core.SimulationState;
import com.mycompany.mavenproject1.telemetry.HardwareTelemetry;

/**
 * Interactive glassmorphic Hardware Profiler (Left Panel) and collapsible
 * Spacetime & Optics Console (Right Panel) with zero heap allocations per frame.
 */
public final class HudOverlay {

    // Action IDs
    public static final int ACT_LENSING = 1;
    public static final int ACT_DISK = 2;
    public static final int ACT_JETS = 3;
    public static final int ACT_DOPPLER = 4;
    public static final int ACT_KERR_SPIN = 5;
    public static final int ACT_FREE_FALL = 6;
    public static final int ACT_ADAPTIVE_AA = 7;
    public static final int ACT_SKY_GRID = 8;
    public static final int ACT_ORDER_VIS = 9;
    public static final int ACT_AUTO_ORBIT = 10;
    public static final int ACT_VSYNC = 11;
    public static final int ACT_PAUSE = 12;
    public static final int ACT_TOGGLE_PANEL = 100;

    public static final int SLIDER_SPIN = 201;
    public static final int SLIDER_TEMP = 202;
    public static final int SLIDER_DENSITY = 203;
    public static final int SLIDER_JET_POWER = 204;
    public static final int SLIDER_BLOOM = 205;
    public static final int SLIDER_EXPOSURE = 206;
    public static final int SLIDER_FOV = 207;

    public static final int PRESET_EDGE = 301;
    public static final int PRESET_POLE = 302;
    public static final int PRESET_RING = 303;
    public static final int PRESET_DIVE = 304;
    public static final int PRESET_RESET = 305;

    private static final int MAX_HITBOXES = 64;

    private static final class HitBox {
        float x, y, w, h;
        int id;
        float min, max;

        void set(float x, float y, float w, float h, int id, float min, float max) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.id = id;
            this.min = min;
            this.max = max;
        }
    }

    private final HitBox[] hitBoxes = new HitBox[MAX_HITBOXES];
    private int hitBoxCount = 0;
    private int activeSliderId = -1;

    // Reusable StringBuilder for zero-allocation formatted telemetry & slider labels
    private final StringBuilder sb = new StringBuilder(128);

    public HudOverlay() {
        for (int i = 0; i < MAX_HITBOXES; i++) {
            hitBoxes[i] = new HitBox();
        }
    }

    public void clearHitBoxes() {
        hitBoxCount = 0;
    }

    public boolean isDraggingSlider() {
        return activeSliderId != -1;
    }

    public void releaseSlider() {
        activeSliderId = -1;
    }

    public void render(UiRenderer ui, int windowW, int windowH,
                       double mouseX, double mouseY,
                       SimulationState state, CameraController camera,
                       HardwareTelemetry telemetry) {
        hitBoxCount = 0;
        ui.begin(windowW, windowH);

        renderLeftProfilerPanel(ui, windowW, windowH, state, camera, telemetry);
        renderRightControlPanel(ui, windowW, mouseX, mouseY, state);
        renderBottomHintStrip(ui, windowW, windowH);

        ui.end();
    }

    private void renderLeftProfilerPanel(UiRenderer ui, int windowW, int windowH,
                                         SimulationState state, CameraController camera,
                                         HardwareTelemetry telemetry) {
        float lx = 16f;
        float ly = 16f;
        float lw = 374f;
        float lh = 374f;

        ui.drawGlassPanel(lx, ly, lw, lh, 10f,
                0.07f, 0.09f, 0.14f, 0.84f,
                0.42f, 0.68f, 0.96f, 0.36f);

        float cx = lx + 14f;
        float cy = ly + 13f;
        float innerW = lw - 28f;

        ui.drawText("KERR-SCHILD PROFILER & TELEMETRY", cx, cy, 1.30f, 0.95f, 0.78f, 0.48f, 1.0f);
        cy += 19f;
        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        cy += 9f;

        // FPS, Wall-Clock Frame Time, Resolution
        float fpsR = telemetry.currentFps >= 58f ? 0.35f : 0.98f;
        float fpsG = telemetry.currentFps >= 58f ? 0.95f : 0.45f;
        sb.setLength(0);
        sb.append("FPS: ");
        appendFixed1(sb, telemetry.currentFps);
        sb.append("  FRAME: ");
        appendFixed2(sb, telemetry.wallFrameMs);
        sb.append("ms (");
        sb.append(windowW).append('x').append(windowH).append(')');
        ui.drawText(sb, cx, cy, 1.20f, fpsR, fpsG, 0.55f, 1.0f);
        cy += 19f;

        // GPU Device & Load
        sb.setLength(0);
        sb.append("GPU: ").append(telemetry.gpuShortName);
        ui.drawText(sb, cx, cy, 1.15f, 0.55f, 0.85f, 1.0f, 1.0f);
        cy += 15f;

        float gpuLoadPct = telemetry.getGpuLoadPercent(state.vsync);
        sb.setLength(0);
        sb.append("GPU LOAD: ");
        appendFixed1(sb, gpuLoadPct);
        sb.append("%   SHADER: ");
        appendFixed2(sb, telemetry.gpuTotalMs);
        sb.append(" ms");
        ui.drawText(sb, cx, cy, 1.15f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 13f;
        ui.drawProgressBar(cx, cy, innerW, 6f, gpuLoadPct / 100f, 0.25f, 0.82f, 1.0f);
        cy += 12f;

        // Per-Pass Async GPU Timer Queries (Raytrace / Bloom / UI)
        sb.setLength(0);
        sb.append("RAY: ");
        appendFixed2(sb, telemetry.gpuRayMs);
        sb.append(" | BLM: ");
        appendFixed2(sb, telemetry.gpuBloomMs);
        sb.append(" | UI: ");
        appendFixed2(sb, telemetry.gpuUiMs);
        sb.append(" ms");
        ui.drawText(sb, cx, cy, 1.10f, 0.72f, 0.80f, 0.92f, 0.96f);
        cy += 14f;

        // VRAM, Temperature & Graphics Clock
        if (telemetry.vramTotalMb > 0) {
            float vramPct = Math.min(1f, (float) telemetry.vramUsedMb / telemetry.vramTotalMb);
            sb.setLength(0);
            sb.append("VRAM: ").append(telemetry.vramUsedMb).append('/').append(telemetry.vramTotalMb).append("MB");
            if (telemetry.gpuTempC > 0) {
                sb.append(" | ").append(telemetry.gpuTempC).append("°C ");
                sb.append(telemetry.gpuClockMhz).append("MHz");
            }
            ui.drawText(sb, cx, cy, 1.10f, 0.72f, 0.78f, 0.88f, 0.95f);
            cy += 12f;
            ui.drawProgressBar(cx, cy, innerW, 5f, vramPct, 0.65f, 0.55f, 1.0f);
            cy += 13f;
        } else {
            cy += 4f;
        }

        // CPU & JVM Metrics
        sb.setLength(0);
        sb.append("CPU (").append(telemetry.cpuCores).append("C)  SUBMIT: ");
        appendFixed2(sb, telemetry.cpuSubmitMs);
        sb.append(" ms");
        ui.drawText(sb, cx, cy, 1.15f, 0.45f, 0.95f, 0.72f, 1.0f);
        cy += 14f;

        sb.setLength(0);
        sb.append("JVM CPU: ");
        appendFixed1(sb, telemetry.processCpuPct);
        sb.append("%   SYS CPU: ");
        appendFixed1(sb, telemetry.systemCpuPct);
        sb.append('%');
        ui.drawText(sb, cx, cy, 1.15f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 13f;
        ui.drawProgressBar(cx, cy, innerW, 6f, telemetry.processCpuPct / 100f, 0.32f, 0.92f, 0.65f);
        cy += 12f;

        sb.setLength(0);
        sb.append("JVM HEAP: ").append(telemetry.heapUsedMb).append(" / ").append(telemetry.heapMaxMb).append(" MB");
        ui.drawText(sb, cx, cy, 1.10f, 0.72f, 0.78f, 0.88f, 0.95f);
        cy += 15f;

        // Live Kerr Spacetime Telemetry Strip
        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        cy += 7f;
        sb.setLength(0);
        sb.append("r=");
        appendFixed2(sb, (float) camera.currentKerrRadius());
        sb.append("M  r+=");
        appendFixed2(sb, (float) state.outerHorizonRadius());
        sb.append("M  ISCO=");
        appendFixed2(sb, (float) state.progradeIscoRadius());
        sb.append('M');
        ui.drawText(sb, cx, cy, 1.08f, 0.96f, 0.82f, 0.56f, 0.96f);
        cy += 13f;

        sb.setLength(0);
        sb.append("FRAME: ");
        sb.append(state.freeFallMode ? "FREE-FALL (DORAN)" : "STATIC SHELL TETRAD");
        boolean insideHorizon = camera.currentKerrRadius() <= state.outerHorizonRadius();
        if (insideHorizon) {
            sb.append(" [IN r+]");
        }
        ui.drawText(sb, cx, cy, 1.05f,
                insideHorizon ? 1.0f : 0.62f,
                insideHorizon ? 0.45f : 0.86f,
                insideHorizon ? 0.52f : 1.0f, 0.95f);
        cy += 16f;

        // Rolling 120-Frame GPU vs CPU Oscilloscope Graph
        float gx = cx;
        float gy = cy;
        float gw = innerW;
        float gh = 72f;
        ui.drawGlassPanel(gx, gy, gw, gh, 5f, 0.03f, 0.04f, 0.07f, 0.86f, 1f, 1f, 1f, 0.14f);
        ui.drawText("GPU(CYAN) CPU(GRN) | 16.6ms 6.9ms", gx + 6f, gy + 5f, 1.00f, 0.65f, 0.72f, 0.82f, 0.85f);

        float maxScaleMs = 20.0f;
        for (int i = 0; i < HardwareTelemetry.HISTORY_LEN; i++) {
            int idx = (telemetry.historyCursor + i) % HardwareTelemetry.HISTORY_LEN;
            float m = Math.max(telemetry.gpuHistory[idx], telemetry.cpuHistory[idx]) * 1.15f;
            if (m > maxScaleMs) {
                maxScaleMs = m;
            }
        }

        float plotH = gh - 20f;
        float baseLineY = gy + gh - 4f;

        // 16.67 ms (60 FPS) reference marker
        float line60Y = baseLineY - (16.67f / maxScaleMs) * plotH;
        if (line60Y > gy + 15f && line60Y < baseLineY) {
            ui.drawRect(gx + 4f, line60Y, gw - 8f, 1f, 0f, 1.0f, 0.65f, 0.25f, 0.42f);
        }

        // 6.94 ms (144 FPS) reference marker
        float line144Y = baseLineY - (6.94f / maxScaleMs) * plotH;
        if (line144Y > gy + 15f && line144Y < baseLineY) {
            ui.drawRect(gx + 4f, line144Y, gw - 8f, 1f, 0f, 0.65f, 0.55f, 1.0f, 0.42f);
        }

        float stepX = (gw - 8f) / (HardwareTelemetry.HISTORY_LEN - 1);
        for (int i = 0; i < HardwareTelemetry.HISTORY_LEN - 1; i++) {
            int i0 = (telemetry.historyCursor + i) % HardwareTelemetry.HISTORY_LEN;
            int i1 = (telemetry.historyCursor + i + 1) % HardwareTelemetry.HISTORY_LEN;

            float x0 = gx + 4f + i * stepX;
            float x1 = gx + 4f + (i + 1) * stepX;

            float gY0 = baseLineY - Math.min(1f, telemetry.gpuHistory[i0] / maxScaleMs) * plotH;
            float gY1 = baseLineY - Math.min(1f, telemetry.gpuHistory[i1] / maxScaleMs) * plotH;
            ui.drawLine(x0, gY0, x1, gY1, 1.65f, 0.25f, 0.85f, 1.0f, 0.95f);

            float cY0 = baseLineY - Math.min(1f, telemetry.cpuHistory[i0] / maxScaleMs) * plotH;
            float cY1 = baseLineY - Math.min(1f, telemetry.cpuHistory[i1] / maxScaleMs) * plotH;
            ui.drawLine(x0, cY0, x1, cY1, 1.45f, 0.32f, 0.95f, 0.65f, 0.90f);
        }
    }

    private void renderRightControlPanel(UiRenderer ui, int windowW,
                                         double mouseX, double mouseY,
                                         SimulationState state) {
        float rw = 332f;
        float rx = windowW - rw - 16f;
        float ry = 16f;
        float rh = state.controlsExpanded ? 592f : 42f;

        ui.drawGlassPanel(rx, ry, rw, rh, 10f,
                0.07f, 0.09f, 0.14f, 0.84f,
                0.95f, 0.72f, 0.42f, 0.36f);

        float px = rx + 14f;
        float py = ry + 13f;
        float innerW = rw - 28f;

        ui.drawText("SPACETIME & OPTICS CONSOLE", px, py, 1.30f, 0.95f, 0.78f, 0.48f, 1.0f);

        // Collapse/Expand Toggle Button
        float btnW = 24f;
        float btnH = 20f;
        float btnX = rx + rw - btnW - 10f;
        float btnY = ry + 10f;
        ui.drawGlassPanel(btnX, btnY, btnW, btnH, 4f, 1f, 1f, 1f, 0.10f, 1f, 1f, 1f, 0.25f);
        ui.drawText(state.controlsExpanded ? "-" : "+", btnX + 8f, btnY + 5f, 1.25f, 1f, 1f, 1f, 0.92f);
        addHitBox(btnX, btnY, btnW, btnH, ACT_TOGGLE_PANEL, 0f, 0f);

        if (!state.controlsExpanded) {
            return;
        }

        py += 20f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        py += 9f;

        // 2-Column Interactive Toggle Grid (12 Toggles synced with hotkeys)
        float colW = (innerW - 6f) * 0.5f;
        float rowH = 23f;

        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[L] LENSING", state.lensingEnabled, ACT_LENSING,
                px + colW + 6f, "[D] DISK", state.showDisk, ACT_DISK);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[J] JETS", state.showJets, ACT_JETS,
                px + colW + 6f, "[B] DOPPLER", state.dopplerEnabled, ACT_DOPPLER);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[K] KERR SPIN", state.kerrSpinEnabled, ACT_KERR_SPIN,
                px + colW + 6f, "[F] FREE-FALL", state.freeFallMode, ACT_FREE_FALL);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[M] 4X ADPT AA", state.adaptiveAa4x, ACT_ADAPTIVE_AA,
                px + colW + 6f, "[C] SKY GRID", state.showGrid, ACT_SKY_GRID);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[I] ORDER VIS", state.orderVisualizer, ACT_ORDER_VIS,
                px + colW + 6f, "[O] AUTO ORBIT", state.autoOrbit, ACT_AUTO_ORBIT);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[V] V-SYNC", state.vsync, ACT_VSYNC,
                px + colW + 6f, "[SPC] PAUSE", state.paused, ACT_PAUSE);

        py += 4f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        py += 9f;

        // 7 Real-Time Draggable Physics & Optics Sliders
        sb.setLength(0);
        appendFixed3(sb, state.spinParam);
        py = drawSlider(ui, px, py, innerW, "KERR SPIN (a*)", state.spinParam,
                SimulationState.MIN_SPIN, SimulationState.MAX_SPIN, sb, SLIDER_SPIN);

        sb.setLength(0);
        sb.append(Math.round(11800f * state.diskTempScale)).append(" K");
        py = drawSlider(ui, px, py, innerW, "DISK TEMP (K)", state.diskTempScale,
                SimulationState.MIN_TEMP_SCALE, SimulationState.MAX_TEMP_SCALE, sb, SLIDER_TEMP);

        sb.setLength(0);
        appendFixed2(sb, state.diskDensity);
        sb.append('x');
        py = drawSlider(ui, px, py, innerW, "PLASMA DENSITY", state.diskDensity,
                SimulationState.MIN_DENSITY, SimulationState.MAX_DENSITY, sb, SLIDER_DENSITY);

        sb.setLength(0);
        appendFixed2(sb, state.jetPower);
        sb.append('x');
        py = drawSlider(ui, px, py, innerW, "JET POWER", state.jetPower,
                SimulationState.MIN_JET_POWER, SimulationState.MAX_JET_POWER, sb, SLIDER_JET_POWER);

        sb.setLength(0);
        appendFixed2(sb, state.bloomStrength);
        py = drawSlider(ui, px, py, innerW, "BLOOM GLOW", state.bloomStrength,
                SimulationState.MIN_BLOOM, SimulationState.MAX_BLOOM, sb, SLIDER_BLOOM);

        sb.setLength(0);
        appendFixed2(sb, state.exposure);
        py = drawSlider(ui, px, py, innerW, "HDR EXPOSURE", state.exposure,
                SimulationState.MIN_EXPOSURE, SimulationState.MAX_EXPOSURE, sb, SLIDER_EXPOSURE);

        sb.setLength(0);
        sb.append(Math.round(state.fovDeg * 2f)).append('°');
        py = drawSlider(ui, px, py, innerW, "CAMERA FOV", state.fovDeg,
                SimulationState.MIN_FOV_DEG, SimulationState.MAX_FOV_DEG, sb, SLIDER_FOV);

        py += 4f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        py += 9f;

        // 5 Camera Preset Buttons (2 clean rows)
        ui.drawText("CAMERA PRESETS:", px, py, 1.10f, 0.72f, 0.78f, 0.88f, 0.95f);
        py += 14f;

        float row1BtnW = (innerW - 12f) / 3f;
        drawPresetButton(ui, mouseX, mouseY, px, py, row1BtnW, 23f, "[1] EDGE", PRESET_EDGE);
        drawPresetButton(ui, mouseX, mouseY, px + (row1BtnW + 6f), py, row1BtnW, 23f, "[2] POLAR", PRESET_POLE);
        drawPresetButton(ui, mouseX, mouseY, px + (row1BtnW + 6f) * 2f, py, row1BtnW, 23f, "[3] RING", PRESET_RING);
        py += 29f;

        float row2BtnW = (innerW - 6f) * 0.5f;
        drawPresetButton(ui, mouseX, mouseY, px, py, row2BtnW, 23f, "[4] HORIZON DIVE", PRESET_DIVE);
        drawPresetButton(ui, mouseX, mouseY, px + row2BtnW + 6f, py, row2BtnW, 23f, "[R] RESET ALL", PRESET_RESET);
    }

    private void renderBottomHintStrip(UiRenderer ui, int windowW, int windowH) {
        String hint = "[H] HUD  |  DRAG: ORBIT  |  SCROLL: ZOOM  |  [F] FREE-FALL  |  [J] JETS  |  [ESC] QUIT";
        float hintW = hint.length() * 8f * 1.05f + 22f;
        float hx = (windowW - hintW) * 0.5f;
        float hy = windowH - 32f;
        ui.drawGlassPanel(hx, hy, hintW, 22f, 6f, 0.06f, 0.08f, 0.12f, 0.74f, 1f, 1f, 1f, 0.16f);
        ui.drawText(hint, hx + 11f, hy + 6f, 1.05f, 0.76f, 0.82f, 0.92f, 0.88f);
    }

    private float drawToggleRow(UiRenderer ui, double mouseX, double mouseY,
                                float x1, float y, float w, float h,
                                String label1, boolean state1, int id1,
                                float x2, String label2, boolean state2, int id2) {
        drawTogglePill(ui, mouseX, mouseY, x1, y, w, h, label1, state1, id1);
        drawTogglePill(ui, mouseX, mouseY, x2, y, w, h, label2, state2, id2);
        return y + h + 5f;
    }

    private void drawTogglePill(UiRenderer ui, double mouseX, double mouseY,
                                float x, float y, float w, float h,
                                String label, boolean active, int id) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        float bgR = active ? 0.16f : 0.11f;
        float bgG = active ? 0.34f : 0.13f;
        float bgB = active ? 0.50f : 0.18f;
        float bgA = hovered ? 0.92f : 0.76f;

        float bdR = active ? 0.35f : 0.55f;
        float bdG = active ? 0.82f : 0.60f;
        float bdB = active ? 1.00f : 0.70f;
        float bdA = active ? 0.78f : 0.22f;

        ui.drawGlassPanel(x, y, w, h, 5f, bgR, bgG, bgB, bgA, bdR, bdG, bdB, bdA);
        ui.drawText(label, x + 7f, y + 7f, 1.08f,
                active ? 0.96f : 0.68f,
                active ? 0.98f : 0.72f,
                active ? 1.00f : 0.80f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private float drawSlider(UiRenderer ui, float x, float y, float w,
                             String label, float val, float min, float max,
                             CharSequence valText, int id) {
        ui.drawText(label, x, y, 1.10f, 0.78f, 0.84f, 0.92f, 0.95f);
        float valW = valText.length() * 8f * 1.10f;
        ui.drawText(valText, x + w - valW, y, 1.10f, 0.95f, 0.78f, 0.48f, 1.0f);

        float trackY = y + 14f;
        float trackH = 5f;
        float norm = Math.max(0f, Math.min(1f, (val - min) / (max - min)));

        ui.drawRect(x, trackY, w, trackH, 2.5f, 1f, 1f, 1f, 0.14f);
        ui.drawRect(x, trackY, Math.max(4f, w * norm), trackH, 2.5f, 0.95f, 0.68f, 0.32f, 0.92f);

        float thumbX = x + w * norm - 4.5f;
        ui.drawRect(thumbX, trackY - 3f, 9f, 11f, 4f, 1f, 1f, 1f, 0.98f);

        addHitBox(x - 4f, y, w + 8f, 24f, id, min, max);
        return y + 29f;
    }

    private void drawPresetButton(UiRenderer ui, double mouseX, double mouseY,
                                  float x, float y, float w, float h,
                                  String label, int id) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        ui.drawGlassPanel(x, y, w, h, 5f,
                hovered ? 0.24f : 0.14f,
                hovered ? 0.28f : 0.17f,
                hovered ? 0.38f : 0.24f,
                0.86f, 0.95f, 0.75f, 0.45f, hovered ? 0.78f : 0.30f);
        float textW = label.length() * 8f * 1.02f;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 7f, 1.02f, 0.95f, 0.95f, 0.98f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    /**
     * Handles a left mouse click against active HUD hitboxes.
     *
     * @return action ID triggered (> 0), or {@code -1} if a slider drag started
     *         (already handled), or {@code 0} if click did not hit any HUD control.
     */
    public int handleClick(double mx, double my, SimulationState state) {
        if (!state.showUi) {
            return 0;
        }
        for (int i = 0; i < hitBoxCount; i++) {
            HitBox box = hitBoxes[i];
            if (mx >= box.x && mx <= box.x + box.w && my >= box.y && my <= box.y + box.h) {
                if (box.id >= 200 && box.id < 300) {
                    activeSliderId = box.id;
                    updateSliderFromMouse(box, mx, state);
                    return -1;
                }
                return box.id;
            }
        }
        return 0;
    }

    public boolean handleSliderDrag(double mx, SimulationState state) {
        if (activeSliderId == -1) {
            return false;
        }
        for (int i = 0; i < hitBoxCount; i++) {
            HitBox box = hitBoxes[i];
            if (box.id == activeSliderId) {
                updateSliderFromMouse(box, mx, state);
                return true;
            }
        }
        return false;
    }

    private void updateSliderFromMouse(HitBox box, double mx, SimulationState state) {
        float t = (float) Math.max(0.0, Math.min(1.0, (mx - (box.x + 4f)) / (box.w - 8f)));
        float v = box.min + t * (box.max - box.min);
        switch (box.id) {
            case SLIDER_SPIN -> {
                state.spinParam = v;
                state.kerrSpinEnabled = v > 0.005f;
            }
            case SLIDER_TEMP -> state.diskTempScale = v;
            case SLIDER_DENSITY -> state.diskDensity = v;
            case SLIDER_JET_POWER -> {
                state.jetPower = v;
                state.showJets = v > 0.005f;
            }
            case SLIDER_BLOOM -> state.bloomStrength = v;
            case SLIDER_EXPOSURE -> state.exposure = v;
            case SLIDER_FOV -> state.fovDeg = v;
            default -> {
            }
        }
    }

    private void addHitBox(float x, float y, float w, float h, int id, float min, float max) {
        if (hitBoxCount < MAX_HITBOXES) {
            hitBoxes[hitBoxCount++].set(x, y, w, h, id, min, max);
        }
    }

    // Zero-allocation fixed-point decimal formatters
    private static void appendFixed1(StringBuilder out, float val) {
        if (val < 0f) {
            out.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 10f);
        out.append(scaled / 10).append('.').append(scaled % 10);
    }

    private static void appendFixed2(StringBuilder out, float val) {
        if (val < 0f) {
            out.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 100f);
        int frac = scaled % 100;
        out.append(scaled / 100).append('.');
        if (frac < 10) {
            out.append('0');
        }
        out.append(frac);
    }

    private static void appendFixed3(StringBuilder out, float val) {
        if (val < 0f) {
            out.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 1000f);
        int frac = scaled % 1000;
        out.append(scaled / 1000).append('.');
        if (frac < 100) {
            out.append('0');
        }
        if (frac < 10) {
            out.append('0');
        }
        out.append(frac);
    }
}
