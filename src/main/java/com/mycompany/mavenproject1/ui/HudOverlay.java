package com.mycompany.mavenproject1.ui;

import com.mycompany.mavenproject1.core.CameraController;
import com.mycompany.mavenproject1.core.SimulationState;
import com.mycompany.mavenproject1.telemetry.HardwareTelemetry;

/**
 * Interactive glassmorphic Hardware Profiler (Left Panel), live Doran Plunge
 * Telemetry Banner (Top-Center during dives), and collapsible Spacetime & Optics
 * Console (Right Panel) with 250ms cached telemetry formatting, responsive resize
 * guards, and zero heap allocations per frame.
 */
public final class HudOverlay {

    // Toggle & Action IDs
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
    public static final int ACT_WORMHOLE = 13;
    public static final int ACT_SHRED_STAR = 14;
    public static final int ACT_AUDIO = 15;
    public static final int ACT_FLY_THROUGH = 16;
    public static final int ACT_PLUNGE = 17;
    public static final int ACT_TOGGLE_PANEL = 100;

    // Slider IDs
    public static final int SLIDER_SPIN = 201;
    public static final int SLIDER_TEMP = 202;
    public static final int SLIDER_DENSITY = 203;
    public static final int SLIDER_JET_POWER = 204;
    public static final int SLIDER_BLOOM = 205;
    public static final int SLIDER_EXPOSURE = 206;
    public static final int SLIDER_FOV = 207;
    public static final int SLIDER_VOLUME = 208;
    public static final int SLIDER_WH_RHO = 209;
    public static final int SLIDER_WH_A = 210;
    public static final int SLIDER_WH_M = 211;
    public static final int SLIDER_PLUNGE_SPEED = 212;

    // Preset IDs
    public static final int PRESET_EDGE = 301;
    public static final int PRESET_POLE = 302;
    public static final int PRESET_RING = 303;
    public static final int PRESET_DIVE = 304;
    public static final int PRESET_RESET = 305;
    public static final int PRESET_UNIV_A = 306;
    public static final int PRESET_THROAT = 307;
    public static final int PRESET_UNIV_B = 308;

    private static final int MAX_HITBOXES = 88;

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

    // Reusable scratch StringBuilder for dynamic live values (zero heap allocs)
    private final StringBuilder sb = new StringBuilder(128);

    // 250ms Cached Hardware Telemetry StringBuilders (avoid re-formatting every frame at 144+ FPS)
    private final StringBuilder cachedFpsLine = new StringBuilder(64);
    private final StringBuilder cachedGpuNameLine = new StringBuilder(48);
    private final StringBuilder cachedGpuLoadLine = new StringBuilder(64);
    private final StringBuilder cachedPassesLine = new StringBuilder(64);
    private final StringBuilder cachedVramLine = new StringBuilder(64);
    private final StringBuilder cachedCpuSubmitLine = new StringBuilder(48);
    private final StringBuilder cachedCpuPctLine = new StringBuilder(48);
    private final StringBuilder cachedHeapLine = new StringBuilder(48);
    private final StringBuilder cachedTdeLine = new StringBuilder(48);
    private float cachedGpuLoadPct = 0f;
    private float cachedVramPct = 0f;
    private float cachedProcCpuPct = 0f;
    private boolean cachePrimed = false;

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

