package io.github.laithabuzaid.blackhole.ui;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;

/**
 * Next-generation modular glassmorphic Observatory HUD:
 * <ul>
 *   <li><b>3-State Visibility Cycle ({@code [H]}):</b> {@code CLEAN} (cinema mode) →
 *       {@code COMPACT} (top-left status pill + bottom timelapse dock) →
 *       {@code FULL} (left hardware/physics profiler + 4-tab console + bottom timelapse dock).</li>
 *   <li><b>Bottom-Center Timelapse Transport Bar:</b> clickable {@code [<<]}, {@code [PLAY/PAUSE]},
 *       {@code [>>]}, color-coded speed badge, and quick speed chips ({@code 0.25x, 1x, 5x, 15x}).</li>
 *   <li><b>4-Tab Right Observatory Console:</b> {@code SCENE | PHYSICS | TDE LAB | OPTICS} with
 *       active LED indicators, smooth hover/press shading, and a zero-allocation 1-line hover tooltip bar.</li>
 * </ul>
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
    public static final int ACT_ATTRACT_TOUR = 18;
    public static final int ACT_ANAMORPHIC = 19;
    public static final int ACT_BINARY = 20;
    public static final int ACT_BINARY_REPLAY = 21;
    public static final int ACT_BINARY_GW_OVERLAY = 22;
    public static final int ACT_BINARY_AUTOLOOP = 23;

    // Global Timelapse Transport IDs
    public static final int ACT_TIME_SLOWER = 30;
    public static final int ACT_TIME_FASTER = 31;
    public static final int ACT_TIME_RESET = 32;
    public static final int ACT_TIME_025 = 33;
    public static final int ACT_TIME_1 = 34;
    public static final int ACT_TIME_5 = 35;
    public static final int ACT_TIME_15 = 36;

    // Right Console Tab IDs
    public static final int ACT_TAB_SCENE = 40;
    public static final int ACT_TAB_PHYSICS = 41;
    public static final int ACT_TAB_TDE_LAB = 42;
    public static final int ACT_TAB_OPTICS = 43;

    // TDE Lab & Mode Switch Action IDs
    public static final int ACT_TDE_ARCH_0 = 50;
    public static final int ACT_TDE_ARCH_1 = 51;
    public static final int ACT_TDE_ARCH_2 = 52;
    public static final int ACT_TDE_ARCH_3 = 53;
    public static final int ACT_TDE_CYCLE_ARCH = 54;
    public static final int ACT_TDE_CLEAR = 55;
    public static final int ACT_MODE_KERR = 56;

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
    public static final int SLIDER_ANAMORPHIC = 213;
    public static final int SLIDER_BIN_MASS_RATIO = 214;
    public static final int SLIDER_TIME_SCALE = 216;
    public static final int SLIDER_TDE_BETA = 217;
    public static final int SLIDER_TDE_INC = 218;

    // Preset IDs
    public static final int PRESET_EDGE = 301;
    public static final int PRESET_POLE = 302;
    public static final int PRESET_RING = 303;
    public static final int PRESET_DIVE = 304;
    public static final int PRESET_RESET = 305;
    public static final int PRESET_UNIV_A = 306;
    public static final int PRESET_THROAT = 307;
    public static final int PRESET_UNIV_B = 308;
    public static final int PRESET_BIN_OBLIQUE = 309;
    public static final int PRESET_BIN_TOP = 310;
    public static final int PRESET_BIN_CLOSE = 311;

    private static final int MAX_HITBOXES = 96;
    private static final double LOG_TIME_RATIO = Math.log(SimulationState.MAX_TIME_SCALE / SimulationState.MIN_TIME_SCALE);

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
    private int hoveredId = 0;
    private int pressedId = 0;

    // Reusable scratch StringBuilders (zero heap allocs per frame)
    private final StringBuilder sb = new StringBuilder(128);
    private final StringBuilder sb2 = new StringBuilder(96);
    private final StringBuilder toastScratch = new StringBuilder(96);

    // 250ms Cached Hardware Telemetry StringBuilders
    private final StringBuilder cachedFpsLine = new StringBuilder(64);
    private final StringBuilder cachedCompactLine = new StringBuilder(96);
    private final StringBuilder cachedGpuNameLine = new StringBuilder(48);
    private final StringBuilder cachedGpuLoadLine = new StringBuilder(64);
    private final StringBuilder cachedPassesLine = new StringBuilder(64);
    private final StringBuilder cachedVramLine = new StringBuilder(64);
    private final StringBuilder cachedCpuSubmitLine = new StringBuilder(48);
    private final StringBuilder cachedCpuPctLine = new StringBuilder(48);
    private final StringBuilder cachedHeapLine = new StringBuilder(48);
    private final StringBuilder cachedTdeLine1 = new StringBuilder(64);
    private final StringBuilder cachedTdeLine2 = new StringBuilder(64);
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
        pressedId = 0;
    }

    /** Rebuilds cached hardware & TDE profiler strings once per 250ms telemetry tick (zero allocations). */
    public void refreshTelemetryCache(int windowW, int windowH, SimulationState state, HardwareTelemetry telemetry) {
        cachedFpsLine.setLength(0);
        cachedFpsLine.append("FPS: ");
        appendFixed1(cachedFpsLine, telemetry.currentFps);
        cachedFpsLine.append("  FRAME: ");
        appendFixed2(cachedFpsLine, telemetry.wallFrameMs);
        cachedFpsLine.append("ms (").append(windowW).append('x').append(windowH).append(')');

        cachedCompactLine.setLength(0);
        appendFixed1(cachedCompactLine, telemetry.currentFps);
        cachedCompactLine.append(" FPS  \u2022  GPU ");
        appendFixed2(cachedCompactLine, telemetry.gpuTotalMs);
        cachedCompactLine.append("ms  \u2022  ");
        if (state.binaryMode) {
            cachedCompactLine.append("BINARY MERGER");
        } else if (state.wormholeMode) {
            cachedCompactLine.append("WORMHOLE");
        } else if (state.plungeActive) {
            cachedCompactLine.append("HORIZON PLUNGE");
        } else {
            cachedCompactLine.append("KERR BH (a*=");
            appendFixed2(cachedCompactLine, state.effectiveSpin());
            cachedCompactLine.append(')');
        }

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

        cachedTdeLine1.setLength(0);
        cachedTdeLine2.setLength(0);
        if (telemetry.tdeActiveParticles > 0 || telemetry.tdeSwallowedParticles > 0) {
            cachedTdeLine1.append("TDE: ").append(state.currentTdeArchetype().shortLabel)
                    .append("  ACT:").append(telemetry.tdeActiveParticles);
            cachedTdeLine2.append("BND:").append(telemetry.tdeBoundParticles)
                    .append("  TAIL:").append(telemetry.tdeUnboundParticles)
                    .append("  SWL:").append(telemetry.tdeSwallowedParticles);
        } else {
            cachedTdeLine1.append("TDE LAB: IDLE ([E] LAUNCH STAR)");
        }
        cachePrimed = true;
    }

    public void render(UiRenderer ui, int windowW, int windowH,
                       double mouseX, double mouseY, boolean refreshTelemetry,
                       boolean suppressOverlays,
                       SimulationState state, CameraController camera,
                       HardwareTelemetry telemetry) {
        render(ui, windowW, windowH, mouseX, mouseY, refreshTelemetry, suppressOverlays, state, camera, null, telemetry);
    }

    public void render(UiRenderer ui, int windowW, int windowH,
                       double mouseX, double mouseY, boolean refreshTelemetry,
                       boolean suppressOverlays,
                       SimulationState state, CameraController camera,
                       BinaryMergerPhysics binary,
                       HardwareTelemetry telemetry) {
        hitBoxCount = 0;
        hoveredId = 0;
        if (suppressOverlays) {
            return;
        }
        if (state.hudMode != SimulationState.HUD_CLEAN && (!cachePrimed || refreshTelemetry)) {
            refreshTelemetryCache(windowW, windowH, state, telemetry);
        }

        ui.begin(windowW, windowH);

        boolean plungeBannerDrawn = false;
        if (state.hudMode == SimulationState.HUD_FULL) {
            renderLeftProfilerPanel(ui, windowW, windowH, state, camera, binary, telemetry);
            if (state.plungeActive && windowW >= 1040) {
                renderTopPlungeBanner(ui, windowW, state, camera);
                plungeBannerDrawn = true;
            }
            if (windowW >= 780) {
                renderRightControlPanel(ui, windowW, windowH, mouseX, mouseY, state, camera, telemetry);
            }
            if (windowH >= 420) {
                renderTimelapseTransportBar(ui, windowW, windowH, mouseX, mouseY, state);
            }
        } else if (state.hudMode == SimulationState.HUD_COMPACT) {
            renderCompactStatusBadge(ui, windowW, state);
            if (state.plungeActive && windowW >= 680) {
                renderTopPlungeBanner(ui, windowW, state, camera);
                plungeBannerDrawn = true;
            }
            if (windowH >= 360) {
                renderTimelapseTransportBar(ui, windowW, windowH, mouseX, mouseY, state);
            }
        } else {
            // CLEAN (Cinema-First) Mode: plunge banner during active dive + auto-fading bottom pill
            if (state.plungeActive && windowW >= 640) {
                renderTopPlungeBanner(ui, windowW, state, camera);
                plungeBannerDrawn = true;
            }
            if (windowH >= 360) {
                renderCinemaBottomPill(ui, windowW, windowH, mouseY, state);
            }
        }

        renderToastPill(ui, windowW, state, plungeBannerDrawn ? 120f : 18f);
        ui.end();
    }

    private void renderCompactStatusBadge(UiRenderer ui, int windowW, SimulationState state) {
        float scale = windowW < 1080 ? 0.96f : 1.04f;
        float textW = cachedCompactLine.length() * 8f * scale;
        float pw = Math.min(windowW - 32f, textW + 38f);
        float ph = 28f;
        float px = 16f;
        float py = 16f;

        float accR = modeAccentR(state);
        float accG = modeAccentG(state);
        float accB = modeAccentB(state);

        ui.drawGradientGlassPanel(px, py, pw, ph, 14f,
                0.06f, 0.08f, 0.13f, 0.84f,
                accR, accG, accB, 0.44f);
        ui.drawIndicatorDot(px + 13f, py + ph * 0.5f, 3.6f, accR, accG, accB, 0.98f);
        ui.drawText(cachedCompactLine, px + 24f, py + 9.5f, scale, 0.92f, 0.95f, 0.99f, 0.98f);
    }

    private void renderTimelapseTransportBar(UiRenderer ui, int windowW, int windowH,
                                             double mouseX, double mouseY, SimulationState state) {
        float barW = Math.min(windowW - 28f, 548f);
        float barH = 34f;
        float bx = (windowW - barW) * 0.5f;
        float by = windowH - barH - 12f;

        boolean isFast = !state.paused && state.timeScale > 1.02f;
        boolean isSlow = !state.paused && state.timeScale < 0.98f;
        float bdR = state.paused ? 0.96f : (isFast ? 1.00f : (isSlow ? 0.34f : 0.62f));
        float bdG = state.paused ? 0.50f : (isFast ? 0.72f : (isSlow ? 0.88f : 0.74f));
        float bdB = state.paused ? 0.48f : (isFast ? 0.28f : (isSlow ? 1.00f : 0.90f));

        ui.drawGradientGlassPanel(bx, by, barW, barH, 17f,
                0.06f, 0.08f, 0.13f, 0.88f,
                bdR, bdG, bdB, 0.46f);

        float x = bx + 9f;
        float btnY = by + 5f;
        float btnH = 24f;

        // Transport cluster: [<<] [PLAY/PAUSE] [>>]
        drawTransportButton(ui, mouseX, mouseY, x, btnY, 32f, btnH, "<<", false, ACT_TIME_SLOWER);
        x += 36f;

        String playLabel = state.paused ? "PLAY" : "PAUSE";
        drawTransportButton(ui, mouseX, mouseY, x, btnY, 64f, btnH, playLabel, state.paused, ACT_PAUSE);
        x += 68f;

        drawTransportButton(ui, mouseX, mouseY, x, btnY, 32f, btnH, ">>", false, ACT_TIME_FASTER);
        x += 38f;

        ui.drawRect(x, by + 8f, 1f, barH - 16f, 0f, 1f, 1f, 1f, 0.14f);
        x += 8f;

        // Highlighted Multiplier Badge (click to snap back to 1.0x)
        sb.setLength(0);
        if (state.paused) {
            sb.append("PAUSED (");
            appendCompactScale(sb, state.timeScale);
            sb.append("x)");
        } else {
            appendCompactScale(sb, state.timeScale);
            sb.append(isFast ? "x TIMELAPSE" : (isSlow ? "x SLOW-MO" : "x REALTIME"));
        }

        float chipsTotalW = 48f + 36f + 36f + 42f + 12f; // 4 chips + 3x4px gaps
        float badgeW = Math.max(122f, (bx + barW - 10f) - x - chipsTotalW - 12f);
        boolean badgeHover = isOver(mouseX, mouseY, x, btnY, badgeW, btnH);
        if (badgeHover) hoveredId = ACT_TIME_RESET;

        float bgR = state.paused ? 0.30f : (isFast ? 0.34f : (isSlow ? 0.10f : 0.13f));
        float bgG = state.paused ? 0.14f : (isFast ? 0.22f : (isSlow ? 0.28f : 0.16f));
        float bgB = state.paused ? 0.16f : (isFast ? 0.08f : (isSlow ? 0.38f : 0.23f));
        if (badgeHover) {
            bgR += 0.07f; bgG += 0.07f; bgB += 0.08f;
        }
        ui.drawGlassPanel(x, btnY, badgeW, btnH, 12f,
                bgR, bgG, bgB, 0.86f,
                bdR, bdG, bdB, badgeHover ? 0.88f : 0.56f);
        float badgeScale = 0.96f;
        float badgeTextW = sb.length() * 8f * badgeScale;
        ui.drawText(sb, x + (badgeW - badgeTextW) * 0.5f, btnY + 8f, badgeScale,
                state.paused ? 1.00f : (isFast ? 1.00f : (isSlow ? 0.46f : 0.92f)),
                state.paused ? 0.72f : (isFast ? 0.84f : (isSlow ? 0.94f : 0.96f)),
                state.paused ? 0.68f : (isFast ? 0.42f : (isSlow ? 1.00f : 1.00f)), 1.0f);
        addHitBox(x, btnY, badgeW, btnH, ACT_TIME_RESET, 0f, 0f);
        x += badgeW + 6f;

        ui.drawRect(x, by + 8f, 1f, barH - 16f, 0f, 1f, 1f, 1f, 0.14f);
        x += 6f;

        // Quick-click speed chips: 0.25x | 1x | 5x | 15x
        drawSpeedChip(ui, mouseX, mouseY, x, btnY, 48f, btnH, "0.25x", Math.abs(state.timeScale - 0.25f) < 0.02f, false, ACT_TIME_025);
        x += 52f;
        drawSpeedChip(ui, mouseX, mouseY, x, btnY, 36f, btnH, "1x", Math.abs(state.timeScale - 1.00f) < 0.05f, false, ACT_TIME_1);
        x += 40f;
        drawSpeedChip(ui, mouseX, mouseY, x, btnY, 36f, btnH, "5x", Math.abs(state.timeScale - 5.00f) < 0.25f, true, ACT_TIME_5);
        x += 40f;
        drawSpeedChip(ui, mouseX, mouseY, x, btnY, 42f, btnH, "15x", Math.abs(state.timeScale - 15.0f) < 0.50f, true, ACT_TIME_15);
    }

    private void drawTransportButton(UiRenderer ui, double mouseX, double mouseY,
                                     float x, float y, float w, float h,
                                     String label, boolean active, int id) {
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;
        boolean pressed = (pressedId == id);
        float boost = pressed ? -0.04f : (hovered ? 0.09f : 0.0f);

        float bgR = (active ? 0.34f : 0.13f) + boost;
        float bgG = (active ? 0.18f : 0.16f) + boost;
        float bgB = (active ? 0.20f : 0.23f) + boost;
        ui.drawGlassPanel(x, y, w, h, 12f,
                bgR, bgG, bgB, hovered ? 0.94f : 0.80f,
                active ? 0.98f : 0.65f, active ? 0.62f : 0.76f, active ? 0.56f : 0.92f,
                hovered ? 0.78f : (active ? 0.68f : 0.26f));

        float scale = 0.96f;
        float textW = label.length() * 8f * scale;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 8f, scale, 0.94f, 0.96f, 1.0f, 0.98f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private void drawSpeedChip(UiRenderer ui, double mouseX, double mouseY,
                               float x, float y, float w, float h,
                               String label, boolean active, boolean warmAccent, int id) {
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;

        float bgR = active ? (warmAccent ? 0.38f : 0.14f) : (hovered ? 0.20f : 0.11f);
        float bgG = active ? (warmAccent ? 0.24f : 0.30f) : (hovered ? 0.24f : 0.14f);
        float bgB = active ? (warmAccent ? 0.10f : 0.44f) : (hovered ? 0.32f : 0.20f);
        float bdR = warmAccent ? 1.00f : 0.38f;
        float bdG = warmAccent ? 0.76f : 0.86f;
        float bdB = warmAccent ? 0.36f : 1.00f;

        ui.drawGlassPanel(x, y, w, h, 10f,
                bgR, bgG, bgB, active ? 0.92f : 0.76f,
                bdR, bdG, bdB, active ? 0.85f : (hovered ? 0.56f : 0.18f));
        float scale = 0.92f;
        float textW = label.length() * 8f * scale;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 8.2f, scale,
                active ? 1.00f : 0.78f, active ? 0.98f : 0.84f, active ? 0.96f : 0.92f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private void renderCinemaBottomPill(UiRenderer ui, int windowW, int windowH,
                                        double mouseY, SimulationState state) {
        String pill = "Drag Orbit \u2022 [H] HUD \u2022 [-/=] Time \u2022 [X] Binary \u2022 [P] Dive \u2022 [W] Wormhole \u2022 [E] Shred";
        float fadeT = Math.max(0.0f, Math.min(1.0f, (state.cinemaHintAge - 5.0f) / 1.5f));
        float alpha = 0.92f - 0.62f * fadeT;
        if (mouseY > windowH - 72.0) {
            alpha = Math.max(alpha, 0.88f);
        }

        float scale = windowW < 1140 ? 0.88f : 1.00f;
        float textW = pill.length() * 8f * scale;
        float pw = Math.min(windowW - 24f, textW + 28f);
        float px = (windowW - pw) * 0.5f;
        float py = windowH - 36f;

        ui.drawGradientGlassPanel(px, py, pw, 24f, 12f,
                0.05f, 0.07f, 0.11f, 0.74f * alpha,
                0.78f, 0.86f, 0.98f, 0.26f * alpha);
        ui.drawText(pill, px + (pw - textW) * 0.5f, py + 8f, scale,
                0.88f, 0.92f, 0.98f, alpha);
    }

    private void renderToastPill(UiRenderer ui, int windowW, SimulationState state, float topY) {
        float alpha = state.readActiveToast(toastScratch);
        if (alpha <= 0.01f || toastScratch.length() == 0) {
            return;
        }
        float scale = 1.04f;
        float textW = toastScratch.length() * 8f * scale;
        float pw = Math.min(windowW - 40f, textW + 30f);
        float ph = 26f;
        float px = (windowW - pw) * 0.5f;
        float py = topY - (1.0f - alpha) * 6f;

        ui.drawGradientGlassPanel(px, py, pw, ph, 13f,
                0.06f, 0.09f, 0.14f, 0.88f * alpha,
                0.96f, 0.78f, 0.44f, 0.66f * alpha);
        ui.drawText(toastScratch, px + (pw - textW) * 0.5f, py + 8.5f, scale,
                0.96f, 0.95f, 0.92f, alpha);
    }

    private void renderTopPlungeBanner(UiRenderer ui, int windowW,
                                       SimulationState state, CameraController camera) {
        float bw = Math.min(460f, windowW - 780f);
        if (state.hudMode != SimulationState.HUD_FULL) {
            bw = Math.min(460f, windowW - 48f);
        }
        if (bw < 310f) {
            return;
        }
        float bx = (windowW - bw) * 0.5f;
        float by = 16f;
        float bh = 96f;

        boolean danger = state.plungeZone >= SimulationState.ZONE_EVENT_HORIZON;
        ui.drawGradientGlassPanel(bx, by, bw, bh, 10f,
                danger ? 0.14f : 0.07f,
                danger ? 0.07f : 0.10f,
                danger ? 0.16f : 0.16f, 0.88f,
                danger ? 0.98f : 0.42f,
                danger ? 0.48f : 0.85f,
                danger ? 0.92f : 1.00f, 0.58f);

        float cx = bx + 14f;
        float cy = by + 11f;

        sb.setLength(0);
        sb.append("PLUNGE ZONE: ").append(state.plungeZoneName());
        ui.drawText(sb, cx, cy, 1.16f,
                danger ? 1.0f : 0.45f,
                danger ? 0.68f : 0.92f,
                danger ? 0.96f : 1.0f, 1.0f);
        cy += 17f;
        ui.drawRect(cx, cy, bw - 28f, 1f, 0f, 1f, 1f, 1f, 0.14f);
        cy += 8f;

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
        ui.drawText(sb, cx, cy, 1.04f, 0.92f, 0.95f, 1.0f, 0.98f);
        cy += 15f;

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
        ui.drawText(sb, cx, cy, 1.02f, 0.98f, 0.80f, 0.46f, 0.98f);
        cy += 16f;

        String footer = state.plungeEnded
                ? (state.effectiveSpin() > 0.05f
                    ? "CAUCHY MASS-INFLATION  |  [P] REPLAY  |  [R] RESET"
                    : "SINGULARITY REACHED  |  [P] REPLAY  |  [R] RESET")
                : "DRAG: 360° FREE-LOOK  |  SCROLL: SPEED  |  [P] ABORT";
        ui.drawText(footer, cx, cy, 0.92f, 0.72f, 0.82f, 0.94f, 0.92f);
    }

    private void renderLeftProfilerPanel(UiRenderer ui, int windowW, int windowH,
                                         SimulationState state, CameraController camera,
                                         BinaryMergerPhysics binary,
                                         HardwareTelemetry telemetry) {
        boolean isBinary = state.binaryMode && binary != null;
        boolean hasTde = !isBinary && !state.wormholeMode
                && (telemetry.tdeActiveParticles > 0 || telemetry.tdeSwallowedParticles > 0);
        float lx = 16f;
        float ly = 16f;
        float lw = Math.min(388f, windowW - 32f);
        float desiredH = isBinary ? 386f : (hasTde ? 394f : 368f);
        float lh = Math.min(desiredH, windowH - 68f);

        float accR = modeAccentR(state);
        float accG = modeAccentG(state);
        float accB = modeAccentB(state);

        ui.drawGradientGlassPanel(lx, ly, lw, lh, 11f,
                0.06f, 0.08f, 0.13f, 0.86f,
                accR, accG, accB, 0.40f);
        ui.drawAccentBar(lx + 14f, ly + 5f, lw - 28f, 2.5f, accR, accG, accB, 0.78f);

        float cx = lx + 14f;
        float cy = ly + 15f;
        float innerW = lw - 28f;

        String headerTitle = isBinary
                ? "BINARY MERGER & LIGO TELEMETRY"
                : (state.wormholeMode ? "WORMHOLE TELEMETRY" : "KERR-SCHILD TELEMETRY");
        ui.drawText(headerTitle, cx, cy, 1.22f, accR, accG, accB, 1.0f);
        cy += 18f;
        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        cy += 8f;

        float fpsR = telemetry.currentFps >= 58f ? 0.35f : 0.98f;
        float fpsG = telemetry.currentFps >= 58f ? 0.95f : 0.45f;
        ui.drawText(cachedFpsLine, cx, cy, 1.12f, fpsR, fpsG, 0.55f, 1.0f);
        cy += 17f;

        ui.drawText(cachedGpuNameLine, cx, cy, 1.08f, 0.55f, 0.85f, 1.0f, 1.0f);
        cy += 14f;

        ui.drawText(cachedGpuLoadLine, cx, cy, 1.08f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 12.5f;
        ui.drawProgressBar(cx, cy, innerW, 5.5f, cachedGpuLoadPct / 100f, 0.25f, 0.82f, 1.0f);
        cy += 11f;

        ui.drawText(cachedPassesLine, cx, cy, 1.02f, 0.74f, 0.82f, 0.94f, 0.96f);
        cy += 14f;

        if (cachedVramLine.length() > 0) {
            ui.drawText(cachedVramLine, cx, cy, 1.04f, 0.72f, 0.78f, 0.88f, 0.95f);
            cy += 11.5f;
            ui.drawProgressBar(cx, cy, innerW, 4.5f, cachedVramPct, 0.65f, 0.55f, 1.0f);
            cy += 12f;
        } else {
            cy += 4f;
        }

        ui.drawText(cachedCpuSubmitLine, cx, cy, 1.08f, 0.45f, 0.95f, 0.72f, 1.0f);
        cy += 13.5f;

        ui.drawText(cachedCpuPctLine, cx, cy, 1.08f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 12.5f;
        ui.drawProgressBar(cx, cy, innerW, 5.5f, cachedProcCpuPct / 100f, 0.32f, 0.92f, 0.65f);
        cy += 11f;

        ui.drawText(cachedHeapLine, cx, cy, 1.04f, 0.72f, 0.78f, 0.88f, 0.95f);
        cy += 13.5f;

        ui.drawRect(cx, cy, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        cy += 7f;

        if (isBinary) {
            BinaryMergerPhysics.Phase ph = binary.phase();
            boolean hotPhase = (ph == BinaryMergerPhysics.Phase.MERGER || ph == BinaryMergerPhysics.Phase.PLUNGE);

            sb.setLength(0);
            sb.append("STAGE: ").append(ph.label()).append("  SEP: ");
            appendFixed2(sb, binary.currentSeparation);
            sb.append('M');
            ui.drawText(sb, cx, cy, 1.06f,
                    hotPhase ? 1.00f : 0.40f,
                    hotPhase ? 0.72f : 0.90f,
                    hotPhase ? 0.38f : 1.00f, 0.98f);
            cy += 14f;

            sb.setLength(0);
            sb.append("GW FREQ: ");
            appendFixed1(sb, binary.gwFreqHz);
            sb.append("Hz  REMNANT a*: ");
            appendFixed3(sb, binary.remnantSpin);
            ui.drawText(sb, cx, cy, 1.02f, 0.95f, 0.84f, 0.56f, 0.96f);
            cy += 14f;

            sb.setLength(0);
            sb.append("M1:");
            appendFixed2(sb, binary.mass1);
            sb.append(" M2:");
            appendFixed2(sb, binary.mass2);
            sb.append(" Mf:");
            appendFixed3(sb, binary.remnantMass);
            sb.append("M  v:");
            appendFixed2(sb, binary.orbitalBeta);
            sb.append('c');
            ui.drawText(sb, cx, cy, 1.00f, 0.74f, 0.84f, 0.96f, 0.95f);
            cy += 15f;
        } else if (state.wormholeMode) {
            float lVal = (float) camera.currentWormholeL();
            float throatBand = state.wormholeA + 0.65f;
            boolean inThroat = Math.abs(lVal) <= throatBand;
            boolean inUnivB = lVal < -throatBand;

            ui.drawText("METRIC: MORRIS-THORNE (DNEG)", cx, cy, 1.04f, 0.42f, 0.95f, 0.88f, 0.96f);
            cy += 13f;

            sb.setLength(0);
            sb.append("l=");
            if (lVal >= 0f) sb.append('+');
            appendFixed2(sb, lVal);
            sb.append("  ZONE: ");
            sb.append(inThroat ? "THROAT" : (inUnivB ? "UNIVERSE B" : "UNIVERSE A"));
            ui.drawText(sb, cx, cy, 1.06f,
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
            ui.drawText(sb, cx, cy, 1.02f, 0.78f, 0.86f, 0.94f, 0.92f);
            cy += 15f;
        } else {
            sb.setLength(0);
            sb.append("r=");
            appendFixed2(sb, (float) camera.currentKerrRadius());
            sb.append("M  r+=");
            appendFixed2(sb, (float) state.outerHorizonRadius());
            sb.append("M  ISCO=");
            appendFixed2(sb, (float) state.progradeIscoRadius());
            sb.append('M');
            ui.drawText(sb, cx, cy, 1.04f, 0.96f, 0.82f, 0.56f, 0.96f);
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
            ui.drawText(sb, cx, cy, 1.02f,
                    insideHorizon ? 1.0f : 0.62f,
                    insideHorizon ? 0.45f : 0.86f,
                    insideHorizon ? 0.52f : 1.0f, 0.95f);
            cy += 13f;

            ui.drawText(cachedTdeLine1, cx, cy, 1.02f,
                    hasTde ? 1.0f : 0.68f,
                    hasTde ? 0.78f : 0.76f,
                    hasTde ? 0.38f : 0.86f, 0.98f);
            cy += 13f;
            if (hasTde && cachedTdeLine2.length() > 0) {
                ui.drawText(cachedTdeLine2, cx, cy, 0.98f, 0.82f, 0.88f, 0.98f, 0.94f);
                cy += 12f;
                ui.drawProgressBar(cx, cy, innerW, 4.5f, telemetry.tdeHorizonSurge, 1.00f, 0.64f, 0.28f);
                cy += 11f;
            } else {
                cy += 3f;
            }
        }

        // Rolling Oscilloscope: LIGO Strain h+(t) in Binary mode, GPU vs CPU ms otherwise
        float gh = Math.min(74f, (ly + lh - 10f) - cy);
        if (gh >= 36f) {
            float gx = cx;
            float gy = cy;
            float gw = innerW;
            ui.drawGlassPanel(gx, gy, gw, gh, 6f, 0.03f, 0.04f, 0.07f, 0.86f, 1f, 1f, 1f, 0.14f);

            if (isBinary) {
                ui.drawText("LIGO STRAIN h+(t) | QUADRUPOLE GW", gx + 6f, gy + 5f, 0.98f, 0.45f, 0.88f, 1.0f, 0.92f);
                float midY = gy + 17f + (gh - 22f) * 0.5f;
                float halfAmpPx = (gh - 24f) * 0.46f;
                ui.drawRect(gx + 4f, midY, gw - 8f, 1f, 0f, 1f, 1f, 1f, 0.16f);

                float[] hist = binary.strainHistory();
                int cursor = binary.strainCursor();
                int count = BinaryMergerPhysics.STRAIN_HISTORY_LEN;
                float stepX = (gw - 8f) / (count - 1);
                for (int i = 0; i < count - 1; i++) {
                    int i0 = (cursor + i) % count;
                    int i1 = (cursor + i + 1) % count;
                    float x0 = gx + 4f + i * stepX;
                    float x1 = gx + 4f + (i + 1) * stepX;
                    float h0 = Math.max(-1.0f, Math.min(1.0f, hist[i0]));
                    float h1 = Math.max(-1.0f, Math.min(1.0f, hist[i1]));
                    float y0 = midY - h0 * halfAmpPx;
                    float y1 = midY - h1 * halfAmpPx;
                    float amp = Math.min(1.0f, Math.max(Math.abs(h0), Math.abs(h1)));
                    ui.drawLine(x0, y0, x1, y1, 1.75f,
                            0.24f + 0.76f * amp, 0.88f - 0.12f * amp, 1.00f - 0.62f * amp, 0.96f);
                }
            } else {
                ui.drawText("GPU(CYAN) CPU(GRN) | 16.6ms 6.9ms", gx + 6f, gy + 5f, 0.98f, 0.65f, 0.72f, 0.82f, 0.85f);

                float maxScaleMs = 20.0f;
                for (int i = 0; i < HardwareTelemetry.HISTORY_LEN; i++) {
                    int idx = (telemetry.historyCursor + i) % HardwareTelemetry.HISTORY_LEN;
                    float m = Math.max(telemetry.gpuHistory[idx], telemetry.cpuHistory[idx]) * 1.15f;
                    if (m > maxScaleMs) maxScaleMs = m;
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
    }

    private void renderRightControlPanel(UiRenderer ui, int windowW, int windowH,
                                         double mouseX, double mouseY,
                                         SimulationState state, CameraController camera,
                                         HardwareTelemetry telemetry) {
        float rw = 356f;
        float rx = windowW - rw - 16f;
        float ry = 16f;
        boolean expanded = state.controlsExpanded && windowH >= 500;
        float rh = expanded ? Math.min(408f, windowH - 68f) : 44f;

        float accR = modeAccentR(state);
        float accG = modeAccentG(state);
        float accB = modeAccentB(state);

        ui.drawGradientGlassPanel(rx, ry, rw, rh, 11f,
                0.06f, 0.08f, 0.13f, 0.86f,
                accR, accG, accB, 0.42f);
        ui.drawAccentBar(rx + 14f, ry + 5f, rw - 28f, 2.5f, accR, accG, accB, 0.80f);

        float px = rx + 14f;
        float py = ry + 15f;
        float innerW = rw - 28f;

        ui.drawText("OBSERVATORY CONSOLE", px, py, 1.22f, accR, accG, accB, 1.0f);

        float btnW = 24f;
        float btnH = 20f;
        float btnX = rx + rw - btnW - 12f;
        float btnY = ry + 12f;
        boolean minHover = isOver(mouseX, mouseY, btnX, btnY, btnW, btnH);
        if (minHover) hoveredId = ACT_TOGGLE_PANEL;
        ui.drawGlassPanel(btnX, btnY, btnW, btnH, 5f,
                1f, 1f, 1f, minHover ? 0.18f : 0.09f,
                1f, 1f, 1f, minHover ? 0.45f : 0.24f);
        ui.drawText(expanded ? "-" : "+", btnX + 8f, btnY + 5f, 1.22f, 1f, 1f, 1f, 0.94f);
        addHitBox(btnX, btnY, btnW, btnH, ACT_TOGGLE_PANEL, 0f, 0f);

        if (!expanded) {
            return;
        }

        py += 19f;
        // 4-Tab Segmented Bar: SCENE | PHYSICS | TDE LAB | OPTICS
        float tabGap = 4f;
        float tabW = (innerW - tabGap * 3f) / 4f;
        float tabH = 24f;
        drawConsoleTab(ui, mouseX, mouseY, px, py, tabW, tabH, "SCENE",
                state.activeConsoleTab == SimulationState.TAB_SCENE, 0.95f, 0.78f, 0.48f, ACT_TAB_SCENE);
        drawConsoleTab(ui, mouseX, mouseY, px + (tabW + tabGap), py, tabW, tabH, "PHYSICS",
                state.activeConsoleTab == SimulationState.TAB_PHYSICS, 0.36f, 0.86f, 1.00f, ACT_TAB_PHYSICS);
        drawConsoleTab(ui, mouseX, mouseY, px + (tabW + tabGap) * 2f, py, tabW, tabH, "TDE LAB",
                state.activeConsoleTab == SimulationState.TAB_TDE_LAB, 1.00f, 0.66f, 0.34f, ACT_TAB_TDE_LAB);
        drawConsoleTab(ui, mouseX, mouseY, px + (tabW + tabGap) * 3f, py, tabW, tabH, "OPTICS",
                state.activeConsoleTab == SimulationState.TAB_OPTICS, 0.72f, 0.62f, 1.00f, ACT_TAB_OPTICS);
        py += tabH + 8f;

        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.12f);
        py += 9f;

        float colW = (innerW - 6f) * 0.5f;
        float rowH = 22f;

        switch (state.activeConsoleTab) {
            case SimulationState.TAB_PHYSICS -> py = renderPhysicsTab(ui, mouseX, mouseY, px, py, innerW, colW, rowH, state, camera);
            case SimulationState.TAB_TDE_LAB -> py = renderTdeLabTab(ui, mouseX, mouseY, px, py, innerW, colW, rowH, state, telemetry);
            case SimulationState.TAB_OPTICS -> py = renderOpticsTab(ui, mouseX, mouseY, px, py, innerW, colW, rowH, state);
            default -> py = renderSceneTab(ui, mouseX, mouseY, px, py, innerW, colW, rowH, state, camera);
        }

        // Dedicated 1-line Hover Tooltip Footer Bar at the bottom of the panel
        float tipH = 22f;
        float tipY = ry + rh - tipH - 8f;
        if (tipY > py + 2f) {
            ui.drawGlassPanel(px, tipY, innerW, tipH, 6f,
                    0.04f, 0.06f, 0.10f, 0.86f,
                    hoveredId != 0 ? accR : 0.7f,
                    hoveredId != 0 ? accG : 0.8f,
                    hoveredId != 0 ? accB : 0.9f,
                    hoveredId != 0 ? 0.42f : 0.14f);
            String tip = tooltipFor(hoveredId);
            float tipScale = 0.84f;
            int maxChars = (int) ((innerW - 14f) / (8f * tipScale));
            if (tip.length() > maxChars) {
                sb2.setLength(0);
                sb2.append(tip, 0, Math.max(4, maxChars - 2)).append("..");
                ui.drawText(sb2, px + 7f, tipY + 7.5f, tipScale,
                        hoveredId != 0 ? 0.94f : 0.64f,
                        hoveredId != 0 ? 0.96f : 0.72f,
                        hoveredId != 0 ? 1.00f : 0.82f, 0.96f);
            } else {
                ui.drawText(tip, px + 7f, tipY + 7.5f, tipScale,
                        hoveredId != 0 ? 0.94f : 0.64f,
                        hoveredId != 0 ? 0.96f : 0.72f,
                        hoveredId != 0 ? 1.00f : 0.82f, 0.96f);
            }
        }
    }

    private float renderSceneTab(UiRenderer ui, double mouseX, double mouseY,
                                 float px, float py, float innerW, float colW, float rowH,
                                 SimulationState state, CameraController camera) {
        ui.drawText("SPACETIME SANDBOX", px, py, 0.96f, 0.66f, 0.76f, 0.88f, 0.92f);
        py += 14f;

        boolean singleKerr = !state.binaryMode && !state.wormholeMode;
        String diveLabel = state.plungeActive
                ? (state.plungeEnded ? "[P] REPLAY" : "[P] ABORT")
                : "[P] DIVE IN";
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "KERR BLACK HOLE", singleKerr, ACT_MODE_KERR,
                px + colW + 6f, "[X] BINARY", state.binaryMode, ACT_BINARY);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[W] WORMHOLE", state.wormholeMode, ACT_WORMHOLE,
                px + colW + 6f, diveLabel, state.plungeActive, ACT_PLUNGE);

        py += 5f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        py += 8f;

        ui.drawText("SCENE LAYERS & PLAYBACK", px, py, 0.96f, 0.66f, 0.76f, 0.88f, 0.92f);
        py += 14f;

        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[D] DISK", state.showDisk, ACT_DISK,
                px + colW + 6f, "[J] JETS", state.showJets, ACT_JETS);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[C] SKY GRID", state.showGrid, ACT_SKY_GRID,
                px + colW + 6f, "[O] AUTO ORBIT", state.autoOrbit, ACT_AUTO_ORBIT);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[T] TOUR", state.attractTourActive, ACT_ATTRACT_TOUR,
                px + colW + 6f, "[E] SHRED STAR", false, ACT_SHRED_STAR);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[A] AUDIO", state.audioEnabled, ACT_AUDIO,
                px + colW + 6f, "[V] V-SYNC", state.vsync, ACT_VSYNC);

        py += 5f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        py += 8f;

        ui.drawText("CAMERA PRESETS", px, py, 0.96f, 0.66f, 0.76f, 0.88f, 0.92f);
        py += 14f;

        float row3W = (innerW - 12f) / 3f;
        float row2W = (innerW - 6f) * 0.5f;
        if (state.binaryMode) {
            drawPresetButton(ui, mouseX, mouseY, px, py, row3W, 22f, "[1] OBLIQUE", PRESET_BIN_OBLIQUE);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f), py, row3W, 22f, "[2] TOP-DN", PRESET_BIN_TOP);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f) * 2f, py, row3W, 22f, "[3] CLOSE", PRESET_BIN_CLOSE);
            py += 26f;
            drawPresetButton(ui, mouseX, mouseY, px, py, row2W, 22f, "[SH+X] REPLAY", ACT_BINARY_REPLAY);
            drawPresetButton(ui, mouseX, mouseY, px + row2W + 6f, py, row2W, 22f, "[R] RESET ALL", PRESET_RESET);
            py += 26f;
        } else if (state.wormholeMode) {
            drawPresetButton(ui, mouseX, mouseY, px, py, row3W, 22f, "[1] UNIV A", PRESET_UNIV_A);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f), py, row3W, 22f, "[2] THROAT", PRESET_THROAT);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f) * 2f, py, row3W, 22f, "[3] UNIV B", PRESET_UNIV_B);
            py += 26f;
            drawPresetButton(ui, mouseX, mouseY, px, py, row2W, 22f,
                    camera.isFlyingThrough() ? "FLYING THRU..." : "[4] FLY THRU", ACT_FLY_THROUGH);
            drawPresetButton(ui, mouseX, mouseY, px + row2W + 6f, py, row2W, 22f, "[R] RESET ALL", PRESET_RESET);
            py += 26f;
        } else {
            drawPresetButton(ui, mouseX, mouseY, px, py, row3W, 22f, "[1] EDGE", PRESET_EDGE);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f), py, row3W, 22f, "[2] POLAR", PRESET_POLE);
            drawPresetButton(ui, mouseX, mouseY, px + (row3W + 6f) * 2f, py, row3W, 22f, "[3] RING", PRESET_RING);
            py += 26f;
            drawPresetButton(ui, mouseX, mouseY, px, py, row2W, 22f, "[4] HORIZON DIVE", PRESET_DIVE);
            drawPresetButton(ui, mouseX, mouseY, px + row2W + 6f, py, row2W, 22f, "[R] RESET ALL", PRESET_RESET);
            py += 26f;
        }
        return py;
    }

    private float renderPhysicsTab(UiRenderer ui, double mouseX, double mouseY,
                                   float px, float py, float innerW, float colW, float rowH,
                                   SimulationState state, CameraController camera) {
        if (state.binaryMode) {
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[G] GW RIPPLES", state.binaryGwOverlay, ACT_BINARY_GW_OVERLAY,
                    px + colW + 6f, "AUTO LOOP", state.binaryAutoLoop, ACT_BINARY_AUTOLOOP);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[L] LENSING", state.lensingEnabled, ACT_LENSING,
                    px + colW + 6f, "[B] DOPPLER", state.dopplerEnabled, ACT_DOPPLER);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[REPLAY MERGER]", false, ACT_BINARY_REPLAY,
                    px + colW + 6f, "[D] DISK", state.showDisk, ACT_DISK);

            py += 4f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
            py += 8f;

            sb.setLength(0);
            appendFixed2(sb, state.binaryMassRatio);
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "MASS RATIO (q)", state.binaryMassRatio,
                    SimulationState.MIN_MASS_RATIO, SimulationState.MAX_MASS_RATIO, sb, SLIDER_BIN_MASS_RATIO);

            py = drawLogTimeScaleSlider(ui, mouseX, mouseY, px, py, innerW, state);

            sb.setLength(0);
            sb.append(Math.round(11800f * state.diskTempScale)).append(" K");
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "DISK TEMP (K)", state.diskTempScale,
                    SimulationState.MIN_TEMP_SCALE, SimulationState.MAX_TEMP_SCALE, sb, SLIDER_TEMP);

            sb.setLength(0);
            appendFixed2(sb, state.diskDensity);
            sb.append('x');
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "PLASMA DENSITY", state.diskDensity,
                    SimulationState.MIN_DENSITY, SimulationState.MAX_DENSITY, sb, SLIDER_DENSITY);
        } else if (state.wormholeMode) {
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[FLY THROUGH]", camera.isFlyingThrough(), ACT_FLY_THROUGH,
                    px + colW + 6f, "[C] SKY GRID", state.showGrid, ACT_SKY_GRID);

            py += 4f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
            py += 8f;

            sb.setLength(0);
            appendFixed2(sb, state.wormholeRho);
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "THROAT RADIUS (rho)", state.wormholeRho,
                    SimulationState.MIN_WORMHOLE_RHO, SimulationState.MAX_WORMHOLE_RHO, sb, SLIDER_WH_RHO);

            sb.setLength(0);
            appendFixed2(sb, state.wormholeA);
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "THROAT LENGTH (a)", state.wormholeA,
                    SimulationState.MIN_WORMHOLE_A, SimulationState.MAX_WORMHOLE_A, sb, SLIDER_WH_A);

            sb.setLength(0);
            appendFixed2(sb, state.wormholeM);
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "LENSING WIDTH (Mw)", state.wormholeM,
                    SimulationState.MIN_WORMHOLE_M, SimulationState.MAX_WORMHOLE_M, sb, SLIDER_WH_M);

            py = drawLogTimeScaleSlider(ui, mouseX, mouseY, px, py, innerW, state);
        } else {
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[L] LENSING", state.lensingEnabled, ACT_LENSING,
                    px + colW + 6f, "[B] DOPPLER", state.dopplerEnabled, ACT_DOPPLER);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[K] KERR SPIN", state.kerrSpinEnabled, ACT_KERR_SPIN,
                    px + colW + 6f, "[F] FREE-FALL", state.freeFallMode, ACT_FREE_FALL);
            py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                    "[I] ORDER VIS", state.orderVisualizer, ACT_ORDER_VIS,
                    px + colW + 6f, "[J] JETS", state.showJets, ACT_JETS);

            py += 4f;
            ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
            py += 8f;

            sb.setLength(0);
            appendFixed3(sb, state.spinParam);
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "KERR SPIN (a*)", state.spinParam,
                    SimulationState.MIN_SPIN, SimulationState.MAX_SPIN, sb, SLIDER_SPIN);

            sb.setLength(0);
            appendFixed2(sb, state.plungeSpeed);
            sb.append('x');
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "PLUNGE SPEED", state.plungeSpeed,
                    SimulationState.MIN_PLUNGE_SPEED, SimulationState.MAX_PLUNGE_SPEED, sb, SLIDER_PLUNGE_SPEED);

            sb.setLength(0);
            sb.append(Math.round(11800f * state.diskTempScale)).append(" K");
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "DISK TEMP (K)", state.diskTempScale,
                    SimulationState.MIN_TEMP_SCALE, SimulationState.MAX_TEMP_SCALE, sb, SLIDER_TEMP);

            sb.setLength(0);
            appendFixed2(sb, state.diskDensity);
            sb.append('x');
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "PLASMA DENSITY", state.diskDensity,
                    SimulationState.MIN_DENSITY, SimulationState.MAX_DENSITY, sb, SLIDER_DENSITY);

            sb.setLength(0);
            appendFixed2(sb, state.jetPower);
            sb.append('x');
            py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "JET POWER", state.jetPower,
                    SimulationState.MIN_JET_POWER, SimulationState.MAX_JET_POWER, sb, SLIDER_JET_POWER);

            py = drawLogTimeScaleSlider(ui, mouseX, mouseY, px, py, innerW, state);
        }
        return py;
    }

    private float renderTdeLabTab(UiRenderer ui, double mouseX, double mouseY,
                                  float px, float py, float innerW, float colW, float rowH,
                                  SimulationState state, HardwareTelemetry telemetry) {
        ui.drawText("STELLAR ARCHETYPE ([SHIFT+E] CYCLE)", px, py, 0.94f, 0.68f, 0.78f, 0.90f, 0.94f);
        py += 14f;

        drawArchetypeCard(ui, mouseX, mouseY, px, py, colW, 24f, StellarArchetype.SOLAR_MAIN_SEQ,
                state.tdeArchetypeIndex == 0, ACT_TDE_ARCH_0);
        drawArchetypeCard(ui, mouseX, mouseY, px + colW + 6f, py, colW, 24f, StellarArchetype.RED_SUPERGIANT,
                state.tdeArchetypeIndex == 1, ACT_TDE_ARCH_1);
        py += 28f;

        drawArchetypeCard(ui, mouseX, mouseY, px, py, colW, 24f, StellarArchetype.BLUE_SUPERGIANT,
                state.tdeArchetypeIndex == 2, ACT_TDE_ARCH_2);
        drawArchetypeCard(ui, mouseX, mouseY, px + colW + 6f, py, colW, 24f, StellarArchetype.WHITE_DWARF,
                state.tdeArchetypeIndex == 3, ACT_TDE_ARCH_3);
        py += 30f;

        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        py += 8f;

        // Interactive Penetration Factor (beta = r_t / r_p)
        sb.setLength(0);
        appendFixed2(sb, state.tdeBeta);
        sb.append(" (").append(StellarArchetype.betaRegimeLabel(state.tdeBeta)).append(')');
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "PENETRATION (beta)", state.tdeBeta,
                StellarArchetype.MIN_BETA, StellarArchetype.MAX_BETA, sb, SLIDER_TDE_BETA);

        // Orbital Inclination (-65 deg .. +65 deg)
        sb.setLength(0);
        int incRounded = Math.round(state.tdeInclinationDeg);
        if (incRounded > 0) sb.append('+');
        sb.append(incRounded).append('°');
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "ORBIT INCLINATION", state.tdeInclinationDeg,
                StellarArchetype.MIN_INCLINATION_DEG, StellarArchetype.MAX_INCLINATION_DEG, sb, SLIDER_TDE_INC);

        py = drawLogTimeScaleSlider(ui, mouseX, mouseY, px, py, innerW, state);

        py += 4f;
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, 24f,
                "[E] LAUNCH STAR", false, ACT_SHRED_STAR,
                px + colW + 6f, "[SH+C] CLEAR", false, ACT_TDE_CLEAR);

        py += 4f;
        // Live TDE Physics & GPU Counter Telemetry Card
        StellarArchetype arch = state.currentTdeArchetype();
        double rP = arch.computePeriapsis(state.tdeBeta, state.outerHorizonRadius());

        float cardH = 74f;
        ui.drawGlassPanel(px, py, innerW, cardH, 7f,
                0.04f, 0.05f, 0.09f, 0.88f,
                arch.accentR, arch.accentG, arch.accentB, 0.34f);

        float ty = py + 8f;
        sb.setLength(0);
        sb.append(arch.displayName).append("  |  r_t=");
        appendFixed1(sb, arch.tidalRadius);
        sb.append("M  r_p=");
        appendFixed2(sb, (float) rP);
        sb.append('M');
        ui.drawText(sb, px + 9f, ty, 0.94f, arch.accentR, arch.accentG, arch.accentB, 0.98f);
        ty += 15f;

        sb.setLength(0);
        sb.append("ACTIVE: ").append(telemetry.tdeActiveParticles)
                .append("   BOUND: ").append(telemetry.tdeBoundParticles);
        ui.drawText(sb, px + 9f, ty, 0.94f, 0.90f, 0.94f, 1.00f, 0.96f);
        ty += 14f;

        sb.setLength(0);
        sb.append("UNBOUND TAIL: ").append(telemetry.tdeUnboundParticles)
                .append("   SWALLOWED: ").append(telemetry.tdeSwallowedParticles);
        ui.drawText(sb, px + 9f, ty, 0.92f, 0.78f, 0.86f, 0.96f, 0.94f);
        ty += 15f;

        ui.drawText("HORIZON SURGE", px + 9f, ty, 0.86f, 1.00f, 0.74f, 0.42f, 0.92f);
        ui.drawProgressBar(px + 106f, ty + 2f, innerW - 115f, 5.5f, telemetry.tdeHorizonSurge, 1.00f, 0.64f, 0.28f);

        return py + cardH + 4f;
    }

    private float renderOpticsTab(UiRenderer ui, double mouseX, double mouseY,
                                  float px, float py, float innerW, float colW, float rowH,
                                  SimulationState state) {
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[M] TEMPORAL AA", state.adaptiveAa4x, ACT_ADAPTIVE_AA,
                px + colW + 6f, "ANAMORPHIC", state.anamorphicEnabled, ACT_ANAMORPHIC);
        py = drawToggleRow(ui, mouseX, mouseY, px, py, colW, rowH,
                "[A] AUDIO", state.audioEnabled, ACT_AUDIO,
                px + colW + 6f, "[V] V-SYNC", state.vsync, ACT_VSYNC);

        py += 6f;
        ui.drawRect(px, py, innerW, 1f, 0f, 1f, 1f, 1f, 0.10f);
        py += 10f;

        sb.setLength(0);
        appendFixed2(sb, state.bloomStrength);
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "BLOOM GLOW", state.bloomStrength,
                SimulationState.MIN_BLOOM, SimulationState.MAX_BLOOM, sb, SLIDER_BLOOM);

        sb.setLength(0);
        appendFixed2(sb, state.anamorphicStrength);
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "ANAMORPHIC FLARE", state.anamorphicStrength,
                SimulationState.MIN_ANAMORPHIC, SimulationState.MAX_ANAMORPHIC, sb, SLIDER_ANAMORPHIC);

        sb.setLength(0);
        appendFixed2(sb, state.exposure);
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "HDR EXPOSURE", state.exposure,
                SimulationState.MIN_EXPOSURE, SimulationState.MAX_EXPOSURE, sb, SLIDER_EXPOSURE);

        sb.setLength(0);
        sb.append(Math.round(state.fovDeg * 2f)).append('°');
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "CAMERA FOV", state.fovDeg,
                SimulationState.MIN_FOV_DEG, SimulationState.MAX_FOV_DEG, sb, SLIDER_FOV);

        sb.setLength(0);
        sb.append(Math.round(state.audioVolume * 100f)).append('%');
        py = drawSlider(ui, mouseX, mouseY, px, py, innerW, "AUDIO VOLUME", state.audioVolume,
                SimulationState.MIN_VOLUME, SimulationState.MAX_VOLUME, sb, SLIDER_VOLUME);

        return py;
    }

    private float drawLogTimeScaleSlider(UiRenderer ui, double mouseX, double mouseY,
                                         float px, float py, float innerW, SimulationState state) {
        sb.setLength(0);
        appendCompactScale(sb, state.timeScale);
        sb.append('x');
        float logNorm = (float) (Math.log(state.timeScale / SimulationState.MIN_TIME_SCALE) / LOG_TIME_RATIO);
        return drawSlider(ui, mouseX, mouseY, px, py, innerW, "GLOBAL TIME SCALE",
                logNorm, 0.0f, 1.0f, sb, SLIDER_TIME_SCALE);
    }

    private void drawConsoleTab(UiRenderer ui, double mouseX, double mouseY,
                                float x, float y, float w, float h, String label,
                                boolean active, float r, float g, float b, int id) {
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;

        float bgR = active ? (r * 0.24f) : (hovered ? 0.16f : 0.09f);
        float bgG = active ? (g * 0.24f) : (hovered ? 0.19f : 0.11f);
        float bgB = active ? (b * 0.24f) : (hovered ? 0.26f : 0.16f);
        ui.drawGlassPanel(x, y, w, h, 6f,
                bgR, bgG, bgB, active ? 0.94f : 0.78f,
                active ? r : 0.65f, active ? g : 0.74f, active ? b : 0.88f,
                active ? 0.84f : (hovered ? 0.44f : 0.16f));
        if (active) {
            ui.drawAccentBar(x + 8f, y + h - 3.5f, w - 16f, 2.2f, r, g, b, 0.96f);
        }
        float scale = 0.94f;
        float textW = label.length() * 8f * scale;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 7.2f, scale,
                active ? 1.00f : 0.72f, active ? 0.99f : 0.78f, active ? 0.98f : 0.88f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private void drawArchetypeCard(UiRenderer ui, double mouseX, double mouseY,
                                   float x, float y, float w, float h,
                                   StellarArchetype arch, boolean selected, int id) {
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;

        float bgR = selected ? (arch.accentR * 0.28f) : (hovered ? 0.16f : 0.10f);
        float bgG = selected ? (arch.accentG * 0.24f) : (hovered ? 0.19f : 0.12f);
        float bgB = selected ? (arch.accentB * 0.28f) : (hovered ? 0.26f : 0.17f);

        ui.drawGlassPanel(x, y, w, h, 6f,
                bgR, bgG, bgB, selected ? 0.94f : 0.80f,
                arch.accentR, arch.accentG, arch.accentB,
                selected ? 0.90f : (hovered ? 0.52f : 0.22f));
        ui.drawIndicatorDot(x + 11f, y + h * 0.5f, 3.6f, arch.accentR, arch.accentG, arch.accentB,
                selected ? 1.0f : 0.72f);
        ui.drawText(arch.shortLabel, x + 21f, y + 7.8f, 0.98f,
                selected ? 1.00f : 0.78f, selected ? 0.98f : 0.82f, selected ? 0.98f : 0.90f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
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
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;
        boolean pressed = (pressedId == id);
        float hBoost = pressed ? -0.04f : (hovered ? 0.08f : 0.0f);

        boolean plungeAction = (id == ACT_PLUNGE);
        boolean accentAction = (id == ACT_SHRED_STAR || id == ACT_FLY_THROUGH || id == ACT_BINARY_REPLAY);
        boolean dangerAction = (id == ACT_TDE_CLEAR);
        boolean binaryAction = (id == ACT_BINARY);
        boolean kerrAction = (id == ACT_MODE_KERR);

        float bgR, bgG, bgB, bdR, bdG, bdB, dotR, dotG, dotB;
        if (plungeAction) {
            bgR = (active ? 0.48f : 0.24f) + hBoost;
            bgG = (active ? 0.16f : 0.13f) + hBoost;
            bgB = (active ? 0.54f : 0.34f) + hBoost;
            bdR = 0.96f; bdG = 0.52f; bdB = 1.00f;
            dotR = 1.00f; dotG = 0.56f; dotB = 0.98f;
        } else if (binaryAction) {
            bgR = (active ? 0.14f : 0.10f) + hBoost;
            bgG = (active ? 0.36f : 0.18f) + hBoost;
            bgB = (active ? 0.54f : 0.28f) + hBoost;
            bdR = 0.34f; bdG = 0.88f; bdB = 1.00f;
            dotR = 0.36f; dotG = 0.90f; dotB = 1.00f;
        } else if (kerrAction) {
            bgR = (active ? 0.34f : 0.11f) + hBoost;
            bgG = (active ? 0.24f : 0.13f) + hBoost;
            bgB = (active ? 0.12f : 0.18f) + hBoost;
            bdR = 0.98f; bdG = 0.78f; bdB = 0.42f;
            dotR = 1.00f; dotG = 0.80f; dotB = 0.42f;
        } else if (accentAction) {
            bgR = 0.32f + hBoost;
            bgG = 0.20f + hBoost;
            bgB = 0.10f + hBoost;
            bdR = 1.00f; bdG = 0.72f; bdB = 0.34f;
            dotR = 1.00f; dotG = 0.76f; dotB = 0.38f;
        } else if (dangerAction) {
            bgR = 0.28f + hBoost;
            bgG = 0.13f + hBoost;
            bgB = 0.15f + hBoost;
            bdR = 0.96f; bdG = 0.48f; bdB = 0.48f;
            dotR = 0.96f; dotG = 0.48f; dotB = 0.48f;
        } else {
            bgR = (active ? 0.15f : 0.10f) + hBoost;
            bgG = (active ? 0.32f : 0.12f) + hBoost;
            bgB = (active ? 0.46f : 0.17f) + hBoost;
            bdR = active ? 0.36f : 0.58f;
            bdG = active ? 0.84f : 0.64f;
            bdB = active ? 1.00f : 0.76f;
            dotR = 0.36f; dotG = 0.88f; dotB = 1.00f;
        }
        float bgA = hovered ? 0.94f : 0.80f;
        float bdA = (active || accentAction || plungeAction || dangerAction) ? 0.82f : (hovered ? 0.48f : 0.20f);

        ui.drawGlassPanel(x, y, w, h, 6f, bgR, bgG, bgB, bgA, bdR, bdG, bdB, bdA);

        boolean showDot = !accentAction && !dangerAction;
        float textX = x + (showDot ? 16f : 8f);
        if (showDot) {
            ui.drawIndicatorDot(x + 9f, y + h * 0.5f, 2.7f,
                    active ? dotR : 0.50f, active ? dotG : 0.56f, active ? dotB : 0.66f,
                    active ? 0.98f : 0.34f);
        }
        ui.drawText(label, textX, y + 7.2f, 0.96f,
                (active || accentAction || plungeAction || dangerAction) ? 0.98f : 0.70f,
                (active || accentAction || plungeAction || dangerAction) ? 0.98f : 0.75f,
                (active || accentAction || plungeAction || dangerAction) ? 1.00f : 0.84f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    private float drawSlider(UiRenderer ui, double mouseX, double mouseY,
                             float x, float y, float w,
                             String label, float val, float min, float max,
                             CharSequence valText, int id) {
        boolean hovered = isOver(mouseX, mouseY, x - 4f, y, w + 8f, 24f) || activeSliderId == id;
        if (hovered) hoveredId = id;

        ui.drawText(label, x, y, 0.98f,
                hovered ? 0.96f : 0.78f, hovered ? 0.98f : 0.84f, hovered ? 1.00f : 0.92f, 0.96f);
        float valW = valText.length() * 8f * 0.98f;
        ui.drawText(valText, x + w - valW, y, 0.98f, 0.98f, 0.80f, 0.48f, 1.0f);

        float trackY = y + 12.5f;
        float trackH = 5f;
        float norm = Math.max(0f, Math.min(1f, (val - min) / (max - min)));

        ui.drawRect(x, trackY, w, trackH, 2.5f, 1f, 1f, 1f, hovered ? 0.20f : 0.13f);
        ui.drawRect(x, trackY, Math.max(4f, w * norm), trackH, 2.5f,
                hovered ? 1.00f : 0.94f, hovered ? 0.74f : 0.66f, hovered ? 0.36f : 0.32f, 0.94f);

        float thumbX = x + w * norm - 4.5f;
        ui.drawRect(thumbX, trackY - 3f, 9f, 11f, 4.5f, 1f, 1f, 1f, hovered ? 1.0f : 0.94f);

        addHitBox(x - 4f, y, w + 8f, 23f, id, min, max);
        return y + 26.5f;
    }

    private void drawPresetButton(UiRenderer ui, double mouseX, double mouseY,
                                  float x, float y, float w, float h,
                                  String label, int id) {
        boolean hovered = isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) hoveredId = id;
        ui.drawGlassPanel(x, y, w, h, 6f,
                hovered ? 0.24f : 0.13f,
                hovered ? 0.28f : 0.16f,
                hovered ? 0.38f : 0.23f,
                0.88f, 0.95f, 0.75f, 0.45f, hovered ? 0.82f : 0.30f);
        float scale = 0.96f;
        float textW = label.length() * 8f * scale;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 7.2f, scale, 0.95f, 0.96f, 0.99f, 1.0f);
        addHitBox(x, y, w, h, id, 0f, 0f);
    }

    public int handleClick(double mx, double my, SimulationState state) {
        if (state.hudMode == SimulationState.HUD_CLEAN) {
            return 0;
        }
        for (int i = 0; i < hitBoxCount; i++) {
            HitBox box = hitBoxes[i];
            if (mx >= box.x && mx <= box.x + box.w && my >= box.y && my <= box.y + box.h) {
                pressedId = box.id;
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
            if (hitBoxes[i].id == activeSliderId) {
                updateSliderFromMouse(hitBoxes[i], mx, state);
                return true;
            }
        }
        return false;
    }

    private void updateSliderFromMouse(HitBox box, double mx, SimulationState state) {
        float trackX = box.x + 4f;
        float trackW = Math.max(1f, box.w - 8f);
        float norm = (float) Math.max(0.0, Math.min(1.0, (mx - trackX) / trackW));
        float val = box.min + norm * (box.max - box.min);

        switch (box.id) {
            case SLIDER_SPIN -> {
                state.spinParam = val;
                state.kerrSpinEnabled = true;
            }
            case SLIDER_PLUNGE_SPEED -> state.plungeSpeed = val;
            case SLIDER_TEMP -> state.diskTempScale = val;
            case SLIDER_DENSITY -> state.diskDensity = val;
            case SLIDER_JET_POWER -> state.jetPower = val;
            case SLIDER_BLOOM -> state.bloomStrength = val;
            case SLIDER_ANAMORPHIC -> {
                state.anamorphicStrength = val;
                state.anamorphicEnabled = val > 0.005f;
            }
            case SLIDER_EXPOSURE -> state.exposure = val;
            case SLIDER_FOV -> state.fovDeg = val;
            case SLIDER_VOLUME -> state.audioVolume = val;
            case SLIDER_WH_RHO -> state.wormholeRho = val;
            case SLIDER_WH_A -> state.wormholeA = val;
            case SLIDER_WH_M -> state.wormholeM = val;
            case SLIDER_BIN_MASS_RATIO -> state.binaryMassRatio = val;
            case SLIDER_TIME_SCALE -> {
                float scaled = (float) (SimulationState.MIN_TIME_SCALE * Math.exp(norm * LOG_TIME_RATIO));
                state.setTimeScale(scaled);
            }
            case SLIDER_TDE_BETA -> state.tdeBeta = StellarArchetype.clampBeta(val);
            case SLIDER_TDE_INC -> state.tdeInclinationDeg = StellarArchetype.clampInclinationDeg(val);
            default -> {
            }
        }
    }

    private void addHitBox(float x, float y, float w, float h, int id, float min, float max) {
        if (hitBoxCount < MAX_HITBOXES) {
            hitBoxes[hitBoxCount++].set(x, y, w, h, id, min, max);
        }
    }

    private static boolean isOver(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float modeAccentR(SimulationState state) {
        if (state.binaryMode) return 0.36f;
        if (state.wormholeMode) return 0.38f;
        if (state.plungeActive) return 0.96f;
        return 0.96f;
    }

    private static float modeAccentG(SimulationState state) {
        if (state.binaryMode) return 0.86f;
        if (state.wormholeMode) return 0.92f;
        if (state.plungeActive) return 0.50f;
        return 0.78f;
    }

    private static float modeAccentB(SimulationState state) {
        if (state.binaryMode) return 1.00f;
        if (state.wormholeMode) return 0.86f;
        if (state.plungeActive) return 0.96f;
        return 0.46f;
    }

    /** Zero-allocation constant physics/UI tooltip lookup by hovered control ID. */
    private static String tooltipFor(int id) {
        return switch (id) {
            case ACT_TAB_SCENE -> "Switch spacetime modes, scene layers, and camera presets";
            case ACT_TAB_PHYSICS -> "Tune spacetime metric, plasma physics, and time scale";
            case ACT_TAB_TDE_LAB -> "Launch 4 stellar archetypes with custom beta & inclination";
            case ACT_TAB_OPTICS -> "Configure TAA, Karis bloom, anamorphic flares, FOV & audio";
            case ACT_MODE_KERR -> "Single rotating Kerr-Schild black hole with volumetric TDE";
            case ACT_BINARY -> "Superposed binary BH inspiral, merger & LIGO GW ripples";
            case ACT_WORMHOLE -> "Morris-Thorne / Interstellar traversable wormhole throat";
            case ACT_PLUNGE -> "Doran free-fall plunge across r+ to the Cauchy horizon r-";
            case ACT_SHRED_STAR -> "Launch 65,536-particle star into the black hole Roche limit";
            case ACT_TDE_CLEAR -> "Clear all active TDE stellar debris and reset counters";
            case ACT_TDE_ARCH_0 -> "Solar G2V (5,800K): balanced golden core & twin tidal tails";
            case ACT_TDE_ARCH_1 -> "Red Supergiant (3,400K): huge envelope shredding early";
            case ACT_TDE_ARCH_2 -> "Blue O-Type (24,000K): blinding sapphire periapsis shocks";
            case ACT_TDE_ARCH_3 -> "White Dwarf (16,000K): dense core detonating near photon ring";
            case SLIDER_TDE_BETA -> "beta = r_t/r_p: low = partial core survival, high = deep plunge";
            case SLIDER_TDE_INC -> "Orbital tilt: 0 deg hits disk, +/-65 deg arcs over the poles";
            case SLIDER_TIME_SCALE -> "Global physics-safe timelapse (0.05x slow-mo to 25x)";
            case SLIDER_SPIN -> "Kerr dimensionless spin a* = J/M^2 (frame-dragging & ISCO)";
            case SLIDER_PLUNGE_SPEED -> "Proper-time pacing multiplier during Doran horizon plunges";
            case SLIDER_TEMP -> "Accretion disk Planckian blackbody temperature multiplier";
            case SLIDER_DENSITY -> "Volumetric accretion plasma optical thickness";
            case SLIDER_JET_POWER -> "Relativistic collimated polar synchrotron jet intensity";
            case SLIDER_BIN_MASS_RATIO -> "Binary mass ratio q = M2/M1 (sets remnant mass & spin a_f)";
            case SLIDER_WH_RHO -> "Wormhole throat radius rho in geometric units";
            case SLIDER_WH_A -> "Wormhole throat half-length a separating Universe A & B";
            case SLIDER_WH_M -> "Wormhole lensing curvature transition width M_w";
            case SLIDER_BLOOM -> "5-level Karis-averaged HDR mipmap bloom intensity";
            case SLIDER_ANAMORPHIC -> "Horizontal cinema anamorphic lens streak strength";
            case SLIDER_EXPOSURE -> "ACES filmic tone-mapping exposure multiplier";
            case SLIDER_FOV -> "Camera vertical field of view (degrees)";
            case SLIDER_VOLUME -> "Master procedural OpenAL 44.1 kHz soundscape volume";
            case ACT_TIME_SLOWER -> "Step global time scale slower ([-] or [,])";
            case ACT_TIME_FASTER -> "Step global time scale faster ([=] or [.])";
            case ACT_TIME_RESET -> "Click or press [0] to snap time scale back to 1.0x";
            case ACT_TIME_025 -> "Jump to 0.25x slow-motion inspection speed";
            case ACT_TIME_1 -> "Jump to 1.0x real-time simulation speed";
            case ACT_TIME_5 -> "Jump to 5.0x smooth timelapse";
            case ACT_TIME_15 -> "Jump to 15.0x rapid debris circularization timelapse";
            case ACT_PAUSE -> "Pause or resume physics without resetting time scale [Space]";
            case ACT_LENSING -> "Toggle exact curved GR geodesics vs flat Euclidean rays";
            case ACT_DOPPLER -> "Toggle relativistic Doppler beaming and gravitational redshift";
            case ACT_KERR_SPIN -> "Toggle rotating Kerr metric vs Schwarzschild (a* = 0)";
            case ACT_FREE_FALL -> "Switch static shell tetrad vs Doran free-fall observer";
            case ACT_ORDER_VIS -> "Luminet geodesic image-order diagnostic (n=0,1,2+)";
            case ACT_ADAPTIVE_AA -> "Toggle 16-phase Halton Temporal Anti-Aliasing (TAA)";
            case ACT_BINARY_GW_OVERLAY -> "Toggle orbital-plane quadrupole GW ripple contours [G]";
            case ACT_BINARY_REPLAY -> "Restart binary inspiral from separation a_0 = 9.0 M";
            case ACT_TOGGLE_PANEL -> "Collapse or expand the right Observatory Console";
            default -> "Hover control for notes \u2022 [H] Cycle HUD";
        };
    }

    private static void appendCompactScale(StringBuilder dst, float val) {
        if (val >= 9.95f) {
            dst.append(Math.round(val));
        } else if (val >= 0.99f && Math.abs(val - Math.round(val)) < 0.04f) {
            dst.append(Math.round(val)).append(".0");
        } else if (val < 1.0f) {
            appendFixed2(dst, val);
        } else {
            appendFixed1(dst, val);
        }
    }

    private static void appendFixed1(StringBuilder dst, float val) {
        if (val < 0f) {
            dst.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 10f);
        dst.append(scaled / 10).append('.').append(scaled % 10);
    }

    private static void appendFixed2(StringBuilder dst, float val) {
        if (val < 0f) {
            dst.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 100f);
        int frac = scaled % 100;
        dst.append(scaled / 100).append('.');
        if (frac < 10) dst.append('0');
        dst.append(frac);
    }

    private static void appendFixed3(StringBuilder dst, float val) {
        if (val < 0f) {
            dst.append('-');
            val = -val;
        }
        int scaled = Math.round(val * 1000f);
        int frac = scaled % 1000;
        dst.append(scaled / 1000).append('.');
        if (frac < 100) dst.append('0');
        if (frac < 10) dst.append('0');
        dst.append(frac);
    }
}
