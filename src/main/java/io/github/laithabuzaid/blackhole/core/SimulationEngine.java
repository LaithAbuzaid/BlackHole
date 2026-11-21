package io.github.laithabuzaid.blackhole.core;

import io.github.laithabuzaid.blackhole.audio.RelativisticAudioEngine;
import io.github.laithabuzaid.blackhole.graphics.BlackHoleRenderer;
import io.github.laithabuzaid.blackhole.graphics.GpuTimerQueries;
import io.github.laithabuzaid.blackhole.graphics.ShaderProgram;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;
import io.github.laithabuzaid.blackhole.ui.HudOverlay;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;
import io.github.laithabuzaid.blackhole.util.PngWriter;
import io.github.laithabuzaid.blackhole.util.SettingsStore;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.InflaterInputStream;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main GLFW / OpenGL 4.3 Core simulation lifecycle, Cinema-First attract tour
 * handoff, Doran plunge controls, zero-dependency PNG capture ({@code [F12]} &
 * {@code --capture-showcase}), procedural OpenAL audio synchronization, and
 * zero-allocation real-time render loop.
 */
public final class SimulationEngine {

    private static final int SHOWCASE_WIDTH = 1920;
    private static final int SHOWCASE_HEIGHT = 1080;
    private static final DateTimeFormatter SNAPSHOT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final boolean captureShowcaseMode;

    private int windowW = 1440;
    private int windowH = 860;
    private int windowedX = 80;
    private int windowedY = 60;
    private int windowedW = 1440;
    private int windowedH = 860;
    private final int[] winPosXBuf = new int[1];
    private final int[] winPosYBuf = new int[1];
    private final int[] winSizeWBuf = new int[1];
    private final int[] winSizeHBuf = new int[1];
    private boolean framebufferResized = false;

    private final SimulationState state = new SimulationState();
    private final CameraController camera = new CameraController();
    private final BinaryMergerPhysics binaryPhysics = new BinaryMergerPhysics();
    private final HardwareTelemetry telemetry = new HardwareTelemetry();
    private final HudOverlay hud = new HudOverlay();
    private final RelativisticAudioEngine audio = new RelativisticAudioEngine();

    private boolean draggingCamera = false;
    private boolean mouseDown = false;
    private boolean keyUpHeld = false;
    private boolean keyDownHeld = false;
    private boolean pendingSpawnStar = false;
    private boolean pendingClearStars = false;
    private boolean pendingScreenshot = false;

    private double anchorX;
    private double anchorY;
    private double mouseX;
    private double mouseY;
    private final double[] cursorXBuf = new double[1];
    private final double[] cursorYBuf = new double[1];
    private final StringBuilder toastSb = new StringBuilder(64);

    private long window = NULL;

    public SimulationEngine() {
        this(false);
    }

    public SimulationEngine(boolean captureShowcaseMode) {
        this.captureShowcaseMode = captureShowcaseMode;
    }