    /** Rebuilds cached hardware profiler lines once per 250ms telemetry tick (zero allocations). */
    public void refreshTelemetryCache(int windowW, int windowH, SimulationState state, HardwareTelemetry telemetry) {
        cachedFpsLine.setLength(0);
        cachedFpsLine.append("FPS: ");
        appendFixed1(cachedFpsLine, telemetry.currentFps);
        cachedFpsLine.append("  FRAME: ");
        appendFixed2(cachedFpsLine, telemetry.wallFrameMs);
        cachedFpsLine.append("ms (");
        cachedFpsLine.append(windowW).append('x').append(windowH).append(')');

        cachedGpuNameLine.setLength(0);
        cachedGpuNameLine.append("GPU: ").append(telemetry.gpuShortName);

        cachedGpuLoadPct = telemetry.getGpuLoadPercent(state.vsync);
        cachedGpuLoadLine.setLength(0);
        cachedGpuLoadLine.append("GPU LOAD: ");
        appendFixed1(cachedGpuLoadLine, cachedGpuLoadPct);
        cachedGpuLoadLine.append("%   SHADER: ");
        appendFixed2(cachedGpuLoadLine, telemetry.gpuTotalMs);
        cachedGpuLoadLine.append(" ms");

        cachedPassesLine.setLength(0);
        cachedPassesLine.append("RAY:");
        appendFixed2(cachedPassesLine, telemetry.gpuRayMs);
        cachedPassesLine.append(" TDE:");
        appendFixed2(cachedPassesLine, telemetry.gpuTdeMs);
        cachedPassesLine.append(" BLM:");
        appendFixed2(cachedPassesLine, telemetry.gpuBloomMs);
        cachedPassesLine.append(" UI:");
        appendFixed2(cachedPassesLine, telemetry.gpuUiMs);

        cachedVramLine.setLength(0);
        if (telemetry.vramTotalMb > 0) {
            cachedVramPct = Math.min(1f, (float) telemetry.vramUsedMb / telemetry.vramTotalMb);
            cachedVramLine.append("VRAM: ").append(telemetry.vramUsedMb).append('/').append(telemetry.vramTotalMb).append("MB");
            if (telemetry.gpuTempC > 0) {
                cachedVramLine.append(" | ").append(telemetry.gpuTempC).append("°C ");
                cachedVramLine.append(telemetry.gpuClockMhz).append("MHz");
            }
        } else {
            cachedVramPct = 0f;
        }

        cachedCpuSubmitLine.setLength(0);
        cachedCpuSubmitLine.append("CPU (").append(telemetry.cpuCores).append("C)  SUBMIT: ");
        appendFixed2(cachedCpuSubmitLine, telemetry.cpuSubmitMs);
        cachedCpuSubmitLine.append(" ms");

        cachedProcCpuPct = telemetry.processCpuPct;
        cachedCpuPctLine.setLength(0);
        cachedCpuPctLine.append("JVM CPU: ");
        appendFixed1(cachedCpuPctLine, telemetry.processCpuPct);
        cachedCpuPctLine.append("%   SYS CPU: ");
        appendFixed1(cachedCpuPctLine, telemetry.systemCpuPct);
        cachedCpuPctLine.append('%');

        cachedHeapLine.setLength(0);
        cachedHeapLine.append("JVM HEAP: ").append(telemetry.heapUsedMb).append(" / ").append(telemetry.heapMaxMb).append(" MB");

        cachedTdeLine.setLength(0);
        cachedTdeLine.append("TDE PARTICLES: ");
        if (telemetry.tdeActiveParticles > 0) {
            cachedTdeLine.append(telemetry.tdeActiveParticles).append(" ACTIVE");
        } else {
            cachedTdeLine.append("IDLE ([E] SHRED STAR)");
        }
        cachePrimed = true;
    }

    public void render(UiRenderer ui, int windowW, int windowH,
                       double mouseX, double mouseY, boolean refreshTelemetry,
                       SimulationState state, CameraController camera,
                       HardwareTelemetry telemetry) {
        hitBoxCount = 0;
        if (!cachePrimed || refreshTelemetry) {
            refreshTelemetryCache(windowW, windowH, state, telemetry);
        }

        ui.begin(windowW, windowH);

        renderLeftProfilerPanel(ui, windowW, windowH, state, camera, telemetry);
        if (state.plungeActive && windowW >= 1040) {
            renderTopPlungeBanner(ui, windowW, state, camera);
        }
        if (windowW >= 760) {
            renderRightControlPanel(ui, windowW, windowH, mouseX, mouseY, state, camera);
        }
        if (windowH >= 480) {
            renderBottomHintStrip(ui, windowW, windowH, state);
        }

        ui.end();
    }

