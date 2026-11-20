package io.github.laithabuzaid.blackhole.core;

import io.github.laithabuzaid.blackhole.audio.RelativisticAudioEngine;
import io.github.laithabuzaid.blackhole.graphics.BlackHoleRenderer;
import io.github.laithabuzaid.blackhole.graphics.GpuTimerQueries;
import io.github.laithabuzaid.blackhole.graphics.ShaderProgram;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;
import io.github.laithabuzaid.blackhole.ui.HudOverlay;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;
import io.github.laithabuzaid.blackhole.util.PngWriter;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
    private boolean framebufferResized = false;

    private final SimulationState state = new SimulationState();
    private final CameraController camera = new CameraController();
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
                    "Relativistic Kerr-Schild Black Hole & Wormhole Engine — OpenGL 4.3", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW OpenGL 4.3 Core window");
            }

            if (!captureShowcaseMode) {
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
            double dt = Math.min(elapsedNano * 1e-9, 0.05);
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
                state.simTime += dt;
            }
            state.tickUiTimers(dt);
            camera.update(dt, state, keyUpHeld, keyDownHeld, renderer.tdeShearEnergy(), true);

            if (camera.consumeAttractSpawnTrigger() && !state.wormholeMode) {
                renderer.spawnTdeStar(state);
                state.showToast("STAR INJECTED: 65,536 PARTICLES");
            }

            // Passes 1..3: Kerr-Schild / Wormhole Raytrace, Compute TDE + HDR Sprites, Bloom, Anamorphic ACES Composite
            renderer.renderFrame(windowW, windowH, dt, telemetryTick, state, camera, gpuTimers, 0);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            audio.updatePhysics(state, camera, renderer.tdeShearEnergy());

            // [F12] Live HUD-free PNG capture read directly from clean post-composite backbuffer (zero flicker)
            if (pendingScreenshot) {
                pendingScreenshot = false;
                captureLiveSnapshotAsync(windowW, windowH);
            }

            // Pass 4: Cinema Bottom Pill, Toasts, or Full Glassmorphic Profiler & Controls HUD
            gpuTimers.beginUi();
            hud.render(uiRenderer, windowW, windowH, mouseX, mouseY, telemetryTick, false, state, camera, telemetry);
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
        Path outPath = Path.of("captures", filename);
        String displayRel = "captures/" + filename;

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
     * Automated {@code --capture-showcase} generator rendering 5 pixel-exact 1920x1080 PNGs
     * into {@code docs/media/} via an offscreen RGBA8 capture FBO, then exiting cleanly.
     */
    private void runShowcaseCapture(String glRenderer, String glVersion) {
        int w = SHOWCASE_WIDTH;
        int h = SHOWCASE_HEIGHT;
        System.out.println("[Showcase] OpenGL " + glVersion + " | GPU: " + glRenderer
                + " | Rendering 5 curated " + w + "x" + h + " showcase PNGs into docs/media/...");

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
            state.showUi = false;
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

            // 2. tde_shredding.png — Mid-disruption 65,536-particle spaghettified star wrapping around shadow
            state.resetDefaults();
            state.showUi = false;
            state.wormholeMode = false;
            state.spinParam = 0.96f;
            state.diskDensity = 0.50f;
            state.jetPower = 0.0f;
            state.bloomStrength = 0.44f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.48f;
            state.exposure = 1.18f;
            state.fovDeg = 22.5f;
            state.simTime = 8.5;
            camera.setShowcasePose(28.0, 0.72, 0.48, -0.08, 0.0, 0.0, state);
            renderer.clearTdeStars();
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(680, 1.0 / 60.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, w, h, false,
                    Path.of("docs/media/tde_shredding.png"));

            // 3. wormhole_throat.png — Morris-Thorne wormhole showing Universe B nebula & ringed planet ghost arcs
            state.resetDefaults();
            state.showUi = false;
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
            state.showUi = false;
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

            // 5. hud_telemetry.png — Full simulation with glassmorphic GPU/CPU profiler & console expanded
            state.resetDefaults();
            state.showUi = true;
            state.controlsExpanded = true;
            state.wormholeMode = false;
            state.spinParam = 0.98f;
            state.jetPower = 0.55f;
            state.bloomStrength = 0.40f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.60f;
            state.exposure = 1.16f;
            state.fovDeg = 22.5f;
            state.simTime = 6.0;
            camera.setShowcasePose(36.0, 0.28, 0.145, -0.17, 0.0, 0.0, state);
            renderer.clearTdeStars();
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(210, 1.0 / 60.0, state);
            // Warm up TDE + TAA + hardware GPU timers + full 120-frame oscilloscope trace
            renderer.resetTaaHistory();
            for (int f = 0; f < 135; f++) {
                boolean readback = (f == 120);
                long t0 = System.nanoTime();
                state.simTime += 1.0 / 60.0;
                renderer.renderFrame(w, h, 1.0 / 60.0, readback, state, camera, gpuTimers, captureFbo);
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, w, h);
                gpuTimers.beginUi();
                hud.render(uiRenderer, w, h, -10.0, -10.0, false, false, state, camera, telemetry);
                gpuTimers.endUi();
                float submitMs = (System.nanoTime() - t0) * 1e-6f;
                if (f >= 12) {
                    glFinish();
                    if (gpuTimers.pollPreviousFrame()) {
                        telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeMs(),
                                gpuTimers.bloomMs(), gpuTimers.uiMs());
                    }
                    telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
                    telemetry.pollOpenGlVram();
                    telemetry.wallFrameMs = Math.max(1.0f, telemetry.gpuTotalMs > 0.1f ? telemetry.gpuTotalMs : submitMs);
                    telemetry.currentFps = 1000.0f / Math.max(1.0f, telemetry.wallFrameMs);
                    telemetry.recordFrameSample(readback ? telemetry.cpuSubmitMs : submitMs);
                }
                gpuTimers.advanceFrame();
            }
            // Render final crisp HUD frame into captureFbo
            renderer.renderFrame(w, h, 1.0 / 60.0, true, state, camera, gpuTimers, captureFbo);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
            glViewport(0, 0, w, h);
            gpuTimers.beginUi();
            hud.render(uiRenderer, w, h, -10.0, -10.0, true, false, state, camera, telemetry);
            gpuTimers.endUi();
            gpuTimers.advanceFrame();
            saveCaptureFbo(captureFbo, w, h, Path.of("docs/media/hud_telemetry.png"));

            ShaderProgram.checkGlError("Showcase.finish");
            System.out.println("[Showcase] All 5 1920x1080 showcase PNGs saved successfully.");
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
            renderer.renderFrame(w, h, 0.0, false, state, camera, gpuTimers, captureFbo);
            gpuTimers.beginUi();
            if (withHud && s == 15) {
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, w, h);
                hud.render(uiRenderer, w, h, -10.0, -10.0, true, false, state, camera, telemetry);
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
                camera.toggleAttractTour(state);
                state.showToast(state.attractTourActive
                        ? "DIRECTOR'S CUT TOUR: ENGAGED"
                        : "DIRECTOR'S CUT TOUR: OFF");
            }
            case HudOverlay.ACT_PLUNGE -> {
                camera.disengageAttractTour(state);
                if (state.plungeActive && !state.plungeEnded) {
                    camera.abortPlunge(state);
                    state.showToast("PLUNGE DIVE: ABORTED");
                } else {
                    camera.startPlunge(state);
                    state.showToast("PLUNGE DIVE: CROSSING EVENT HORIZON");
                }
            }
            case HudOverlay.ACT_WORMHOLE -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.wormholeMode = !state.wormholeMode;
                if (state.wormholeMode) {
                    camera.applyWormholeUniverseA();
                    state.showToast("SPACETIME: MORRIS-THORNE WORMHOLE");
                } else {
                    camera.applyPresetGargantuaEdge();
                    state.showToast("SPACETIME: KERR-SCHILD BLACK HOLE");
                }
            }
            case HudOverlay.ACT_SHRED_STAR -> {
                if (!state.wormholeMode) {
                    pendingSpawnStar = true;
                    state.showToast("STAR INJECTED: 65,536 PARTICLES");
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
            case HudOverlay.PRESET_RESET -> {
                camera.disengageAttractTour(state);
                state.resetDefaults();
                pendingClearStars = true;
                if (state.wormholeMode) {
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
                    state.showUi = !state.showUi;
                    if (!state.showUi) {
                        state.cinemaHintAge = 0.0f;
                    }
                    state.showToast(state.showUi ? "CONSOLE & TELEMETRY: ON" : "CINEMA MODE");
                }
                case GLFW_KEY_T -> triggerAction(HudOverlay.ACT_ATTRACT_TOUR);
                case GLFW_KEY_F12, GLFW_KEY_S -> pendingScreenshot = true;
                case GLFW_KEY_P -> triggerAction(HudOverlay.ACT_PLUNGE);
                case GLFW_KEY_W -> triggerAction(HudOverlay.ACT_WORMHOLE);
                case GLFW_KEY_E -> triggerAction(HudOverlay.ACT_SHRED_STAR);
                case GLFW_KEY_A -> triggerAction(HudOverlay.ACT_AUDIO);
                case GLFW_KEY_1 -> triggerAction(state.wormholeMode ? HudOverlay.PRESET_UNIV_A : HudOverlay.PRESET_EDGE);
                case GLFW_KEY_2 -> triggerAction(state.wormholeMode ? HudOverlay.PRESET_THROAT : HudOverlay.PRESET_POLE);
                case GLFW_KEY_3 -> triggerAction(state.wormholeMode ? HudOverlay.PRESET_UNIV_B : HudOverlay.PRESET_RING);
                case GLFW_KEY_4 -> triggerAction(state.wormholeMode ? HudOverlay.ACT_FLY_THROUGH : HudOverlay.PRESET_DIVE);
                case GLFW_KEY_R -> triggerAction(HudOverlay.PRESET_RESET);
                case GLFW_KEY_L -> triggerAction(HudOverlay.ACT_LENSING);
                case GLFW_KEY_D -> triggerAction(HudOverlay.ACT_DISK);
                case GLFW_KEY_J -> triggerAction(HudOverlay.ACT_JETS);
                case GLFW_KEY_B -> triggerAction(HudOverlay.ACT_DOPPLER);
                case GLFW_KEY_K -> triggerAction(HudOverlay.ACT_KERR_SPIN);
                case GLFW_KEY_F -> triggerAction(HudOverlay.ACT_FREE_FALL);
                case GLFW_KEY_M -> triggerAction(HudOverlay.ACT_ADAPTIVE_AA);
                case GLFW_KEY_C -> triggerAction(HudOverlay.ACT_SKY_GRID);
                case GLFW_KEY_I -> triggerAction(HudOverlay.ACT_ORDER_VIS);
                case GLFW_KEY_O -> triggerAction(HudOverlay.ACT_AUTO_ORBIT);
                case GLFW_KEY_V -> triggerAction(HudOverlay.ACT_VSYNC);
                case GLFW_KEY_SPACE -> triggerAction(HudOverlay.ACT_PAUSE);
                case GLFW_KEY_U -> {
                    camera.disengageAttractTour(state);
                    camera.toggleCinematicRoll();
                    state.showToast("CAMERA ROLL TOGGLED");
                }
                case GLFW_KEY_G -> {
                    state.bloomStrength =
                            (state.bloomStrength > 0.45f) ? 0.0f : (state.bloomStrength < 0.1f ? 0.30f : 0.62f);
                    state.showToast(state.bloomStrength < 0.05f ? "BLOOM: OFF"
                            : (state.bloomStrength > 0.45f ? "BLOOM: CINEMATIC" : "BLOOM: BALANCED"));
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
}
