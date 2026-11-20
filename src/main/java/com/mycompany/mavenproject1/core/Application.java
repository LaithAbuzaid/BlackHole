package com.mycompany.mavenproject1.core;

import com.mycompany.mavenproject1.graphics.BlackHoleRenderer;
import com.mycompany.mavenproject1.graphics.GpuTimerQueries;
import com.mycompany.mavenproject1.graphics.ShaderProgram;
import com.mycompany.mavenproject1.telemetry.HardwareTelemetry;
import com.mycompany.mavenproject1.ui.HudOverlay;
import com.mycompany.mavenproject1.ui.UiRenderer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main GLFW / OpenGL 3.3 application lifecycle, input routing, and
 * zero-allocation real-time render loop.
 */
public final class Application {

    private int windowW = 1440;
    private int windowH = 860;
    private boolean framebufferResized = false;

    private final SimulationState state = new SimulationState();
    private final CameraController camera = new CameraController();
    private final HardwareTelemetry telemetry = new HardwareTelemetry();
    private final HudOverlay hud = new HudOverlay();

    private boolean draggingCamera = false;
    private boolean mouseDown = false;
    private double anchorX;
    private double anchorY;
    private double mouseX;
    private double mouseY;
    private final double[] cursorXBuf = new double[1];
    private final double[] cursorYBuf = new double[1];

    private long window = NULL;

    public void run() {
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

            window = glfwCreateWindow(windowW, windowH,
                    "Relativistic Kerr-Schild Black Hole Engine — OpenGL 3.3", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW OpenGL 3.3 window");
            }

            setupCallbacks(window);

            glfwMakeContextCurrent(window);
            glfwSwapInterval(state.vsync ? 1 : 0);
            glfwShowWindow(window);

            GL.createCapabilities();

            String glRenderer = glGetString(GL_RENDERER);
            String glVersion = glGetString(GL_VERSION);
            telemetry.initOpenGlInfo(glRenderer, glVersion);
            telemetry.startBackgroundSampler();

            try (BlackHoleRenderer renderer = new BlackHoleRenderer(windowW, windowH);
                 UiRenderer uiRenderer = new UiRenderer();
                 GpuTimerQueries gpuTimers = new GpuTimerQueries()) {

                ShaderProgram.checkGlError("Application.startup");
                System.out.println("[BlackHole] OpenGL " + glVersion + " | GPU: " + glRenderer + " | Shaders & FBOs validated.");

                runRenderLoop(renderer, uiRenderer, gpuTimers);
            }
        } finally {
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

            if (framebufferResized) {
                renderer.resize(windowW, windowH);
                framebufferResized = false;
            }

            long elapsedNano = frameStartNano - lastFrameNano;
            double dt = Math.min(elapsedNano * 1e-9, 0.05);
            float rawWallMs = elapsedNano * 1e-6f;
            telemetry.wallFrameMs = telemetry.wallFrameMs * 0.88f + rawWallMs * 0.12f;
            lastFrameNano = frameStartNano;

            frameCounter++;
            if (frameStartNano - fpsTimerNano >= 250_000_000L) {
                telemetry.currentFps = (float) (frameCounter * 1_000_000_000.0 / (frameStartNano - fpsTimerNano));
                frameCounter = 0;
                fpsTimerNano = frameStartNano;
                telemetry.pollOpenGlVram();
            }

            if (gpuTimers.pollPreviousFrame()) {
                telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.bloomMs(), gpuTimers.uiMs());
            }

            if (!state.paused) {
                state.simTime += dt;
            }
            camera.update(dt, state);

            // Passes 1..3: Kerr-Schild Raytrace, 5-Level Karis Bloom Pyramid, HDR Composite
            renderer.renderFrame(windowW, windowH, state, camera, gpuTimers);

            // Pass 4: Glassmorphic Hardware Profiler & Controls HUD (Always GPU-timed for query sync)
            gpuTimers.beginUi();
            if (state.showUi) {
                hud.render(uiRenderer, windowW, windowH, mouseX, mouseY, state, camera, telemetry);
            } else {
                hud.clearHitBoxes();
            }
            gpuTimers.endUi();

            float rawCpuSubmitMs = (System.nanoTime() - frameStartNano) * 1e-6f;
            telemetry.recordFrameSample(rawCpuSubmitMs);

            gpuTimers.advanceFrame();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void triggerAction(int actionId) {
        switch (actionId) {
            case HudOverlay.ACT_LENSING -> state.lensingEnabled = !state.lensingEnabled;
            case HudOverlay.ACT_DISK -> state.showDisk = !state.showDisk;
            case HudOverlay.ACT_JETS -> state.showJets = !state.showJets;
            case HudOverlay.ACT_DOPPLER -> state.dopplerEnabled = !state.dopplerEnabled;
            case HudOverlay.ACT_KERR_SPIN -> state.kerrSpinEnabled = !state.kerrSpinEnabled;
            case HudOverlay.ACT_FREE_FALL -> state.freeFallMode = !state.freeFallMode;
            case HudOverlay.ACT_ADAPTIVE_AA -> state.adaptiveAa4x = !state.adaptiveAa4x;
            case HudOverlay.ACT_SKY_GRID -> state.showGrid = !state.showGrid;
            case HudOverlay.ACT_ORDER_VIS -> state.orderVisualizer = !state.orderVisualizer;
            case HudOverlay.ACT_AUTO_ORBIT -> state.autoOrbit = !state.autoOrbit;
            case HudOverlay.ACT_VSYNC -> {
                state.vsync = !state.vsync;
                glfwSwapInterval(state.vsync ? 1 : 0);
            }
            case HudOverlay.ACT_PAUSE -> state.paused = !state.paused;
            case HudOverlay.ACT_TOGGLE_PANEL -> state.controlsExpanded = !state.controlsExpanded;
            case HudOverlay.PRESET_EDGE -> {
                state.autoOrbit = false;
                camera.applyPresetGargantuaEdge();
            }
            case HudOverlay.PRESET_POLE -> {
                state.autoOrbit = false;
                camera.applyPresetPolarVortex();
            }
            case HudOverlay.PRESET_RING -> {
                state.autoOrbit = false;
                camera.applyPresetPhotonRing();
            }
            case HudOverlay.PRESET_DIVE -> {
                state.autoOrbit = false;
                camera.applyPresetHorizonDive(state);
            }
            case HudOverlay.PRESET_RESET -> {
                state.resetDefaults();
                camera.applyPresetGargantuaEdge();
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
                camera.orbitBy(xpos - anchorX, ypos - anchorY);
                anchorX = xpos;
                anchorY = ypos;
            }
        });

        glfwSetScrollCallback(winHandle, (win, xoffset, yoffset) -> camera.zoomBy(yoffset, state));

        glfwSetKeyCallback(winHandle, (win, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS) {
                return;
            }
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true);
                case GLFW_KEY_H -> state.showUi = !state.showUi;
                case GLFW_KEY_1 -> triggerAction(HudOverlay.PRESET_EDGE);
                case GLFW_KEY_2 -> triggerAction(HudOverlay.PRESET_POLE);
                case GLFW_KEY_3 -> triggerAction(HudOverlay.PRESET_RING);
                case GLFW_KEY_4 -> triggerAction(HudOverlay.PRESET_DIVE);
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
                case GLFW_KEY_T -> camera.toggleCinematicRoll();
                case GLFW_KEY_G -> state.bloomStrength =
                        (state.bloomStrength > 0.45f) ? 0.0f : (state.bloomStrength < 0.1f ? 0.30f : 0.62f);
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