    private void renderTopPlungeBanner(UiRenderer ui, int windowW,
                                       SimulationState state, CameraController camera) {
        float bw = Math.min(460f, windowW - 760f);
        if (bw < 310f) {
            return;
        }
        float bx = (windowW - bw) * 0.5f;
        float by = 16f;
        float bh = 96f;

        boolean danger = state.plungeZone >= SimulationState.ZONE_EVENT_HORIZON;
        ui.drawGlassPanel(bx, by, bw, bh, 10f,
                danger ? 0.14f : 0.07f,
                danger ? 0.07f : 0.10f,
                danger ? 0.16f : 0.16f, 0.86f,
                danger ? 0.98f : 0.42f,
                danger ? 0.48f : 0.85f,
                danger ? 0.92f : 1.00f, 0.55f);

        float cx = bx + 14f;
        float cy = by + 11f;

        // Zone Header
        sb.setLength(0);
        sb.append("PLUNGE ZONE: ").append(state.plungeZoneName());
        ui.drawText(sb, cx, cy, 1.18f,
                danger ? 1.0f : 0.45f,
                danger ? 0.68f : 0.92f,
                danger ? 0.96f : 1.0f, 1.0f);
        cy += 17f;
        ui.drawRect(cx, cy, bw - 28f, 1f, 0f, 1f, 1f, 1f, 0.14f);
        cy += 8f;

        // Proper time vs Distant coordinate time
        sb.setLength(0);
        sb.append("TAU: ");
        appendFixed2(sb, (float) state.plungeProperTime);
        sb.append("M   |   t_inf: ");
        if (state.plungeHorizonCrossed) {
            sb.append("INF (HORIZON CROSSED)");
        } else {
            appendFixed1(sb, (float) state.plungeCoordTime);
            sb.append('M');
        }
        ui.drawText(sb, cx, cy, 1.05f, 0.92f, 0.95f, 1.0f, 0.98f);
        cy += 15f;

        // Infall Velocity v/c & Tidal Gradient 2M/r^3
        sb.setLength(0);
        sb.append("v/c: ");
        appendFixed2(sb, state.plungeVelocityC);
        sb.append("c  |  TIDAL: ");
        if (state.plungeTidalGrad >= 100f) {
            sb.append(Math.round(state.plungeTidalGrad));
        } else if (state.plungeTidalGrad < 1.0f) {
            appendFixed3(sb, state.plungeTidalGrad);
        } else {
            appendFixed2(sb, state.plungeTidalGrad);
        }
        sb.append("  |  r: ");
        appendFixed2(sb, (float) camera.currentKerrRadius());
        sb.append('M');
        ui.drawText(sb, cx, cy, 1.04f, 0.98f, 0.80f, 0.46f, 0.98f);
        cy += 16f;

        String footer = state.plungeEnded
                ? (state.effectiveSpin() > 0.05f
                    ? "CAUCHY MASS-INFLATION  |  [P] REPLAY  |  [R] RESET"
                    : "SINGULARITY REACHED  |  [P] REPLAY  |  [R] RESET")
                : "DRAG: 360° FREE-LOOK  |  SCROLL: SPEED  |  [P] ABORT";
        ui.drawText(footer, cx, cy, 0.94f, 0.72f, 0.82f, 0.94f, 0.92f);
    }