    public void run() {
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, captureShowcaseMode ? GLFW_FALSE : GLFW_TRUE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

            int initW = captureShowcaseMode ? 640 : windowW;
            int initH = captureShowcaseMode ? 360 : windowH;
            window = glfwCreateWindow(initW, initH,
                    "BlackHole v1.1 — Relativistic Kerr-Schild, Binary Merger, Wormhole & TDE Engine", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW OpenGL 4.3 Core window");
            }

            if (!captureShowcaseMode) {
                SettingsStore.load(state);
                applyWindowIcon(window);
                setupCallbacks(window);
            }

            glfwMakeContextCurrent(window);
            glfwSwapInterval(captureShowcaseMode ? 0 : (state.vsync ? 1 : 0));
            if (!captureShowcaseMode) {
                glfwShowWindow(window);
            }

            GL.createCapabilities();

            String glRenderer = glGetString(GL_RENDERER);
            String glVersion = glGetString(GL_VERSION);
            telemetry.initOpenGlInfo(glRenderer, glVersion);

            if (captureShowcaseMode) {
                runShowcaseCapture(glRenderer, glVersion);
                return;
            }

            telemetry.startBackgroundSampler();
            audio.start();

            try (BlackHoleRenderer renderer = new BlackHoleRenderer(windowW, windowH);
                 UiRenderer uiRenderer = new UiRenderer();
                 GpuTimerQueries gpuTimers = new GpuTimerQueries()) {

                ShaderProgram.checkGlError("SimulationEngine.startup");
                System.out.println("[BlackHole] OpenGL " + glVersion + " | GPU: " + glRenderer
                        + " | Cinema Attract Mode, Anamorphic Glare, Compute TDE & Dual Cubemaps validated.");

                runRenderLoop(renderer, uiRenderer, gpuTimers);
            }
        } finally {
            if (!captureShowcaseMode) {
                SettingsStore.save(state);
            }
            audio.close();
            telemetry.stop();
            if (window != NULL) {
                glfwFreeCallbacks(window);
                glfwDestroyWindow(window);
                window = NULL;
            }
            glfwTerminate();
            errorCallback.free();
        }
    }

    /** Zero-allocation real-time render loop. */
    private void runRenderLoop(BlackHoleRenderer renderer, UiRenderer uiRenderer, GpuTimerQueries gpuTimers) {
        long lastFrameNano = System.nanoTime();
        long fpsTimerNano = lastFrameNano;
        int frameCounter = 0;

        while (!glfwWindowShouldClose(window)) {
            long frameStartNano = System.nanoTime();

            boolean resizedThisFrame = false;
            if (framebufferResized) {
                renderer.resize(windowW, windowH);
                framebufferResized = false;
                resizedThisFrame = true;
            }

            if (pendingClearStars) {
                renderer.clearTdeStars();
                pendingClearStars = false;
            }
            if (pendingSpawnStar) {
                renderer.spawnTdeStar(state);
                pendingSpawnStar = false;
            }

            long elapsedNano = frameStartNano - lastFrameNano;
            double wallDt = Math.min(elapsedNano * 1e-9, 0.05);
            double simDt = state.effectiveSimDt(wallDt);
            float rawWallMs = elapsedNano * 1e-6f;
            telemetry.wallFrameMs = telemetry.wallFrameMs * 0.88f + rawWallMs * 0.12f;
            lastFrameNano = frameStartNano;

            boolean telemetryTick = resizedThisFrame;
            frameCounter++;
            if (frameStartNano - fpsTimerNano >= 250_000_000L) {
                telemetry.currentFps = (float) (frameCounter * 1_000_000_000.0 / (frameStartNano - fpsTimerNano));
                frameCounter = 0;
                fpsTimerNano = frameStartNano;
                telemetry.pollOpenGlVram();
                telemetryTick = true;
            }

            if (gpuTimers.pollPreviousFrame()) {
                telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeMs(),
                        gpuTimers.bloomMs(), gpuTimers.uiMs());
            }

            if (!state.paused) {
                state.simTime += simDt;
                if (state.binaryMode) {
                    binaryPhysics.step(wallDt, state.binaryMassRatio, state.timeScale, state.binaryAutoLoop);
                }
            }
            state.tickUiTimers(wallDt);
            camera.update(wallDt, state, keyUpHeld, keyDownHeld, renderer.tdeShearEnergy(), true);

            if (camera.consumeAttractSpawnTrigger() && !state.wormholeMode && !state.binaryMode) {
                renderer.spawnTdeStar(state);
                toastSb.setLength(0);
                toastSb.append("STAR LAUNCHED: ").append(state.currentTdeArchetype().displayName);
                state.showToast(toastSb);
            }

            // Passes 1..3: Kerr-Schild / Binary Merger / Wormhole Raytrace, Compute TDE, TAA, Bloom, Anamorphic ACES
            renderer.renderFrame(windowW, windowH, wallDt, simDt, state, camera, binaryPhysics, gpuTimers, 0);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
            telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
            telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
            telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
            audio.updatePhysics(state, camera, renderer.tdeShearEnergy(), binaryPhysics);

            // [F12] Live HUD-free PNG capture read directly from clean post-composite backbuffer (zero flicker)
            if (pendingScreenshot) {
                pendingScreenshot = false;
                captureLiveSnapshotAsync(windowW, windowH);
            }

            // Pass 4: Cinema Bottom Pill, Toasts, or Full Glassmorphic Profiler & Controls HUD
            gpuTimers.beginUi();
            hud.render(uiRenderer, windowW, windowH, mouseX, mouseY, telemetryTick, false, state, camera, binaryPhysics, telemetry);
            gpuTimers.endUi();

            float rawCpuSubmitMs = (System.nanoTime() - frameStartNano) * 1e-6f;
            telemetry.recordFrameSample(rawCpuSubmitMs);

            gpuTimers.advanceFrame();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    /** Captures a clean HUD-free RGB snapshot from framebuffer 0 and writes PNG asynchronously. */
    private void captureLiveSnapshotAsync(int width, int height) {
        byte[] rgb = readFramebufferRgb(0, width, height);
        String filename = "blackhole_" + SNAPSHOT_FMT.format(LocalDateTime.now()) + ".png";
        Path captureDir = resolveCaptureDirectory();
        Path outPath = captureDir.resolve(filename);
        String displayRel = captureDir.isAbsolute()
                ? ("~/Pictures/BlackHole/" + filename)
                : ("captures/" + filename);

        Thread writer = new Thread(() -> {
            try {
                PngWriter.writeRgbPng(outPath, width, height, rgb);
                state.showSavedToast(displayRel);
                System.out.println("[Capture] Saved " + width + "x" + height + " PNG -> " + outPath);
            } catch (IOException e) {
                state.showToast("CAPTURE FAILED: " + e.getMessage());
                System.err.println("[Capture] Failed to write PNG: " + e.getMessage());
            }
        }, "png-snapshot-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * Resolves a guaranteed-writable screenshot directory: uses {@code ~/Pictures/BlackHole}
     * when launched from a packaged installer/AppImage or read-only working directory, and
     * local {@code captures/} during repository development.
     */
    private static Path resolveCaptureDirectory() {
        boolean packaged = System.getProperty("jpackage.app-path") != null
                || System.getenv("APPIMAGE") != null;
        Path cwd = Path.of("").toAbsolutePath();
        if (packaged || !Files.isWritable(cwd)) {
            String home = System.getProperty("user.home", ".");
            return Path.of(home, "Pictures", "BlackHole");
        }
        return Path.of("captures");
    }

    /**
     * Automated {@code --capture-showcase} generator rendering 6 pixel-exact 1920x1080 PNGs
     * into {@code docs/media/} via an offscreen RGBA8 capture FBO, then exiting cleanly.
     */
    private void runShowcaseCapture(String glRenderer, String glVersion) {
        int w = SHOWCASE_WIDTH;
        int h = SHOWCASE_HEIGHT;
        System.out.println("[Showcase] OpenGL " + glVersion + " | GPU: " + glRenderer
                + " | Rendering 7 curated " + w + "x" + h + " showcase PNGs into docs/media/...");

        telemetry.startBackgroundSampler();
        int captureFbo = glGenFramebuffers();
        int captureTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, captureTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, captureTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Showcase 1080p capture FBO incomplete");
        }

        try (BlackHoleRenderer renderer = new BlackHoleRenderer(w, h);
             UiRenderer uiRenderer = new UiRenderer();
             GpuTimerQueries gpuTimers = new GpuTimerQueries()) {

            ShaderProgram.checkGlError("Showcase.init");

            // 1. hero_kerr_edge.png — Gargantua-style edge-on Kerr black hole (a* = 0.96, Doppler + anamorphic flare)
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = false;
            state.spinParam = 0.96f;
            state.diskTempScale = 1.02f;
            state.diskDensity = 1.08f;
            state.jetPower = 0.55f;
            state.bloomStrength = 0.42f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.72f;
            state.exposure = 1.18f;
            state.fovDeg = 22.0f;
            state.simTime = 4.2;
            camera.setShowcasePose(34.0, 0.28, 0.145, -0.17, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/hero_kerr_edge.png"));

            // 2. tde_shredding.png — Upgraded TDE Lab: Solar G2V (beta=1.85, +24 deg) bound fallback torus + unbound tail
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = false;
            state.spinParam = 0.96f;
            state.tdeArchetypeIndex = 0; // Solar G2V
            state.tdeBeta = 1.85f;
            state.tdeInclinationDeg = 24.0f;
            state.diskDensity = 0.44f;
            state.jetPower = 0.0f;
            state.bloomStrength = 0.44f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.44f;
            state.exposure = 1.16f;
            state.fovDeg = 24.0f;
            state.simTime = 8.5;
            camera.setShowcasePose(30.0, 0.85, 0.48, -0.08, 0.0, 0.0, state);
            renderer.clearTdeStars();
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(760, 1.0 / 60.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/tde_shredding.png"));

            // Benchmark 10x TDE timelapse substepping at 1920x1080 on RTX 4060
            state.setTimeScale(10.0f);
            renderer.resetTaaHistory();
            float tde10xRay = 0f, tde10xTde = 0f, tde10xBlm = 0f;
            for (int f = 0; f < 24; f++) {
                double wDt = 1.0 / 120.0;
                double sDt = state.effectiveSimDt(wDt);
                state.simTime += sDt;
                renderer.renderFrame(w, h, wDt, sDt, state, camera, binaryPhysics, gpuTimers, captureFbo);
                gpuTimers.beginUi();
                gpuTimers.endUi();
                glFinish();
                if (gpuTimers.pollPreviousFrame() && f >= 8) {
                    tde10xRay = gpuTimers.rayMs();
                    tde10xTde = gpuTimers.tdeMs();
                    tde10xBlm = gpuTimers.bloomMs();
                }
                gpuTimers.advanceFrame();
            }
            float tde10xTot = tde10xRay + tde10xTde + tde10xBlm;
            System.out.printf("[Showcase] TDE 10x Timelapse 1080p TAA GPU timing: RAY=%.2f ms, TDE=%.2f ms, BLM=%.2f ms, TOTAL=%.2f ms (%.1f FPS)%n",
                    tde10xRay, tde10xTde, tde10xBlm, tde10xTot, 1000.0f / Math.max(0.1f, tde10xTot));

            // 3. wormhole_throat.png — Morris-Thorne wormhole showing Universe B nebula & ringed planet ghost arcs
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = true;
            state.wormholeRho = 2.60f;
            state.wormholeA = 0.45f;
            state.wormholeM = 1.25f;
            state.bloomStrength = 0.34f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.26f;
            state.exposure = 1.20f;
            state.fovDeg = 22.5f;
            camera.setShowcaseWormholePose(9.4, 0.06, 0.04, 0.0);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/wormhole_throat.png"));

            // 4. horizon_plunge.png — Interior Kerr plunge looking back at blueshifted exterior sky & disk ring
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = false;
            state.spinParam = 0.98f;
            state.plungeActive = true;
            state.freeFallMode = true;
            state.plungeHorizonCrossed = true;
            state.plungeVelocityC = 1.01f;
            state.plungeTidalGrad = 2.15f;
            state.plungeZone = SimulationState.ZONE_INTERIOR;
            state.cauchyFlashIntensity = 0.22f;
            state.bloomStrength = 0.36f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.42f;
            state.exposure = 1.08f;
            state.fovDeg = 26.0f;
            state.simTime = 7.5;
            renderer.clearTdeStars();
            camera.setShowcasePose(1.02, 0.42, 0.14, -0.06, 1.85, -0.08, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/horizon_plunge.png"));

            // 5. hud_telemetry.png — Modular Glassmorphic Observatory HUD (TDE LAB Tab + Timelapse Dock + Live Counters)
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_FULL);
            state.activeConsoleTab = SimulationState.TAB_TDE_LAB;
            state.controlsExpanded = true;
            state.wormholeMode = false;
            state.spinParam = 0.98f;
            state.tdeArchetypeIndex = 2; // Blue O-Type showcase in TDE Lab
            state.tdeBeta = 2.05f;
            state.tdeInclinationDeg = 28.0f;
            state.setTimeScale(1.00f);
            state.jetPower = 0.55f;
            state.bloomStrength = 0.40f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.56f;
            state.exposure = 1.16f;
            state.fovDeg = 22.5f;
            state.simTime = 6.0;
            camera.setShowcasePose(35.0, 0.34, 0.19, -0.14, 0.0, 0.0, state);
            renderer.clearTdeStars();
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(560, 1.0 / 60.0, state);
            // Warm up 1x TAA + hardware GPU timers + full 120-frame oscilloscope trace
            renderer.resetTaaHistory();
            for (int f = 0; f < 135; f++) {
                long t0 = System.nanoTime();
                double wDt = 1.0 / 120.0;
                double sDt = state.effectiveSimDt(wDt);
                state.simTime += sDt;
                renderer.renderFrame(w, h, wDt, sDt, state, camera, binaryPhysics, gpuTimers, captureFbo);
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, w, h);
                gpuTimers.beginUi();
                hud.render(uiRenderer, w, h, -10.0, -10.0, false, false, state, camera, binaryPhysics, telemetry);
                gpuTimers.endUi();
                float submitMs = (System.nanoTime() - t0) * 1e-6f;
                if (f >= 12) {
                    glFinish();
                    if (gpuTimers.pollPreviousFrame()) {
                        telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeMs(),
                                gpuTimers.bloomMs(), gpuTimers.uiMs());
                    }
                    telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
                    telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
                    telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
                    telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
                    telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
                    telemetry.pollOpenGlVram();
                    telemetry.wallFrameMs = Math.max(1.0f, telemetry.gpuTotalMs > 0.1f ? telemetry.gpuTotalMs : submitMs);
                    telemetry.currentFps = 1000.0f / Math.max(1.0f, telemetry.wallFrameMs);
                    telemetry.recordFrameSample(submitMs);
                }
                gpuTimers.advanceFrame();
            }
            System.out.printf("[Showcase] Single Kerr + TDE 1x 1080p TAA GPU timing: RAY=%.2f ms, TDE=%.2f ms, BLM=%.2f ms, UI=%.2f ms, TOTAL=%.2f ms (%.1f FPS)%n",
                    telemetry.gpuRayMs, telemetry.gpuTdeMs, telemetry.gpuBloomMs, telemetry.gpuUiMs,
                    telemetry.gpuTotalMs, 1000.0f / Math.max(0.1f, telemetry.gpuTotalMs));
            // Render final crisp HUD frame with 5.0x Timelapse badge active for showcase clarity
            state.setTimeScale(5.0f);
            renderer.renderFrame(w, h, 1.0 / 120.0, 0.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
            telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
            telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
            telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
            glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
            glViewport(0, 0, w, h);
            gpuTimers.beginUi();
            hud.render(uiRenderer, w, h, -10.0, -10.0, true, false, state, camera, binaryPhysics, telemetry);
            gpuTimers.endUi();
            gpuTimers.advanceFrame();
            saveCaptureFbo(captureFbo, w, h, Path.of("docs/media/hud_telemetry.png"));

            // 6. binary_merger.png — Late inspiral / pre-coalescence binary black holes with mutual eyebrow lensing & shock bridge
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = false;
            state.binaryMode = true;
            state.binaryMassRatio = 0.65f;
            state.setTimeScale(1.00f);
            state.binaryGwOverlay = true;
            state.diskTempScale = 1.00f;
            state.diskDensity = 1.08f;
            state.bloomStrength = 0.25f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.22f;
            state.exposure = 1.12f;
            state.fovDeg = 23.0f;
            binaryPhysics.advanceToSeparation(state.binaryMassRatio, 5.25);
            double broadsideYaw = Math.atan2(binaryPhysics.pos1X - binaryPhysics.pos2X,
                    binaryPhysics.pos1Z - binaryPhysics.pos2Z) + Math.PI * 0.5 - 0.14;
            camera.setShowcasePose(28.5, broadsideYaw, 0.38, -0.07, 0.0, 0.0, state);
            // Benchmark live 1080p Binary Merger GPU frame time with TAA active
            renderer.resetTaaHistory();
            float binRayMs = 0f, binBlmMs = 0f;
            for (int f = 0; f < 28; f++) {
                renderer.renderFrame(w, h, 1.0 / 120.0, 1.0 / 120.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
                gpuTimers.beginUi();
                gpuTimers.endUi();
                glFinish();
                if (gpuTimers.pollPreviousFrame() && f >= 8) {
                    binRayMs = gpuTimers.rayMs();
                    binBlmMs = gpuTimers.bloomMs();
                }
                gpuTimers.advanceFrame();
            }
            float binTotalMs = binRayMs + binBlmMs;
            System.out.printf("[Showcase] Binary Merger 1080p TAA GPU timing: RAY=%.2f ms, TAA+BLM=%.2f ms, TOTAL=%.2f ms (%.1f FPS)%n",
                    binRayMs, binBlmMs, binTotalMs, 1000.0f / Math.max(0.1f, binTotalMs));
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/binary_merger.png"));

            // 7. eht_polarization.png — Event Horizon Telescope 230 GHz VLBI + Kerr-dragged Polarized B-Field Swirl
            state.resetDefaults();
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = false;
            state.binaryMode = false;
            state.spinParam = 0.94f;
            state.ehtRadioPalette = true;
            state.ehtPolarization = true;
            state.diskDensity = 1.18f;
            state.jetPower = 0.25f;
            state.bloomStrength = 0.38f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.26f;
            state.exposure = 1.20f;
            state.fovDeg = 25.5f;
            state.simTime = 3.6;
            renderer.clearTdeStars();
            camera.setShowcasePose(31.5, 0.35, 0.60, -0.05, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/eht_polarization.png"));

            ShaderProgram.checkGlError("Showcase.finish");
            System.out.println("[Showcase] All 7 1920x1080 showcase PNGs saved successfully.");
        } catch (IOException e) {
            throw new RuntimeException("Failed writing showcase PNGs", e);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteTextures(captureTex);
            glDeleteFramebuffers(captureFbo);
        }
    }

    private void renderAndSaveShowcase(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                       GpuTimerQueries gpuTimers, int captureFbo,
                                       int w, int h, boolean withHud, Path outPath) throws IOException {
        // Converge 16 Halton(2,3) TAA sub-pixel jitter samples on the frozen showcase frame
        renderer.resetTaaHistory();
        for (int s = 0; s < 16; s++) {
            renderer.renderFrame(w, h, 0.0, 0.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
            gpuTimers.beginUi();
            if (withHud && s == 15) {
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, w, h);
                hud.render(uiRenderer, w, h, -10.0, -10.0, true, false, state, camera, binaryPhysics, telemetry);
            }
            gpuTimers.endUi();
            gpuTimers.advanceFrame();
        }
        saveCaptureFbo(captureFbo, w, h, outPath);
    }

    private static void saveCaptureFbo(int fbo, int width, int height, Path outPath) throws IOException {
        glFinish();
        byte[] rgb = readFramebufferRgb(fbo, width, height);
        PngWriter.writeRgbPng(outPath, width, height, rgb);
        long bytes = Files.size(outPath);
        System.out.println("[Showcase] Wrote " + outPath + " (" + width + "x" + height + ", " + (bytes / 1024) + " KB)");
    }

    private static byte[] readFramebufferRgb(int fbo, int width, int height) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        if (fbo == 0) {
            glReadBuffer(GL_BACK);
        }
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        int byteCount = width * height * 3;
        ByteBuffer nativeBuf = MemoryUtil.memAlloc(byteCount);
        try {
            glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, nativeBuf);
            byte[] rgb = new byte[byteCount];
            nativeBuf.get(rgb);
            return rgb;
        } finally {
            MemoryUtil.memFree(nativeBuf);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
    }

    private void triggerAction(int actionId) {
        switch (actionId) {
            case HudOverlay.ACT_ATTRACT_TOUR -> {
                state.binaryMode = false;
                camera.toggleAttractTour(state);
                state.showToast(state.attractTourActive
                        ? "DIRECTOR'S CUT TOUR: ENGAGED"
                        : "DIRECTOR'S CUT TOUR: OFF");
            }
            case HudOverlay.ACT_PLUNGE -> {
                camera.disengageAttractTour(state);
                state.binaryMode = false;
                if (state.plungeActive && !state.plungeEnded) {
                    camera.abortPlunge(state);
                    state.showToast("PLUNGE DIVE: ABORTED");
                } else {
                    camera.startPlunge(state);
                    state.showToast("PLUNGE DIVE: CROSSING EVENT HORIZON");
                }
            }
            case HudOverlay.ACT_BINARY -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.wormholeMode = false;
                state.binaryMode = !state.binaryMode;
                if (state.binaryMode) {
                    binaryPhysics.reset(state.binaryMassRatio);
                    camera.applyPresetBinaryOblique();
                    state.showToast("SPACETIME: BINARY BLACK HOLE MERGER");
                } else {
                    camera.applyPresetGargantuaEdge();
                    state.showToast("SPACETIME: KERR-SCHILD BLACK HOLE");
                }
            }
            case HudOverlay.ACT_BINARY_REPLAY -> {
                if (state.binaryMode) {
                    binaryPhysics.reset(state.binaryMassRatio);
                    state.paused = false;
                    state.showToast("BINARY MERGER: REPLAYING INSPIRAL");
                }
            }
            case HudOverlay.ACT_BINARY_GW_OVERLAY -> {
                state.binaryGwOverlay = !state.binaryGwOverlay;
                state.showToast(state.binaryGwOverlay ? "GW STRAIN OVERLAY: ON" : "GW STRAIN OVERLAY: OFF");
            }
            case HudOverlay.ACT_BINARY_AUTOLOOP -> {
                state.binaryAutoLoop = !state.binaryAutoLoop;
                state.showToast(state.binaryAutoLoop ? "MERGER AUTO-LOOP: ON" : "MERGER AUTO-LOOP: OFF");
            }
            case HudOverlay.ACT_WORMHOLE -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.binaryMode = false;
                state.wormholeMode = !state.wormholeMode;
                if (state.wormholeMode) {
                    camera.applyWormholeUniverseA();
                    state.showToast("SPACETIME: MORRIS-THORNE WORMHOLE");
                } else {
                    camera.applyPresetGargantuaEdge();
                    state.showToast("SPACETIME: KERR-SCHILD BLACK HOLE");
                }
            }
            case HudOverlay.ACT_MODE_KERR -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.wormholeMode = false;
                state.binaryMode = false;
                camera.applyPresetGargantuaEdge();
                state.showToast("SPACETIME: KERR-SCHILD BLACK HOLE");
            }
            case HudOverlay.ACT_TAB_SCENE -> state.activeConsoleTab = SimulationState.TAB_SCENE;
            case HudOverlay.ACT_TAB_PHYSICS -> state.activeConsoleTab = SimulationState.TAB_PHYSICS;
            case HudOverlay.ACT_TAB_TDE_LAB -> state.activeConsoleTab = SimulationState.TAB_TDE_LAB;
            case HudOverlay.ACT_TAB_OPTICS -> state.activeConsoleTab = SimulationState.TAB_OPTICS;
            case HudOverlay.ACT_TIME_SLOWER -> {
                state.stepTimeScaleDown();
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_FASTER -> {
                state.stepTimeScaleUp();
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_RESET -> {
                state.setTimeScale(1.0f);
                state.paused = false;
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_025 -> {
                state.setTimeScale(0.25f);
                state.paused = false;
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_1 -> {
                state.setTimeScale(1.0f);
                state.paused = false;
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_5 -> {
                state.setTimeScale(5.0f);
                state.paused = false;
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TIME_15 -> {
                state.setTimeScale(15.0f);
                state.paused = false;
                showTimeScaleToast();
            }
            case HudOverlay.ACT_TDE_ARCH_0, HudOverlay.ACT_TDE_ARCH_1,
                 HudOverlay.ACT_TDE_ARCH_2, HudOverlay.ACT_TDE_ARCH_3 -> {
                state.tdeArchetypeIndex = actionId - HudOverlay.ACT_TDE_ARCH_0;
                toastSb.setLength(0);
                toastSb.append("TDE STAR: ").append(state.currentTdeArchetype().displayName);
                state.showToast(toastSb);
            }
            case HudOverlay.ACT_TDE_CYCLE_ARCH -> {
                state.cycleTdeArchetype();
                toastSb.setLength(0);
                toastSb.append("TDE STAR: ").append(state.currentTdeArchetype().displayName);
                state.showToast(toastSb);
            }
            case HudOverlay.ACT_TDE_CLEAR -> {
                pendingClearStars = true;
                state.showToast("TDE DEBRIS CLEARED");
            }
            case HudOverlay.ACT_SHRED_STAR -> {
                if (!state.wormholeMode && !state.binaryMode) {
                    pendingSpawnStar = true;
                    toastSb.setLength(0);
                    toastSb.append("LAUNCHED: ").append(state.currentTdeArchetype().displayName);
                    state.showToast(toastSb);
                } else {
                    state.showToast("SWITCH TO KERR BH FOR TDE LAB");
                }
            }
            case HudOverlay.ACT_FLY_THROUGH -> {
                if (state.wormholeMode) {
                    camera.disengageAttractTour(state);
                    state.autoOrbit = false;
                    camera.triggerWormholeFlyThrough();
                    state.showToast("WORMHOLE: TRAVERSING THROAT");
                }
            }
            case HudOverlay.ACT_AUDIO -> {
                state.audioEnabled = !state.audioEnabled;
                state.showToast(state.audioEnabled ? "AUDIO: RELATIVISTIC SYNTH ON" : "AUDIO: MUTED");
            }
            case HudOverlay.ACT_ANAMORPHIC -> {
                state.anamorphicEnabled = !state.anamorphicEnabled;
                state.showToast(state.anamorphicEnabled ? "ANAMORPHIC GLARE: ON" : "ANAMORPHIC GLARE: OFF");
            }
            case HudOverlay.ACT_LENSING -> {
                state.lensingEnabled = !state.lensingEnabled;
                state.showToast(state.lensingEnabled ? "GRAVITATIONAL LENSING: CURVED" : "GRAVITATIONAL LENSING: EUCLIDEAN");
            }
            case HudOverlay.ACT_DISK -> {
                state.showDisk = !state.showDisk;
                state.showToast(state.showDisk ? "ACCRETION DISK: ON" : "ACCRETION DISK: OFF");
            }
            case HudOverlay.ACT_JETS -> {
                state.showJets = !state.showJets;
                state.showToast(state.showJets ? "RELATIVISTIC JETS: ON" : "RELATIVISTIC JETS: OFF");
            }
            case HudOverlay.ACT_DOPPLER -> {
                state.dopplerEnabled = !state.dopplerEnabled;
                state.showToast(state.dopplerEnabled ? "DOPPLER BEAMING: ON" : "DOPPLER BEAMING: OFF");
            }
            case HudOverlay.ACT_KERR_SPIN -> {
                state.kerrSpinEnabled = !state.kerrSpinEnabled;
                state.showToast(state.kerrSpinEnabled ? "METRIC: ROTATING KERR (a*)" : "METRIC: SCHWARZSCHILD (a* = 0)");
            }
            case HudOverlay.ACT_FREE_FALL -> {
                state.freeFallMode = !state.freeFallMode;
                state.showToast(state.freeFallMode ? "OBSERVER: FREE-FALL (DORAN)" : "OBSERVER: STATIC SHELL");
            }
            case HudOverlay.ACT_ADAPTIVE_AA -> {
                state.adaptiveAa4x = !state.adaptiveAa4x;
                state.showToast(state.adaptiveAa4x ? "TEMPORAL AA (TAA): ON" : "TEMPORAL AA (TAA): OFF");
            }
            case HudOverlay.ACT_SKY_GRID -> {
                state.showGrid = !state.showGrid;
                state.showToast(state.showGrid ? "CELESTIAL GRID: ON" : "CELESTIAL GRID: OFF");
            }
            case HudOverlay.ACT_ORDER_VIS -> {
                state.orderVisualizer = !state.orderVisualizer;
                state.showToast(state.orderVisualizer ? "LUMINET IMAGE-ORDER MODE: ON" : "LUMINET IMAGE-ORDER MODE: OFF");
            }
            case HudOverlay.ACT_EHT_POLARIZATION -> {
                state.ehtPolarization = !state.ehtPolarization;
                state.showToast(state.ehtPolarization ? "EHT B-FIELD POLARIZATION: ON" : "EHT B-FIELD POLARIZATION: OFF");
            }
            case HudOverlay.ACT_EHT_RADIO -> {
                state.ehtRadioPalette = !state.ehtRadioPalette;
                state.showToast(state.ehtRadioPalette ? "EHT 230 GHz VLBI PALETTE: ON" : "OPTICAL BLACKBODY PALETTE: ON");
            }
            case HudOverlay.ACT_RETICLE -> {
                state.showReticle = !state.showReticle;
                state.showToast(state.showReticle ? "ASTROPHYSICAL SCALE RETICLE: ON" : "ASTROPHYSICAL SCALE RETICLE: OFF");
            }
            case HudOverlay.ACT_FULLSCREEN -> toggleFullscreen();
            case HudOverlay.ACT_LIGO_GW150914 -> {
                state.binaryMode = true;
                state.wormholeMode = false;
                state.binaryMassRatio = 0.81f;
                binaryPhysics.reset(state.binaryMassRatio);
                state.paused = false;
                state.showToast("LIGO GW150914: 36 + 29 M_sun (q = 0.81)");
            }
            case HudOverlay.ACT_LIGO_GW190412 -> {
                state.binaryMode = true;
                state.wormholeMode = false;
                state.binaryMassRatio = 0.28f;
                binaryPhysics.reset(state.binaryMassRatio);
                state.paused = false;
                state.showToast("LIGO GW190412: 30 + 8.4 M_sun (q = 0.28)");
            }
            case HudOverlay.ACT_LIGO_EQUAL -> {
                state.binaryMode = true;
                state.wormholeMode = false;
                state.binaryMassRatio = 1.00f;
                binaryPhysics.reset(state.binaryMassRatio);
                state.paused = false;
                state.showToast("BINARY MERGER: EQUAL MASS (q = 1.00)");
            }
            case HudOverlay.ACT_AUTO_ORBIT -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = !state.autoOrbit;
                state.showToast(state.autoOrbit ? "AUTO ORBIT: ON" : "AUTO ORBIT: OFF");
            }
            case HudOverlay.ACT_VSYNC -> {
                state.vsync = !state.vsync;
                glfwSwapInterval(state.vsync ? 1 : 0);
                state.showToast(state.vsync ? "V-SYNC: ON" : "V-SYNC: OFF (UNCAPPED)");
            }
            case HudOverlay.ACT_PAUSE -> {
                state.paused = !state.paused;
                state.showToast(state.paused ? "SIMULATION: PAUSED" : "SIMULATION: RESUMED");
            }
            case HudOverlay.ACT_TOGGLE_PANEL -> state.controlsExpanded = !state.controlsExpanded;
            case HudOverlay.PRESET_EDGE -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.autoOrbit = false;
                camera.applyPresetGargantuaEdge();
                state.showToast("PRESET: GARGANTUA EDGE");
            }
            case HudOverlay.PRESET_POLE -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.autoOrbit = false;
                camera.applyPresetPolarVortex();
                state.showToast("PRESET: POLAR VORTEX");
            }
            case HudOverlay.PRESET_RING -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.autoOrbit = false;
                camera.applyPresetPhotonRing();
                state.showToast("PRESET: PHOTON RING");
            }
            case HudOverlay.PRESET_DIVE -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyPresetHorizonDive(state);
                state.showToast("PRESET: HORIZON DIVE (IN r+)");
            }
            case HudOverlay.PRESET_UNIV_A -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyWormholeUniverseA();
                state.showToast("WORMHOLE: UNIVERSE A (MILKY WAY)");
            }
            case HudOverlay.PRESET_THROAT -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyWormholeThroat();
                state.showToast("WORMHOLE: THROAT CENTER (l = 0)");
            }
            case HudOverlay.PRESET_UNIV_B -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyWormholeUniverseB();
                state.showToast("WORMHOLE: UNIVERSE B (NEBULA)");
            }
            case HudOverlay.PRESET_BIN_OBLIQUE -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyPresetBinaryOblique();
                state.showToast("BINARY: OBLIQUE MERGER VIEW");
            }
            case HudOverlay.PRESET_BIN_TOP -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyPresetBinaryTopDown();
                state.showToast("BINARY: TOP-DOWN ORBITAL PLANE");
            }
            case HudOverlay.PRESET_BIN_CLOSE -> {
                camera.disengageAttractTour(state);
                state.autoOrbit = false;
                camera.applyPresetBinaryClose();
                state.showToast("BINARY: CLOSE EYEBROW LENSING");
            }
            case HudOverlay.PRESET_RESET -> {
                camera.disengageAttractTour(state);
                state.resetDefaults();
                pendingClearStars = true;
                if (state.binaryMode) {
                    binaryPhysics.reset(state.binaryMassRatio);
                    camera.applyPresetBinaryOblique();
                } else if (state.wormholeMode) {
                    camera.resetWormholeDefaults();
                } else {
                    camera.applyPresetGargantuaEdge();
                }
                state.showToast("SIMULATION RESET");
            }
            default -> {
            }
        }
    }

    private void setupCallbacks(long winHandle) {
        glfwSetFramebufferSizeCallback(winHandle, (win, w, h) -> {
            if (w > 0 && h > 0) {
                windowW = w;
                windowH = h;
                framebufferResized = true;
            }
        });

        glfwSetMouseButtonCallback(winHandle, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                glfwGetCursorPos(win, cursorXBuf, cursorYBuf);
                mouseX = cursorXBuf[0];
                mouseY = cursorYBuf[0];

                if (action == GLFW_PRESS) {
                    mouseDown = true;
                    int hitAction = hud.handleClick(mouseX, mouseY, state);
                    if (hitAction > 0) {
                        triggerAction(hitAction);
                    } else if (hitAction == 0) {
                        // Instant zero-jump handoff from Attract Tour on first click/drag
                        camera.disengageAttractTour(state);
                        draggingCamera = true;
                        state.autoOrbit = false;
                        anchorX = mouseX;
                        anchorY = mouseY;
                    }
                } else if (action == GLFW_RELEASE) {
                    mouseDown = false;
                    draggingCamera = false;
                    hud.releaseSlider();
                }
            }
        });

        glfwSetCursorPosCallback(winHandle, (win, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;

            if (mouseDown && hud.isDraggingSlider()) {
                hud.handleSliderDrag(xpos, state);
                return;
            }

            if (draggingCamera) {
                camera.orbitBy(xpos - anchorX, ypos - anchorY, state);
                anchorX = xpos;
                anchorY = ypos;
            }
        });

        glfwSetScrollCallback(winHandle, (win, xoffset, yoffset) -> {
            camera.disengageAttractTour(state);
            camera.zoomBy(yoffset, state);
        });

        glfwSetKeyCallback(winHandle, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_UP) {
                if (action == GLFW_PRESS) {
                    camera.disengageAttractTour(state);
                }
                keyUpHeld = (action != GLFW_RELEASE);
                return;
            }
            if (key == GLFW_KEY_DOWN) {
                if (action == GLFW_PRESS) {
                    camera.disengageAttractTour(state);
                }
                keyDownHeld = (action != GLFW_RELEASE);
                return;
            }
            if (action != GLFW_PRESS) {
                return;
            }
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true);
                case GLFW_KEY_H -> {
                    state.cycleHudMode();
                    String msg = switch (state.hudMode) {
                        case SimulationState.HUD_COMPACT -> "HUD: COMPACT DOCK";
                        case SimulationState.HUD_FULL -> "HUD: FULL OBSERVATORY";
                        default -> "HUD: CINEMA MODE";
                    };
                    state.showToast(msg);
                }
                case GLFW_KEY_MINUS, GLFW_KEY_COMMA -> triggerAction(HudOverlay.ACT_TIME_SLOWER);
                case GLFW_KEY_EQUAL, GLFW_KEY_PERIOD -> triggerAction(HudOverlay.ACT_TIME_FASTER);
                case GLFW_KEY_0 -> triggerAction(HudOverlay.ACT_TIME_RESET);
                case GLFW_KEY_T -> triggerAction(HudOverlay.ACT_ATTRACT_TOUR);
                case GLFW_KEY_F12, GLFW_KEY_S -> pendingScreenshot = true;
                case GLFW_KEY_P -> triggerAction(HudOverlay.ACT_PLUNGE);
                case GLFW_KEY_W -> triggerAction(HudOverlay.ACT_WORMHOLE);
                case GLFW_KEY_X -> {
                    if ((mods & GLFW_MOD_SHIFT) != 0 && state.binaryMode) {
                        triggerAction(HudOverlay.ACT_BINARY_REPLAY);
                    } else {
                        triggerAction(HudOverlay.ACT_BINARY);
                    }
                }
                case GLFW_KEY_E -> {
                    if ((mods & GLFW_MOD_SHIFT) != 0) {
                        triggerAction(HudOverlay.ACT_TDE_CYCLE_ARCH);
                    } else {
                        triggerAction(HudOverlay.ACT_SHRED_STAR);
                    }
                }
                case GLFW_KEY_A -> triggerAction(HudOverlay.ACT_AUDIO);
                case GLFW_KEY_1 -> triggerAction(state.binaryMode ? HudOverlay.PRESET_BIN_OBLIQUE
                        : (state.wormholeMode ? HudOverlay.PRESET_UNIV_A : HudOverlay.PRESET_EDGE));
                case GLFW_KEY_2 -> triggerAction(state.binaryMode ? HudOverlay.PRESET_BIN_TOP
                        : (state.wormholeMode ? HudOverlay.PRESET_THROAT : HudOverlay.PRESET_POLE));
                case GLFW_KEY_3 -> triggerAction(state.binaryMode ? HudOverlay.PRESET_BIN_CLOSE
                        : (state.wormholeMode ? HudOverlay.PRESET_UNIV_B : HudOverlay.PRESET_RING));
                case GLFW_KEY_4 -> triggerAction(state.binaryMode ? HudOverlay.ACT_BINARY_REPLAY
                        : (state.wormholeMode ? HudOverlay.ACT_FLY_THROUGH : HudOverlay.PRESET_DIVE));
                case GLFW_KEY_R -> triggerAction(HudOverlay.PRESET_RESET);
                case GLFW_KEY_L -> triggerAction(HudOverlay.ACT_LENSING);
                case GLFW_KEY_D -> triggerAction(HudOverlay.ACT_DISK);
                case GLFW_KEY_J -> triggerAction(HudOverlay.ACT_JETS);
                case GLFW_KEY_B -> triggerAction(HudOverlay.ACT_DOPPLER);
                case GLFW_KEY_K -> triggerAction(HudOverlay.ACT_KERR_SPIN);
                case GLFW_KEY_F -> triggerAction(HudOverlay.ACT_FREE_FALL);
                case GLFW_KEY_M -> triggerAction(HudOverlay.ACT_ADAPTIVE_AA);
                case GLFW_KEY_C -> {
                    if ((mods & GLFW_MOD_SHIFT) != 0) {
                        triggerAction(HudOverlay.ACT_TDE_CLEAR);
                    } else {
                        triggerAction(HudOverlay.ACT_SKY_GRID);
                    }
                }
                case GLFW_KEY_I -> triggerAction(HudOverlay.ACT_ORDER_VIS);
                case GLFW_KEY_N -> {
                    if ((mods & GLFW_MOD_SHIFT) != 0) {
                        triggerAction(HudOverlay.ACT_EHT_RADIO);
                    } else {
                        triggerAction(HudOverlay.ACT_EHT_POLARIZATION);
                    }
                }
                case GLFW_KEY_Q -> triggerAction(HudOverlay.ACT_RETICLE);
                case GLFW_KEY_F11 -> triggerAction(HudOverlay.ACT_FULLSCREEN);
                case GLFW_KEY_ENTER -> {
                    if ((mods & GLFW_MOD_ALT) != 0) {
                        triggerAction(HudOverlay.ACT_FULLSCREEN);
                    }
                }
                case GLFW_KEY_O -> triggerAction(HudOverlay.ACT_AUTO_ORBIT);
                case GLFW_KEY_V -> triggerAction(HudOverlay.ACT_VSYNC);
                case GLFW_KEY_SPACE -> triggerAction(HudOverlay.ACT_PAUSE);
                case GLFW_KEY_U -> {
                    camera.disengageAttractTour(state);
                    camera.toggleCinematicRoll();
                    state.showToast("CAMERA ROLL TOGGLED");
                }
                case GLFW_KEY_G -> {
                    if (state.binaryMode) {
                        triggerAction(HudOverlay.ACT_BINARY_GW_OVERLAY);
                    } else {
                        state.bloomStrength =
                                (state.bloomStrength > 0.45f) ? 0.0f : (state.bloomStrength < 0.1f ? 0.30f : 0.62f);
                        state.showToast(state.bloomStrength < 0.05f ? "BLOOM: OFF"
                                : (state.bloomStrength > 0.45f ? "BLOOM: CINEMATIC" : "BLOOM: BALANCED"));
                    }
                }
                case GLFW_KEY_LEFT_BRACKET -> state.exposure =
                        Math.max(SimulationState.MIN_EXPOSURE, state.exposure - 0.15f);
                case GLFW_KEY_RIGHT_BRACKET -> state.exposure =
                        Math.min(SimulationState.MAX_EXPOSURE, state.exposure + 0.15f);
                default -> {
                }
            }
        });
    }

    private void showTimeScaleToast() {
        toastSb.setLength(0);
        toastSb.append("TIME SCALE: ");
        float ts = state.timeScale;
        if (ts >= 9.95f) {
            toastSb.append(Math.round(ts)).append(".0x");
        } else if (ts < 1.0f) {
            int scaled = Math.round(ts * 100f);
            toastSb.append(scaled / 100).append('.');
            int frac = scaled % 100;
            if (frac < 10) toastSb.append('0');
            toastSb.append(frac).append('x');
        } else {
            int scaled = Math.round(ts * 10f);
            toastSb.append(scaled / 10).append('.').append(scaled % 10).append('x');
        }
        if (ts > 1.02f) {
            toastSb.append(" (TIMELAPSE)");
        } else if (ts < 0.98f) {
            toastSb.append(" (SLOW-MO)");
        } else {
            toastSb.append(" (REALTIME)");
        }
        state.showToast(toastSb);
    }

    private void toggleFullscreen() {
        if (window == NULL || captureShowcaseMode) {
            return;
        }
        long monitor = glfwGetPrimaryMonitor();
        if (monitor == NULL) {
            return;
        }
        if (!state.fullscreen) {
            glfwGetWindowPos(window, winPosXBuf, winPosYBuf);
            glfwGetWindowSize(window, winSizeWBuf, winSizeHBuf);
            windowedX = winPosXBuf[0];
            windowedY = winPosYBuf[0];
            windowedW = Math.max(640, winSizeWBuf[0]);
            windowedH = Math.max(360, winSizeHBuf[0]);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null) {
                glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
                state.fullscreen = true;
                state.showToast("DISPLAY: FULLSCREEN");
            }
        } else {
            glfwSetWindowMonitor(window, NULL, windowedX, windowedY, windowedW, windowedH, GLFW_DONT_CARE);
            state.fullscreen = false;
            state.showToast("DISPLAY: WINDOWED");
        }
    }

    /** Loads the pre-compressed 64x64 RGBA taskbar icon with zero java.desktop dependency. */
    private static void applyWindowIcon(long winHandle) {
        try (InputStream raw = SimulationEngine.class.getResourceAsStream("/icons/blackhole_64.bin")) {
            if (raw == null) {
                return;
            }
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(raw))) {
                int magic = dis.readInt();
                if (magic != 0x42484943) { // 'BHIC'
                    return;
                }
                int w = dis.readInt();
                int h = dis.readInt();
                int expected = w * h * 4;
                byte[] rgba = new byte[expected];
                try (InflaterInputStream iis = new InflaterInputStream(dis)) {
                    int off = 0;
                    while (off < expected) {
                        int n = iis.read(rgba, off, expected - off);
                        if (n < 0) return;
                        off += n;
                    }
                }
                ByteBuffer buf = MemoryUtil.memAlloc(expected);
                GLFWImage.Buffer images = GLFWImage.malloc(1);
                try {
                    buf.put(rgba).flip();
                    images.position(0).width(w).height(h).pixels(buf);
                    glfwSetWindowIcon(winHandle, images);
                } finally {
                    images.free();
                    MemoryUtil.memFree(buf);
                }
            }
        } catch (Throwable ignored) {
            // Wayland compositors ignore glfwSetWindowIcon; safely no-op
        }
    }
}
