package io.github.laithabuzaid.blackhole.core;

import io.github.laithabuzaid.blackhole.audio.RelativisticAudioEngine;
import io.github.laithabuzaid.blackhole.graphics.BenchmarkAndShowcaseRunner;
import io.github.laithabuzaid.blackhole.graphics.BlackHoleRenderer;
import io.github.laithabuzaid.blackhole.graphics.GpuTimerQueries;
import io.github.laithabuzaid.blackhole.graphics.ShaderProgram;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;
import io.github.laithabuzaid.blackhole.ui.HudOverlay;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;
import io.github.laithabuzaid.blackhole.util.FramebufferCapture;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;
import io.github.laithabuzaid.blackhole.util.SettingsStore;
import io.github.laithabuzaid.blackhole.util.WindowIconLoader;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Path;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main GLFW / OpenGL 4.3 Core simulation lifecycle, input/action coordination,
 * Cinema-First attract tour handoff, procedural OpenAL audio synchronization, and
 * zero-allocation real-time render loop.
 *
 * <p>Delegates offline benchmarking/showcase capture to {@link BenchmarkAndShowcaseRunner},
 * PNG snapshot I/O to {@link FramebufferCapture}, and native taskbar icon loading to
 * {@link WindowIconLoader}.</p>
 */
public final class SimulationEngine {

    private final boolean captureShowcaseMode;
    private final boolean benchmarkMode;
    private final Path externalGwDataPath;

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
    private final FramebufferCapture frameCapture = new FramebufferCapture();

    private boolean draggingCamera = false;
    private boolean mouseDown = false;
    private boolean keyUpHeld = false;
    private boolean keyDownHeld = false;
    private boolean pendingSpawnStar = false;
    private boolean pendingClearStars = false;
    private boolean pendingScreenshot = false;
    private boolean pendingResetTaa = false;

    private double anchorX;
    private double anchorY;
    private double mouseX;
    private double mouseY;
    private final double[] cursorXBuf = new double[1];
    private final double[] cursorYBuf = new double[1];
    private final StringBuilder toastSb = new StringBuilder(64);

    private long window = NULL;

    public SimulationEngine() {
        this(false, false, null);
    }

    public SimulationEngine(boolean captureShowcaseMode) {
        this(captureShowcaseMode, false, null);
    }

    public SimulationEngine(boolean captureShowcaseMode, boolean benchmarkMode, Path externalGwDataPath) {
        this.captureShowcaseMode = captureShowcaseMode;
        this.benchmarkMode = benchmarkMode;
        this.externalGwDataPath = externalGwDataPath;
    }