    private void renderLeftProfilerPanel(UiRenderer ui, int windowW, int windowH,
                                         SimulationState state, CameraController camera,
                                         HardwareTelemetry telemetry) {
        float lx = 16f;
        float ly = 16f;
        float lw = Math.min(386f, windowW - 32f);
        float lh = Math.min(394f, windowH - 58f);

        ui.drawGlassPanel(lx, ly, lw, lh, 10f,
                0.07f, 0.09f, 0.14f, 0.84f,
                state.wormholeMode ? 0.36f : 0.42f,
                state.wormholeMode ? 0.90f : 0.68f,
                state.wormholeMode ? 0.85f : 0.96f, 0.38f);

        float cx = lx + 14f;
        float cy = ly + 13f;
        float innerW = lw - 28f;

        ui.drawText(state.wormholeMode
                        ? "WORMHOLE PROFILER & TELEMETRY"
                        : "KERR-SCHILD PROFILER & TELEMETRY",
                cx, cy, 1.26f,
                state.wormholeMode ? 0.42f : 0.95f,
                state.wormholeMode ? 0.95f : 0.78f,
                state.wormholeMode ? 0.88f : 0.48f, 1.0f);
        cy += 19f;
        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        cy += 9f;

        // Cached 250ms Telemetry Lines
        float fpsR = telemetry.currentFps >= 58f ? 0.35f : 0.98f;
        float fpsG = telemetry.currentFps >= 58f ? 0.95f : 0.45f;
        ui.drawText(cachedFpsLine, cx, cy, 1.15f, fpsR, fpsG, 0.55f, 1.0f);
        cy += 18f;

        ui.drawText(cachedGpuNameLine, cx, cy, 1.12f, 0.55f, 0.85f, 1.0f, 1.0f);
        cy += 15f;

        ui.drawText(cachedGpuLoadLine, cx, cy, 1.12f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 13f;
        ui.drawProgressBar(cx, cy, innerW, 6f, cachedGpuLoadPct / 100f, 0.25f, 0.82f, 1.0f);
        cy += 12f;

        ui.drawText(cachedPassesLine, cx, cy, 1.04f, 0.74f, 0.82f, 0.94f, 0.96f);
        cy += 14f;

        if (cachedVramLine.length() > 0) {
            ui.drawText(cachedVramLine, cx, cy, 1.08f, 0.72f, 0.78f, 0.88f, 0.95f);
            cy += 12f;
            ui.drawProgressBar(cx, cy, innerW, 5f, cachedVramPct, 0.65f, 0.55f, 1.0f);
            cy += 13f;
        } else {
            cy += 4f;
        }

        ui.drawText(cachedCpuSubmitLine, cx, cy, 1.12f, 0.45f, 0.95f, 0.72f, 1.0f);
        cy += 14f;

        ui.drawText(cachedCpuPctLine, cx, cy, 1.12f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 13f;
        ui.drawProgressBar(cx, cy, innerW, 6f, cachedProcCpuPct / 100f, 0.32f, 0.92f, 0.65f);
        cy += 12f;

        ui.drawText(cachedHeapLine, cx, cy, 1.08f, 0.72f, 0.78f, 0.88f, 0.95f);
        cy += 14f;

        // Live Spacetime Metric & TDE / Wormhole Telemetry Strip
        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        cy += 7f;

        if (state.wormholeMode) {
            float lVal = (float) camera.currentWormholeL();
            float throatBand = state.wormholeA + 0.65f;
            boolean inThroat = Math.abs(lVal) <= throatBand;
            boolean inUnivB = lVal < -throatBand;

            ui.drawText("METRIC: MORRIS-THORNE (DNEG)", cx, cy, 1.06f, 0.42f, 0.95f, 0.88f, 0.96f);
            cy += 13f;

            sb.setLength(0);
            sb.append("l=");
            if (lVal >= 0f) sb.append('+');
            appendFixed2(sb, lVal);
            sb.append("  ZONE: ");
            sb.append(inThroat ? "THROAT" : (inUnivB ? "UNIVERSE B" : "UNIVERSE A"));
            ui.drawText(sb, cx, cy, 1.08f,
                    inThroat ? 1.0f : (inUnivB ? 0.98f : 0.60f),
                    inThroat ? 0.82f : (inUnivB ? 0.74f : 0.86f),
                    inThroat ? 0.38f : (inUnivB ? 0.36f : 1.0f), 0.98f);
            cy += 13f;

            sb.setLength(0);
            sb.append("rho=");
            appendFixed2(sb, state.wormholeRho);
            sb.append("  a=");
            appendFixed2(sb, state.wormholeA);
            sb.append("  Mw=");
            appendFixed2(sb, state.wormholeM);
            ui.drawText(sb, cx, cy, 1.04f, 0.78f, 0.86f, 0.94f, 0.92f);
            cy += 16f;
        } else {
            sb.setLength(0);
            sb.append("r=");
            appendFixed2(sb, (float) camera.currentKerrRadius());
            sb.append("M  r+=");
            appendFixed2(sb, (float) state.outerHorizonRadius());
            sb.append("M  ISCO=");
            appendFixed2(sb, (float) state.progradeIscoRadius());
            sb.append('M');
            ui.drawText(sb, cx, cy, 1.06f, 0.96f, 0.82f, 0.56f, 0.96f);
            cy += 13f;

            sb.setLength(0);
            sb.append("FRAME: ");
            if (state.plungeActive) {
                sb.append("PLUNGE (v=");
                appendFixed2(sb, state.plungeVelocityC);
                sb.append("c)");
            } else {
                sb.append(state.freeFallMode ? "FREE-FALL (DORAN)" : "STATIC SHELL TETRAD");
            }
            boolean insideHorizon = camera.currentKerrRadius() <= state.outerHorizonRadius();
            if (insideHorizon) {
                sb.append(" [IN r+]");
            }
            ui.drawText(sb, cx, cy, 1.04f,
                    insideHorizon ? 1.0f : 0.62f,
                    insideHorizon ? 0.45f : 0.86f,
                    insideHorizon ? 0.52f : 1.0f, 0.95f);
            cy += 13f;

            ui.drawText(cachedTdeLine, cx, cy, 1.04f,
                    telemetry.tdeActiveParticles > 0 ? 1.0f : 0.68f,
                    telemetry.tdeActiveParticles > 0 ? 0.76f : 0.76f,
                    telemetry.tdeActiveParticles > 0 ? 0.36f : 0.86f, 0.96f);
            cy += 16f;
        }

        // Rolling 120-Frame GPU vs CPU Oscilloscope Graph (guarded against short windows)
        float gh = Math.min(72f, (ly + lh - 10f) - cy);
        if (gh >= 36f) {
            float gx = cx;
            float gy = cy;
            float gw = innerW;
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

            float line60Y = baseLineY - (16.67f / maxScaleMs) * plotH;
            if (line60Y > gy + 15f && line60Y < baseLineY) {
                ui.drawRect(gx + 4f, line60Y, gw - 8f, 1f, 0f, 1.0f, 0.65f, 0.25f, 0.42f);
            }
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
    }

    private void renderRightControlPanel(UiRenderer ui, int windowW, int windowH,
                                         double mouseX, double mouseY,
                                         SimulationState state, CameraController camera) {
        float rw = 340f;
        float rx = windowW - rw - 16f;
        float ry = 16f;
        boolean expanded = state.controlsExpanded && windowH >= 675;
        float rh;
        if (!expanded) {
            rh = 42f;
        } else if (state.wormholeMode) {
            rh = 468f;
        } else {
            rh = 640f;
        }

        ui.drawGlassPanel(rx, ry, rw, rh, 10f,
                0.07f, 0.09f, 0.14f, 0.84f,
                state.wormholeMode ? 0.36f : (state.plungeActive ? 0.96f : 0.95f),
                state.wormholeMode ? 0.90f : (state.plungeActive ? 0.45f : 0.72f),
                state.wormholeMode ? 0.85f : (state.plungeActive ? 0.92f : 0.42f), 0.40f);

        float px = rx + 14f;
        float py = ry + 13f;
        float innerW = rw - 28f;

        ui.drawText("SPACETIME & OPTICS CONSOLE", px, py, 1.26f, 0.95f, 0.78f, 0.48f, 1.0f);

        float btnW = 24f;
        float btnH = 20f;
        float btnX = rx + rw - btnW - 10f;
        float btnY = ry + 10f;
        ui.drawGlassPanel(btnX, btnY, btnW, btnH, 4f, 1f, 1f, 1f, 0.10f, 1f, 1f, 1f, 0.25f);
        ui.drawText(expanded ? "-" : "+", btnX + 8f, btnY + 5f, 1.25f, 1f, 1f, 1f, 0.92f);
        addHitBox(btnX, btnY, btnW, btnH, ACT_TOGGLE_PANEL, 0f, 0f);

        if (!expanded) {
            return;
        }

        py += 19f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        py += 8f;

        float colW = (innerW - 6f) * 0.5f;
        float rowH = 21f;

        if (state.wormholeMode) {
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[W] WORMHOLE", true, ACT_WORMHOLE,
                    px + colW + 6f, "[FLY THROUGH]", camera.isFlyingThrough(), ACT_FLY_THROUGH);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[M] 4X ADPT AA", state.adaptiveAa4x, ACT_ADAPTIVE_AA,
                    px + colW + 6f, "[C] SKY GRID", state.showGrid, ACT_SKY_GRID);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[O] AUTO ORBIT", state.autoOrbit, ACT_AUTO_ORBIT,
                    px + colW + 6f, "[A] AUDIO", state.audioEnabled, ACT_AUDIO);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[V] V-SYNC", state.vsync, ACT_VSYNC,
                    px + colW + 6f, "[SPC] PAUSE", state.paused, ACT_PAUSE);

            py += 4f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 8f;

            sb.setLength(0);
            appendFixed2(sb, state.wormholeRho);
            py = drawSlider(ui, px, py, innerW, "THROAT RADIUS (rho)", state.wormholeRho,
                    SimulationState.MIN_WORMHOLE_RHO, SimulationState.MAX_WORMHOLE_RHO, sb, SLIDER_WH_RHO);

            sb.setLength(0);
            appendFixed2(sb, state.wormholeA);
            py = drawSlider(ui, px, py, innerW, "THROAT LENGTH (a)", state.wormholeA,
                    SimulationState.MIN_WORMHOLE_A, SimulationState.MAX_WORMHOLE_A, sb, SLIDER_WH_A);

            sb.setLength(0);
            appendFixed2(sb, state.wormholeM);
            py = drawSlider(ui, px, py, innerW, "LENSING WIDTH (Mw)", state.wormholeM,
                    SimulationState.MIN_WORMHOLE_M, SimulationState.MAX_WORMHOLE_M, sb, SLIDER_WH_M);

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

            sb.setLength(0);
            sb.append(Math.round(state.audioVolume * 100f)).append('%');
            py = drawSlider(ui, px, py, innerW, "AUDIO VOLUME", state.audioVolume,
                    SimulationState.MIN_VOLUME, SimulationState.MAX_VOLUME, sb, SLIDER_VOLUME);

            py += 4f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 8f;

            ui.drawText("WORMHOLE PRESETS:", px, py, 1.06f, 0.72f, 0.78f, 0.88f, 0.95f);
            py += 14f;
            float row1W = (innerW - 12f) / 3f;
            drawPresetButton(ui, mouseX, mouseY, px, py, row1W, 22f, "[1] UNIV A", PRESET_UNIV_A);
            drawPresetButton(ui, mouseX, mouseY, px + (row1W + 6f), py, row1W, 22f, "[2] THROAT", PRESET_THROAT);
            drawPresetButton(ui, mouseX, mouseY, px + (row1W + 6f) * 2f, py, row1W, 22f, "[3] UNIV B", PRESET_UNIV_B);
            py += 27f;

            float row2W = (innerW - 6f) * 0.5f;
            drawPresetButton(ui, mouseX, mouseY, px, py, row2W, 22f, "[4] FLY THRU", ACT_FLY_THROUGH);
            drawPresetButton(ui, mouseX, mouseY, px + row2W + 6f, py, row2W, 22f, "[R] RESET ALL", PRESET_RESET);
        } else {
            // Featured Action Row: Plunge Dive Simulator + Star TDE + Wormhole
            String diveLabel = state.plungeActive
                    ? (state.plungeEnded ? "[P] REPLAY" : "[P] ABORT")
                    : "[P] DIVE IN";
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    diveLabel, state.plungeActive, ACT_PLUNGE,
                    px + colW + 6f, "[E] SHRED STAR", false, ACT_SHRED_STAR);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[W] WORMHOLE", false, ACT_WORMHOLE,
                    px + colW + 6f, "[A] AUDIO", state.audioEnabled, ACT_AUDIO);
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

            py += 3f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 7f;

            // 9 Compact Real-Time Sliders (including Plunge Speed & Audio Volume)
            sb.setLength(0);
            appendFixed3(sb, state.spinParam);
            py = drawSlider(ui, px, py, innerW, "KERR SPIN (a*)", state.spinParam,
                    SimulationState.MIN_SPIN, SimulationState.MAX_SPIN, sb, SLIDER_SPIN);

            sb.setLength(0);
            appendFixed2(sb, state.plungeSpeed);
            sb.append('x');
            py = drawSlider(ui, px, py, innerW, "PLUNGE SPEED", state.plungeSpeed,
                    SimulationState.MIN_PLUNGE_SPEED, SimulationState.MAX_PLUNGE_SPEED, sb, SLIDER_PLUNGE_SPEED);

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

            sb.setLength(0);
            sb.append(Math.round(state.audioVolume * 100f)).append('%');
            py = drawSlider(ui, px, py, innerW, "AUDIO VOLUME", state.audioVolume,
                    SimulationState.MIN_VOLUME, SimulationState.MAX_VOLUME, sb, SLIDER_VOLUME);

            py += 3f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 7f;

            float rowW = (innerW - 12f) / 3f;
            drawPresetButton(ui, mouseX, mouseY, px, py, rowW, 21f, "[1] EDGE", PRESET_EDGE);
            drawPresetButton(ui, mouseX, mouseY, px + (rowW + 6f), py, rowW, 21f, "[2] POLAR", PRESET_POLE);
            drawPresetButton(ui, mouseX, mouseY, px + (rowW + 6f) * 2f, py, rowW, 21f, "[3] RING", PRESET_RING);
            py += 25f;

            drawPresetButton(ui, mouseX, mouseY, px, py, rowW, 21f, "[4] DIVE", PRESET_DIVE);
            drawTogglePill(ui, mouseX, mouseY, px + (rowW + 6f), py, rowW, 21f, "[V] V-SYNC", state.vsync, ACT_VSYNC);
            drawPresetButton(ui, mouseX, mouseY, px + (rowW + 6f) * 2f, py, rowW, 21f, "[R] RESET", PRESET_RESET);
        }
    }

    private void renderBottomHintStrip(UiRenderer ui, int windowW, int windowH, SimulationState state) {
        String hint;
        if (state.wormholeMode) {
            hint = "[W] BLACK HOLE | UP/DN OR SCROLL: FLY THRU THROAT | DRAG: ORBIT | [A] AUDIO | [H] HUD";
        } else if (state.plungeActive) {
            hint = "[P] ABORT/REPLAY DIVE | DRAG: 360 FREE-LOOK | SCROLL: PLUNGE SPEED | [R] RESET | [H] HUD";
        } else {
            hint = "[P] PLUNGE DIVE | [W] WORMHOLE | [E] SHRED STAR | [A] AUDIO | [SPC] PAUSE | [H] HUD | [ESC] QUIT";
        }
        float scale = windowW < 920 ? 0.85f : 0.98f;
        float hintW = Math.min(windowW - 24f, hint.length() * 8f * scale + 20f);
        float hx = (windowW - hintW) * 0.5f;
        float hy = windowH - 30f;
        ui.drawGlassPanel(hx, hy, hintW, 21f, 6f, 0.06f, 0.08f, 0.12f, 0.76f, 1f, 1f, 1f, 0.16f);
        ui.drawText(hint, hx + 10f, hy + 6f, scale, 0.76f, 0.82f, 0.92f, 0.88f);
    }

    private float drawToggleRow(UiRenderer ui, double mouseX, double mouseY,
                                float x1, float y, float w, float h,
                                String label1, boolean state1, int id1,
                                float x2, String label2, boolean state2, int id2) {
        drawTogglePill(ui, mouseX, mouseY, x1, y, w, h, label1, state1, id1);
        drawTogglePill(ui, mouseX, mouseY, x2, y, w, h, label2, state2, id2);
        return y + h + 4f;
    }

    private void drawTogglePill(UiRenderer ui, double mouseX, double mouseY,
                                float x, float y, float w, float h,
                                String label, boolean active, int id) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        boolean plungeAction = (id == ACT_PLUNGE);
        boolean accentAction = (id == ACT_SHRED_STAR || id == ACT_FLY_THROUGH);

        float bgR, bgG, bgB, bdR, bdG, bdB;
        if (plungeAction) {
            bgR = active ? 0.52f : (hovered ? 0.42f : 0.28f);
            bgG = active ? 0.18f : (hovered ? 0.20f : 0.15f);
            bgB = active ? 0.56f : (hovered ? 0.48f : 0.36f);
            bdR = 0.96f; bdG = 0.52f; bdB = 1.00f;
        } else if (accentAction) {
            bgR = hovered ? 0.44f : 0.30f;
            bgG = hovered ? 0.28f : 0.20f;
            bgB = hovered ? 0.14f : 0.12f;
            bdR = 1.00f; bdG = 0.72f; bdB = 0.34f;
        } else {
            bgR = active ? 0.16f : 0.11f;
            bgG = active ? 0.35f : 0.13f;
            bgB = active ? 0.50f : 0.18f;
            bdR = active ? 0.35f : 0.55f;
            bdG = active ? 0.84f : 0.60f;
            bdB = active ? 1.00f : 0.70f;
        }
        float bgA = hovered ? 0.92f : 0.78f;
        float bdA = (active || accentAction || plungeAction) ? 0.80f : 0.22f;

        ui.drawGlassPanel(x, y, w, h, 5f, bgR, bgG, bgB, bgA, bdR, bdG, bdB, bdA);
        ui.drawText(label, x + 7f, y + 6.5f, 1.02f,
                (active || accentAction || plungeAction) ? 0.98f : 0.68f,
                (active || accentAction || plungeAction) ? 0.98f : 0.72f,
                (active || accentAction || plungeAction) ? 1.00f : 0.80f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private float drawSlider(UiRenderer ui, float x, float y, float w,
                             String label, float val, float min, float max,
                             CharSequence valText, int id) {
        ui.drawText(label, x, y, 1.02f, 0.78f, 0.84f, 0.92f, 0.95f);
        float valW = valText.length() * 8f * 1.02f;
        ui.drawText(valText, x + w - valW, y, 1.02f, 0.95f, 0.78f, 0.48f, 1.0f);

        float trackY = y + 12f;
        float trackH = 5f;
        float norm = Math.max(0f, Math.min(1f, (val - min) / (max - min)));

        ui.drawRect(x, trackY, w, trackH, 2.5f, 1f, 1f, 1f, 0.14f);
        ui.drawRect(x, trackY, Math.max(4f, w * norm), trackH, 2.5f, 0.95f, 0.68f, 0.32f, 0.92f);

        float thumbX = x + w * norm - 4.5f;
        ui.drawRect(thumbX, trackY - 3f, 9f, 11f, 4f, 1f, 1f, 1f, 0.98f);

        addHitBox(x - 4f, y, w + 8f, 22f, id, min, max);
        return y + 25.5f;
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
        float textW = label.length() * 8f * 0.98f;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 6.5f, 0.98f, 0.95f, 0.95f, 0.98f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

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
            case SLIDER_PLUNGE_SPEED -> state.plungeSpeed = v;
            case SLIDER_TEMP -> state.diskTempScale = v;
            case SLIDER_DENSITY -> state.diskDensity = v;
            case SLIDER_JET_POWER -> {
                state.jetPower = v;
                state.showJets = v > 0.005f;
            }
            case SLIDER_BLOOM -> state.bloomStrength = v;
            case SLIDER_EXPOSURE -> state.exposure = v;
            case SLIDER_FOV -> state.fovDeg = v;
            case SLIDER_VOLUME -> {
                state.audioVolume = v;
                if (v > 0.01f) {
                    state.audioEnabled = true;
                }
            }
            case SLIDER_WH_RHO -> state.wormholeRho = v;
            case SLIDER_WH_A -> state.wormholeA = v;
            case SLIDER_WH_M -> state.wormholeM = v;
            default -> {
            }
        }
    }

    private void addHitBox(float x, float y, float w, float h, int id, float min, float max) {
        if (hitBoxCount < MAX_HITBOXES) {
            hitBoxes[hitBoxCount++].set(x, y, w, h, id, min, max);
        }
    }

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