    public void run() {
        boolean offscreenMode = captureShowcaseMode || benchmarkMode;
        if (externalGwDataPath != null) {
            try {
                GwReferenceData loaded = GwReferenceData.loadFromFile(externalGwDataPath);
                System.out.println("[BlackHole] Loaded external GWOSC dataset: " + loaded.label()
                        + " (" + loaded.sampleCount() + " samples)");
            } catch (IOException e) {
                System.err.println("[BlackHole] Failed to load --gw-data " + externalGwDataPath + ": " + e.getMessage());
            }
        }

        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) {
            errorCallback.free();
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, offscreenMode ? GLFW_FALSE : GLFW_TRUE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            if (Boolean.getBoolean("blackhole.glDebug")) {
                glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
            }

            int initW = offscreenMode ? 640 : windowW;
            int initH = offscreenMode ? 360 : windowH;
            window = glfwCreateWindow(initW, initH,
                    "BlackHole v2.0 — Relativistic Kerr-Schild, Binary Merger, Wormhole & TDE Engine", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW OpenGL 4.3 Core window");
            }

            if (!offscreenMode) {
                SettingsStore.load(state);
                WindowIconLoader.applyWindowIcon(window);
                setupCallbacks(window);
                // Query actual HiDPI physical framebuffer dimensions before creating RenderTargets
                glfwGetFramebufferSize(window, winSizeWBuf, winSizeHBuf);
                if (winSizeWBuf[0] > 0 && winSizeHBuf[0] > 0) {
                    windowW = winSizeWBuf[0];
                    windowH = winSizeHBuf[0];
                }
            }

            glfwMakeContextCurrent(window);
            glfwSwapInterval(offscreenMode ? 0 : (state.vsync ? 1 : 0));
            if (!offscreenMode) {
                glfwShowWindow(window);
            }

            GL.createCapabilities();
            ShaderProgram.installDebugCallbackIfEnabled();

            String glRenderer = glGetString(GL_RENDERER);
            String glVersion = glGetString(GL_VERSION);
            telemetry.initOpenGlInfo(glRenderer, glVersion);

            if (benchmarkMode) {
                try (BlackHoleRenderer renderer = new BlackHoleRenderer(1920, 1080);
                     UiRenderer uiRenderer = new UiRenderer();
                     GpuTimerQueries gpuTimers = new GpuTimerQueries()) {
                    BenchmarkAndShowcaseRunner.runBenchmarks(renderer, uiRenderer, gpuTimers,
                            state, camera, binaryPhysics, telemetry, hud);
                }
                return;
            }

            if (captureShowcaseMode) {
                telemetry.startBackgroundSampler();
                try (BlackHoleRenderer renderer = new BlackHoleRenderer(1920, 1080);
                     UiRenderer uiRenderer = new UiRenderer();
                     GpuTimerQueries gpuTimers = new GpuTimerQueries()) {
                    BenchmarkAndShowcaseRunner.runShowcaseCapture(renderer, uiRenderer, gpuTimers,
                            state, camera, binaryPhysics, telemetry, hud);
                }
                return;
            }

            telemetry.startBackgroundSampler();
            audio.start();

            try (BlackHoleRenderer renderer = new BlackHoleRenderer(windowW, windowH);
                 UiRenderer uiRenderer = new UiRenderer();
                 GpuTimerQueries gpuTimers = new GpuTimerQueries()) {

                ShaderProgram.checkGlError("SimulationEngine.startup");
                System.out.println("[BlackHole] v3.0.0 | OpenGL " + glVersion + " | GPU: " + glRenderer
                        + " | Exact Kerr-Schild RK4/DOPRI5(4), 64^3 Grid-Hydro TDE, 2.5PN/QNM Merger & Sci Diag ready.");

                runRenderLoop(renderer, uiRenderer, gpuTimers);
            }
        } finally {
            if (!offscreenMode) {
                SettingsStore.save(state);
            }
            frameCapture.close();
            audio.close();
            telemetry.close();
            if (window != NULL) {
                glfwFreeCallbacks(window);
                glfwDestroyWindow(window);
                window = NULL;
            }
            glfwTerminate();
            errorCallback.free();
        }
    }

    /** Zero-allocation real-time render loop with minimize idle-skip and 6-pass GPU/CPU profiling. */
    private void runRenderLoop(BlackHoleRenderer renderer, UiRenderer uiRenderer, GpuTimerQueries gpuTimers) {
        long lastFrameNano = System.nanoTime();
        long fpsTimerNano = lastFrameNano;
        int frameCounter = 0;

        while (!glfwWindowShouldClose(window)) {
            if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE || windowW <= 0 || windowH <= 0) {
                glfwWaitEventsTimeout(0.05);
                lastFrameNano = System.nanoTime();
                continue;
            }

            long frameStartNano = System.nanoTime();

            boolean resizedThisFrame = false;
            if (framebufferResized) {
                renderer.resize(windowW, windowH);
                framebufferResized = false;
                resizedThisFrame = true;
            }

            if (pendingResetTaa) {
                renderer.resetTaaHistory();
                pendingResetTaa = false;
            }
            if (pendingClearStars) {
                renderer.clearTdeStars(state.tdeSeed);
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

            float rawGpuTotalMs = 0.0f;
            if (gpuTimers.pollPreviousFrame()) {
                telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeComputeMs(),
                        gpuTimers.tdeRenderMs(), gpuTimers.taaMs(), gpuTimers.postMs(), gpuTimers.uiMs());
                rawGpuTotalMs = gpuTimers.totalGpuMs();
            }

            long physStart = System.nanoTime();
            if (state.binaryMode && state.binaryParamsDirty) {
                state.binaryParamsDirty = false;
                binaryPhysics.reset(state.buildBinaryParameters());
                pendingResetTaa = true;
            }
            if (!state.paused) {
                state.simTime += simDt;
                if (state.binaryMode) {
                    binaryPhysics.step(wallDt, state.timeScale, state.binaryAutoLoop);
                }
            }
            state.tickUiTimers(wallDt);
            camera.update(wallDt, state, keyUpHeld, keyDownHeld, renderer.tdeShearEnergy(), true);
            telemetry.cpuPhysicsMs = telemetry.cpuPhysicsMs * 0.85f + ((System.nanoTime() - physStart) * 1e-6f) * 0.15f;

            if (camera.consumeAttractSpawnTrigger() && !state.wormholeMode && !state.binaryMode) {
                renderer.spawnTdeStar(state);
                toastSb.setLength(0);
                toastSb.append("STAR LAUNCHED: ").append(state.currentTdeArchetype().displayName);
                state.showToast(toastSb);
            }

            // Passes 1..3: Raytrace, 64^3 Hydro TDE, TAA, Karis Bloom, Anamorphic, Hill ACES + IEC sRGB
            renderer.renderFrame(windowW, windowH, wallDt, simDt, state, camera, binaryPhysics, gpuTimers, 0);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
            telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
            telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
            telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
            telemetry.tdeMeanEnergy = renderer.tdeMeanEnergy();
            telemetry.tdeMeanAngMomY = renderer.tdeMeanAngMomY();
            telemetry.tdePeakTempK = renderer.tdePeakTempK();
            telemetry.tdeSubsteps = renderer.tdeSubsteps();
            telemetry.tdeParticleStepsPerSec = renderer.tdeParticleStepsPerSec();
            audio.updatePhysics(state, camera, renderer.tdeShearEnergy(), binaryPhysics);

            // [F12] Bounded-executor HUD-free PNG snapshot
            if (pendingScreenshot) {
                pendingScreenshot = false;
                frameCapture.captureLiveSnapshotAsync(windowW, windowH, state);
            }

            // Pass 4: Observatory HUD + [F3] Scientific Visualizer
            long hudStart = System.nanoTime();
            gpuTimers.beginUi();
            hud.render(uiRenderer, windowW, windowH, mouseX, mouseY, telemetryTick, false, state, camera, binaryPhysics, telemetry);
            gpuTimers.endUi();
            telemetry.cpuHudMs = telemetry.cpuHudMs * 0.85f + ((System.nanoTime() - hudStart) * 1e-6f) * 0.15f;

            float rawCpuSubmitMs = (System.nanoTime() - frameStartNano) * 1e-6f;
            telemetry.recordFrameSample(rawCpuSubmitMs, rawGpuTotalMs);

            gpuTimers.advanceFrame();

            glfwSwapBuffers(window);
            glfwPollEvents();
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
                    state.binaryParamsDirty = false;
                    binaryPhysics.reset(state.buildBinaryParameters());
                    camera.applyPresetBinaryOblique();
                    state.showToast("SPACETIME: EOB BINARY BLACK HOLE MERGER");
                } else {
                    camera.applyPresetGargantuaEdge();
                    state.showToast("SPACETIME: KERR-SCHILD BLACK HOLE");
                }
            }
            case HudOverlay.ACT_BINARY_REPLAY -> {
                if (state.binaryMode) {
                    state.binaryParamsDirty = false;
                    binaryPhysics.reset(state.buildBinaryParameters());
                    state.paused = false;
                    state.showToast("EOB BINARY MERGER: REPLAYING INSPIRAL");
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
            case SimAction.ACT_INTEGRATOR_TOGGLE -> {
                state.integratorMode = (state.integratorMode == SimulationState.INTEGRATOR_RK4)
                        ? SimulationState.INTEGRATOR_DOPRI54 : SimulationState.INTEGRATOR_RK4;
                pendingResetTaa = true;
                state.showToast(state.integratorMode == SimulationState.INTEGRATOR_DOPRI54
                        ? "GEODESIC SOLVER: ADAPTIVE DOPRI5(4)"
                        : "GEODESIC SOLVER: VGPR-OPTIMIZED RK4");
            }
            case SimAction.ACT_SCIENTIFIC_DIAG -> {
                state.cycleScientificDiagMode();
                pendingResetTaa = true;
                state.showToast("SCIENTIFIC MODE: " + state.scientificDiagLabel());
            }
            case SimAction.ACT_TDE_HYDRO_TOGGLE -> {
                state.tdeHydroEnabled = !state.tdeHydroEnabled;
                state.showToast(state.tdeHydroEnabled
                        ? "TDE PHYSICS: 64^3 GRID HYDRO + SHOCK"
                        : "TDE PHYSICS: PURE GRAVITY CONSERVATION");
            }
            case HudOverlay.ACT_LIGO_GW150914 -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.binaryMode = true;
                state.wormholeMode = false;
                state.applyBinaryParameters(BinaryParameters.presetGw150914());
                state.binaryParamsDirty = false;
                binaryPhysics.reset(state.buildBinaryParameters());
                state.paused = false;
                pendingResetTaa = true;
                state.showToast("GW150914: 35.6+30.6 M_sun, chi=+0.02/-0.04, 440 Mpc");
            }
            case HudOverlay.ACT_LIGO_GW190412 -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.binaryMode = true;
                state.wormholeMode = false;
                state.applyBinaryParameters(BinaryParameters.presetGw190412());
                state.binaryParamsDirty = false;
                binaryPhysics.reset(state.buildBinaryParameters());
                state.paused = false;
                pendingResetTaa = true;
                state.showToast("GW190412: 30.1+8.3 M_sun, chi1=+0.44, 740 Mpc");
            }
            case HudOverlay.ACT_LIGO_EQUAL -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.binaryMode = true;
                state.wormholeMode = false;
                state.applyBinaryParameters(BinaryParameters.presetEqualNonSpinning());
                state.binaryParamsDirty = false;
                binaryPhysics.reset(state.buildBinaryParameters());
                state.paused = false;
                pendingResetTaa = true;
                state.showToast("SXS:BBH:0180 EQUAL 1:1 NON-SPINNING (q=1.00)");
            }
            case SimAction.ACT_LIGO_ALIGNED_SPIN -> {
                camera.disengageAttractTour(state);
                state.clearPlungeTelemetry();
                state.binaryMode = true;
                state.wormholeMode = false;
                state.applyBinaryParameters(BinaryParameters.presetEqualAlignedSpin());
                state.binaryParamsDirty = false;
                binaryPhysics.reset(state.buildBinaryParameters());
                state.paused = false;
                pendingResetTaa = true;
                state.showToast("EQUAL 1:1 ALIGNED SPIN: chi1=chi2=+0.75 (HANG-UP)");
            }
            case SimAction.ACT_BIN_DETECTOR_CYCLE -> {
                state.binaryDetector = state.binaryDetector.next();
                state.binaryParamsDirty = true;
                state.showToast("GW DETECTOR: " + state.binaryDetector.label());
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
                    state.binaryParamsDirty = false;
                    binaryPhysics.reset(state.buildBinaryParameters());
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
                case GLFW_KEY_F3 -> triggerAction(SimAction.ACT_SCIENTIFIC_DIAG);
                case GLFW_KEY_F4 -> triggerAction(SimAction.ACT_INTEGRATOR_TOGGLE);
                case GLFW_KEY_F5 -> triggerAction(SimAction.ACT_TDE_HYDRO_TOGGLE);
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
}
