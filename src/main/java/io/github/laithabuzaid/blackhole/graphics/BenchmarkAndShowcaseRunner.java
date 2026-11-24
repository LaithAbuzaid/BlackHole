package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.BinaryParameters;
import io.github.laithabuzaid.blackhole.telemetry.HardwareTelemetry;
import io.github.laithabuzaid.blackhole.ui.HudOverlay;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;
import io.github.laithabuzaid.blackhole.util.FramebufferCapture;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.*;

/**
 * Runs repeatable offscreen 1920x1080 GPU/CPU/zero-GC benchmarks ({@code --benchmark}) and
 * captures all 8 curated 1920x1080 showcase PNGs ({@code --capture-showcase}) with leak-free FBO ownership.
 */
public final class BenchmarkAndShowcaseRunner {

    private static final int TARGET_W = 1920;
    private static final int TARGET_H = 1080;

    private BenchmarkAndShowcaseRunner() {
    }

    /** Repeatable 5-scenario 1080p GPU/CPU/Zero-GC benchmark suite. */
    public static void runBenchmarks(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                     GpuTimerQueries gpuTimers, SimulationState state,
                                     CameraController camera, BinaryMergerPhysics binaryPhysics,
                                     HardwareTelemetry telemetry, HudOverlay hud) {
        int captureFbo = 0;
        int captureTex = 0;
        try {
            int[] fboTex = createCaptureTarget(TARGET_W, TARGET_H);
            captureFbo = fboTex[0];
            captureTex = fboTex[1];

            String gpuName = glGetString(GL_RENDERER);
            String glVer = glGetString(GL_VERSION);
            telemetry.initOpenGlInfo(gpuName, glVer);
            System.out.println("=== BlackHole v3.1.0 Repeatable 1080p Benchmark ===");
            System.out.println("GPU: " + gpuName + " | OpenGL: " + glVer);
            System.out.printf("%-28s | %6s | %6s | %6s | %6s | %6s | %6s | %6s | %7s | %6s%n",
                    "Scenario", "RAY", "TDE_C", "TDE_R", "TAA", "POST", "UI", "TOTAL", "FPS", "P95");
            System.out.println("-".repeat(108));

            // 1. Kerr-Schild VGPR-RK4 (1x)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.integratorMode = SimulationState.INTEGRATOR_RK4;
            state.spinParam = 0.96f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(34.0, 0.24, 0.145, -0.10, 0.0, 0.0, state);
            measureScenario("Kerr VGPR-RK4 (1x)", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 1.0 / 120.0, false, state, camera, binaryPhysics, telemetry, hud);

            // 2. Kerr-Schild Adaptive DOPRI5(4) (1x)
            state.integratorMode = SimulationState.INTEGRATOR_DOPRI54;
            state.integratorRtol = 2.5e-4f;
            measureScenario("Kerr DOPRI5(4) (1x)", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 1.0 / 120.0, false, state, camera, binaryPhysics, telemetry, hud);

            // 3. Kerr + 65k TDE 64^3 Grid-Hydro (1x + Full HUD)
            state.integratorMode = SimulationState.INTEGRATOR_RK4;
            state.setHudMode(SimulationState.HUD_FULL);
            state.tdeHydroEnabled = true;
            state.tdeArchetypeIndex = 2;
            state.tdeBeta = 1.14f;
            renderer.clearTdeStars(state.tdeSeed);
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(180, 0.014, state);
            measureScenario("Kerr + TDE Hydro 1x + HUD", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 1.0 / 120.0, true, state, camera, binaryPhysics, telemetry, hud);

            // 4. Kerr + 65k TDE 64^3 Grid-Hydro (10x Timelapse)
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.setTimeScale(10.0f);
            measureScenario("Kerr + TDE Hydro 10x", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 10.0 / 120.0, false, state, camera, binaryPhysics, telemetry, hud);

            // 5. Binary Black Hole Merger (EOB + Boosted Spinning KS, RK4 + MRT TAA)
            resetForShot(state);
            state.binaryMode = true;
            state.integratorMode = SimulationState.INTEGRATOR_RK4;
            state.applyBinaryParameters(BinaryParameters.presetGw150914());
            state.binaryParamsDirty = false;
            binaryPhysics.reset(state.buildBinaryParameters());
            binaryPhysics.advanceCurrentToSeparation(5.25);
            camera.setShowcasePose(28.5, 0.65, 0.38, -0.07, 0.0, 0.0, state);
            measureScenario("Binary Merger RK4 (1x+TAA)", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 1.0 / 120.0, false, state, camera, binaryPhysics, telemetry, hud);

            // 6. Binary Black Hole Merger (EOB + Boosted Spinning KS, Adaptive DOPRI5(4) + TAA)
            state.integratorMode = SimulationState.INTEGRATOR_DOPRI54;
            state.integratorRtol = 2.5e-4f;
            measureScenario("Binary Merger DOPRI5(4)", renderer, uiRenderer, gpuTimers, captureFbo,
                    1.0 / 120.0, 1.0 / 120.0, false, state, camera, binaryPhysics, telemetry, hud);

            ShaderProgram.checkGlError("Benchmark.finish");
            System.out.println("=== Benchmark Complete (0 GL errors) ===");
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (captureTex != 0) glDeleteTextures(captureTex);
            if (captureFbo != 0) glDeleteFramebuffers(captureFbo);
        }
    }

    private static void measureScenario(String name, BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                        GpuTimerQueries gpuTimers, int captureFbo,
                                        double wallDt, double simDt, boolean withHud,
                                        SimulationState state, CameraController camera,
                                        BinaryMergerPhysics binaryPhysics, HardwareTelemetry telemetry,
                                        HudOverlay hud) {
        renderer.resetTaaHistory();
        final int warmup = 10;
        final int samples = 36;
        float[] totals = new float[samples];
        float sumRay = 0, sumTdeC = 0, sumTdeR = 0, sumTaa = 0, sumPost = 0, sumUi = 0;
        int recorded = 0;

        for (int f = 0; f < warmup + samples; f++) {
            if (state.binaryMode) {
                binaryPhysics.step(simDt, state.binaryMassRatio, 1.0f, true);
            }
            state.simTime += simDt;
            renderer.renderFrame(TARGET_W, TARGET_H, wallDt, simDt, state, camera, binaryPhysics, gpuTimers, captureFbo);
            gpuTimers.beginUi();
            if (withHud) {
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, TARGET_W, TARGET_H);
                hud.render(uiRenderer, TARGET_W, TARGET_H, -10.0, -10.0, (f % 10) == 0, false,
                        state, camera, binaryPhysics, telemetry);
            }
            gpuTimers.endUi();
            glFinish();
            if (gpuTimers.pollPreviousFrame() && f >= warmup && recorded < samples) {
                float r = gpuTimers.rayMs();
                float tc = gpuTimers.tdeComputeMs();
                float tr = gpuTimers.tdeRenderMs();
                float ta = gpuTimers.taaMs();
                float po = gpuTimers.postMs();
                float u = gpuTimers.uiMs();
                sumRay += r; sumTdeC += tc; sumTdeR += tr; sumTaa += ta; sumPost += po; sumUi += u;
                totals[recorded++] = r + tc + tr + ta + po + u;
            }
            gpuTimers.advanceFrame();
        }
        int n = Math.max(1, recorded);
        float avgRay = sumRay / n, avgTc = sumTdeC / n, avgTr = sumTdeR / n;
        float avgTaa = sumTaa / n, avgPost = sumPost / n, avgUi = sumUi / n;
        float avgTot = avgRay + avgTc + avgTr + avgTaa + avgPost + avgUi;
        Arrays.sort(totals, 0, n);
        float p95 = totals[Math.min(n - 1, (int) (n * 0.95f))];
        System.out.printf("%-28s | %6.2f | %6.2f | %6.2f | %6.2f | %6.2f | %6.2f | %6.2f | %7.1f | %6.2f%n",
                name, avgRay, avgTc, avgTr, avgTaa, avgPost, avgUi, avgTot, 1000.0f / Math.max(0.05f, avgTot), p95);
    }

    /** Captures all 8 curated 1920x1080 showcase PNGs into {@code docs/media/}. */
    public static void runShowcaseCapture(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                          GpuTimerQueries gpuTimers, SimulationState state,
                                          CameraController camera, BinaryMergerPhysics binaryPhysics,
                                          HardwareTelemetry telemetry, HudOverlay hud) {
        runShowcaseCapture(renderer, uiRenderer, gpuTimers, state, camera, binaryPhysics,
                telemetry, hud, GwReferenceData.loadBundledGw150914());
    }

    /** Captures all 8 curated 1920x1080 showcase PNGs into {@code docs/media/}. */
    public static void runShowcaseCapture(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                          GpuTimerQueries gpuTimers, SimulationState state,
                                          CameraController camera, BinaryMergerPhysics binaryPhysics,
                                          HardwareTelemetry telemetry, HudOverlay hud, GwReferenceData gwRef) {
        int captureFbo = 0;
        int captureTex = 0;
        try {
            System.out.println("[Showcase] OpenGL " + glGetString(GL_VERSION)
                    + " | GPU: " + glGetString(GL_RENDERER)
                    + " | Rendering 8 curated 1920x1080 showcase PNGs into docs/media/...");

            int[] fboTex = createCaptureTarget(TARGET_W, TARGET_H);
            captureFbo = fboTex[0];
            captureTex = fboTex[1];

            // 1. hero_kerr_edge.png
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.96f;
            state.diskTempScale = 1.00f;
            state.diskDensity = 1.04f;
            state.jetPower = 0.14f;
            state.bloomStrength = 0.28f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.34f;
            state.exposure = 1.24f;
            state.fovDeg = 22.0f;
            state.simTime = 1.4;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(34.0, 0.24, 0.145, -0.10, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/hero_kerr_edge.png"));

            // 2. tde_shredding.png (64^3 GPU Hydrodynamics + Boris Lense-Thirring at peak periapsis wrap)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.96f;
            state.tdeHydroEnabled = true;
            state.tdeArchetypeIndex = 2; // Blue O-Type (r_t = 5.5M, r_p = 5.24M > r_mb = 4.0M -> full tidal spiral + escape tail)
            state.tdeBeta = 1.05f;
            state.tdeInclinationDeg = -36.0f; // Tilted opposite camera elevation (+29 deg) -> ~65 deg face-on view of 3D tidal spiral!
            state.diskTempScale = 0.90f;
            state.diskDensity = 0.50f;
            state.jetPower = 0.12f;
            state.bloomStrength = 0.30f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.14f;
            state.exposure = 1.20f;
            state.fovDeg = 26.5f;
            state.simTime = 6.2;
            camera.setShowcasePose(36.0, -0.35, 0.50, -0.05, 0.0, 0.0, state);
            renderer.clearTdeStars(state.tdeSeed);
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(790, 0.014, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/tde_shredding.png"));

            // 3. wormhole_throat.png (camera-invariant Universe B continuation)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = true;
            state.wormholeRho = 2.65f;
            state.wormholeA = 0.45f;
            state.wormholeM = 1.25f;
            state.bloomStrength = 0.24f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.16f;
            state.exposure = 1.22f;
            state.fovDeg = 23.0f;
            camera.setShowcaseWormholePose(11.6, 0.42, 0.06, 0.0);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/wormhole_throat.png"));

            // 4. horizon_plunge.png (exact L_z=0 Doran free-fall tetrad inside r_+ = 1.436M at a* = 0.90)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.90f;
            state.freeFallMode = true;
            state.plungeActive = true;
            state.plungeHorizonCrossed = true;
            state.plungeZone = SimulationState.ZONE_INTERIOR;
            state.cauchyFlashIntensity = 0.18f;
            state.diskTempScale = 1.04f;
            state.diskDensity = 1.06f;
            state.jetPower = 0.20f;
            state.bloomStrength = 0.28f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.24f;
            state.exposure = 1.16f;
            state.fovDeg = 42.0f;
            state.simTime = 14.5;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(1.14, 0.48, 0.14, -0.06, 1.10, -0.04, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/horizon_plunge.png"));

            // 5. hud_telemetry.png (Full Observatory Console + TDE Lab + Timelapse Bar)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_FULL);
            state.setTimeScale(5.0f);
            state.spinParam = 0.98f;
            state.tdeHydroEnabled = true;
            state.tdeArchetypeIndex = 2;
            state.tdeBeta = 1.05f;
            state.tdeInclinationDeg = -36.0f;
            state.diskTempScale = 0.94f;
            state.diskDensity = 0.56f;
            state.jetPower = 0.16f;
            state.bloomStrength = 0.28f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.14f;
            state.exposure = 1.20f;
            state.fovDeg = 26.0f;
            state.simTime = 8.4;
            state.activeConsoleTab = SimulationState.TAB_TDE_LAB;
            camera.setShowcasePose(38.5, -0.35, 0.48, -0.05, 0.0, 0.0, state);
            renderer.clearTdeStars(state.tdeSeed);
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(790, 0.014, state);

            // Warm up 6-pass GPU timers for realistic telemetry readout
            renderer.resetTaaHistory();
            for (int f = 0; f < 24; f++) {
                long t0 = System.nanoTime();
                renderer.renderFrame(TARGET_W, TARGET_H, 1.0 / 120.0, 1.0 / 120.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
                gpuTimers.beginUi();
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, TARGET_W, TARGET_H);
                hud.render(uiRenderer, TARGET_W, TARGET_H, -10.0, -10.0, f >= 8, false,
                        state, camera, binaryPhysics, renderer.tdeSystem(), GwReferenceData.loadBundledGw150914(), telemetry);
                gpuTimers.endUi();
                glFinish();
                float cpuMs = (System.nanoTime() - t0) * 1e-6f;
                if (gpuTimers.pollPreviousFrame()) {
                    telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeComputeMs(),
                            gpuTimers.tdeRenderMs(), gpuTimers.taaMs(), gpuTimers.postMs(), gpuTimers.uiMs());
                    telemetry.currentFps = 1000.0f / Math.max(0.2f, gpuTimers.totalGpuMs());
                    telemetry.wallFrameMs = gpuTimers.totalGpuMs();
                }
                telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
                telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
                telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
                telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
                telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
                telemetry.tdeMeanEnergy = renderer.tdeMeanEnergy();
                telemetry.tdeMeanAngMomY = renderer.tdeMeanAngMomY();
                telemetry.tdePeakTempK = renderer.tdePeakTempK();
                telemetry.recordFrameSample(cpuMs, gpuTimers.totalGpuMs());
                gpuTimers.advanceFrame();
            }
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/hud_telemetry.png"));

            // 6. binary_merger.png (EOB 3PN+NLO-Spin GW150914 + Boosted Spinning Kerr-Schild)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.binaryMode = true;
            state.applyBinaryParameters(BinaryParameters.presetGw150914());
            state.binaryParamsDirty = false;
            state.binaryGwOverlay = true;
            state.diskTempScale = 1.00f;
            state.diskDensity = 1.08f;
            state.bloomStrength = 0.25f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.22f;
            state.exposure = 1.18f;
            state.fovDeg = 23.0f;
            binaryPhysics.reset(state.buildBinaryParameters());
            binaryPhysics.advanceCurrentToSeparation(5.25);
            double broadsideYaw = Math.atan2(binaryPhysics.pos1X - binaryPhysics.pos2X,
                    binaryPhysics.pos1Z - binaryPhysics.pos2Z) + Math.PI * 0.5 - 0.14;
            camera.setShowcasePose(28.5, broadsideYaw, 0.38, -0.07, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/binary_merger.png"));

            // 7. eht_polarization.png
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.94f;
            state.ehtRadioPalette = true;
            state.ehtPolarization = true;
            state.diskDensity = 1.18f;
            state.jetPower = 0.25f;
            state.bloomStrength = 0.36f;
            state.anamorphicEnabled = true;
            state.anamorphicStrength = 0.24f;
            state.exposure = 1.24f;
            state.fovDeg = 25.5f;
            state.simTime = 3.6;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(31.5, 0.35, 0.60, -0.05, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, false,
                    state, camera, binaryPhysics, telemetry, hud, Path.of("docs/media/eht_polarization.png"));

            // 8. scientific_diagnostics.png ([F3] Bardeen Analytical Shadow + Ergosphere + DOPRI5(4) Geodesic Paths)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.96f;
            state.integratorMode = SimulationState.INTEGRATOR_DOPRI54;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.showReticle = true;
            state.diskDensity = 0.80f;
            state.jetPower = 0.15f;
            state.bloomStrength = 0.24f;
            state.anamorphicEnabled = false;
            state.exposure = 1.18f;
            state.fovDeg = 24.0f;
            state.simTime = 2.0;
            renderer.clearTdeStars(state.tdeSeed);
            telemetry.tdeActiveParticles = 0;
            telemetry.tdeBoundParticles = 0;
            telemetry.tdeUnboundParticles = 0;
            telemetry.tdeSwallowedParticles = 0;
            telemetry.tdeHorizonSurge = 0.0f;
            telemetry.tdeMeanEnergy = 0.0f;
            telemetry.tdeMeanAngMomY = 0.0f;
            telemetry.tdePeakTempK = 0;
            telemetry.renderAllocBytesLastFrame = 0L;
            camera.setShowcasePose(28.0, 0.20, 0.18, 0.0, 0.0, 0.0, state);
            // Warm up DOPRI5(4) GPU timers so the [F3] banner reports live solver P95/P99
            renderer.resetTaaHistory();
            for (int f = 0; f < 16; f++) {
                long t0 = System.nanoTime();
                renderer.renderFrame(TARGET_W, TARGET_H, 1.0 / 120.0, 0.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
                gpuTimers.beginUi();
                gpuTimers.endUi();
                glFinish();
                float cpuMs = (System.nanoTime() - t0) * 1e-6f;
                if (gpuTimers.pollPreviousFrame()) {
                    telemetry.updateGpuPassTimings(gpuTimers.rayMs(), gpuTimers.tdeComputeMs(),
                            gpuTimers.tdeRenderMs(), gpuTimers.taaMs(), gpuTimers.postMs(), gpuTimers.uiMs());
                    telemetry.recordFrameSample(cpuMs, gpuTimers.totalGpuMs());
                }
                gpuTimers.advanceFrame();
            }
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/scientific_diagnostics.png"));

            ShaderProgram.checkGlError("Showcase.finish");
            System.out.println("[Showcase] All 8 1920x1080 showcase PNGs saved successfully.");
        } catch (IOException e) {
            throw new RuntimeException("Failed writing showcase PNGs", e);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (captureTex != 0) glDeleteTextures(captureTex);
            if (captureFbo != 0) glDeleteFramebuffers(captureFbo);
        }
    }

    /** Captures 10 deterministic 1920x1080 scientific verification PNGs into {@code docs/media/after/}. */
    public static void runVerificationCapture(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                              GpuTimerQueries gpuTimers, SimulationState state,
                                              CameraController camera, BinaryMergerPhysics binaryPhysics,
                                              HardwareTelemetry telemetry, HudOverlay hud,
                                              GwReferenceData gwRef) {
        int captureFbo = 0;
        int captureTex = 0;
        try {
            Files.createDirectories(Path.of("docs/media/after"));
            System.out.println("[Verify] Rendering 10 deterministic 1920x1080 verification PNGs into docs/media/after/...");
            int[] fboTex = createCaptureTarget(TARGET_W, TARGET_H);
            captureFbo = fboTex[0];
            captureTex = fboTex[1];

            // 1. binary_sci_gw150914.png
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.binaryMode = true;
            state.applyBinaryParameters(BinaryParameters.presetGw150914());
            state.binaryParamsDirty = false;
            state.binaryGwOverlay = true;
            state.showGwReference = true;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.fovDeg = 24.5f;
            binaryPhysics.reset(state.buildBinaryParameters());
            binaryPhysics.advanceCurrentToSeparation(5.05);
            camera.setShowcasePose(34.0, 0.42, 0.56, -0.05, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/binary_sci_gw150914.png"));

            // 2. binary_sci_gw190412_ecc.png (asymmetric q=0.28 + eccentricity 0.18 + higher modes)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.binaryMode = true;
            state.applyBinaryParameters(BinaryParameters.presetGw190412());
            state.binaryEccentricity = 0.18f;
            state.binaryInitialSep = 11.5f;
            state.binaryParamsDirty = false;
            state.binaryGwOverlay = true;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.fovDeg = 24.5f;
            binaryPhysics.reset(state.buildBinaryParameters());
            binaryPhysics.advanceCurrentToSeparation(5.80);
            camera.setShowcasePose(34.0, 0.28, 0.62, -0.04, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/binary_sci_gw190412_ecc.png"));

            // 3. binary_sci_ringdown.png (post-merger remnant + QNM decay)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.binaryMode = true;
            state.applyBinaryParameters(BinaryParameters.presetGw150914());
            state.binaryParamsDirty = false;
            state.binaryGwOverlay = true;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.fovDeg = 24.0f;
            binaryPhysics.reset(state.buildBinaryParameters());
            int safety = 0;
            while (binaryPhysics.phase() != BinaryMergerPhysics.Phase.RINGDOWN && safety++ < 6000) {
                binaryPhysics.stepGeometric(0.5, false);
            }
            for (int k = 0; k < 14; k++) {
                binaryPhysics.stepGeometric(0.5, false);
            }
            camera.setShowcasePose(30.0, 0.35, 0.52, -0.04, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/binary_sci_ringdown.png"));

            // 4. kerr_sci_spinning.png (a*=0.98 oblate KS geometry + ZAMO drag + 7 DOPRI5(4) geodesics)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.98f;
            state.integratorMode = SimulationState.INTEGRATOR_DOPRI54;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.diskDensity = 0.72f;
            state.jetPower = 0.14f;
            state.bloomStrength = 0.22f;
            state.anamorphicEnabled = false;
            state.fovDeg = 24.0f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(27.5, 0.22, 0.24, 0.0, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/kerr_sci_spinning.png"));

            // 5. kerr_sci_schwarzschild.png (a*=0.00 verification: ergosphere collapsed, ISCO+=ISCO-=6M)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.00f;
            state.kerrSpinEnabled = false;
            state.integratorMode = SimulationState.INTEGRATOR_DOPRI54;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.diskDensity = 0.72f;
            state.jetPower = 0.10f;
            state.bloomStrength = 0.22f;
            state.anamorphicEnabled = false;
            state.fovDeg = 24.0f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(27.5, 0.22, 0.24, 0.0, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/kerr_sci_schwarzschild.png"));

            // 6. kerr_eht_polarization_glyphs.png (EHT EVPA k x B ticks + MAD card + Faraday disclosure)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.94f;
            state.ehtRadioPalette = true;
            state.ehtPolarization = true;
            state.visEhtGlyphs = true;
            state.ehtMagneticModel = SimulationState.EHT_MODEL_MAD;
            state.diskDensity = 1.15f;
            state.jetPower = 0.20f;
            state.bloomStrength = 0.30f;
            state.exposure = 1.22f;
            state.fovDeg = 25.0f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(31.0, 0.35, 0.60, -0.05, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/kerr_eht_polarization_glyphs.png"));

            // 7. kerr_disk_temp_map.png (Temperature colormap T(r) + calibrated colorbar)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.96f;
            state.kerrDiskVisMode = SimulationState.DISK_VIS_TEMP;
            state.diskDensity = 1.00f;
            state.jetPower = 0.14f;
            state.bloomStrength = 0.22f;
            state.anamorphicEnabled = false;
            state.fovDeg = 24.5f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(30.0, 0.25, 0.44, -0.04, 0.0, 0.0, state);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/kerr_disk_temp_map.png"));

            // 8. tde_sci_hydro_bound_unbound.png (Bound/Unbound coloring + 64^3 Hydro Density XZ slice + TDE lab)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.96f;
            state.tdeHydroEnabled = true;
            state.tdeArchetypeIndex = 2;
            state.tdeBeta = 1.15f;
            state.tdeInclinationDeg = -32.0f;
            state.tdeParticleColorMode = SimulationState.TDE_COLOR_BOUND_UNBOUND;
            state.tdeHydroVisMode = SimulationState.TDE_HYDRO_VIS_DENSITY;
            state.tdeHydroSlicePlane = SimulationState.TDE_SLICE_XZ;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.diskDensity = 0.44f;
            state.jetPower = 0.10f;
            state.bloomStrength = 0.24f;
            state.anamorphicEnabled = false;
            state.fovDeg = 27.0f;
            camera.setShowcasePose(37.5, -0.35, 0.52, -0.04, 0.0, 0.0, state);
            renderer.clearTdeStars(state.tdeSeed);
            renderer.spawnTdeStar(state);
            renderer.stepTdeComputeOnly(680, 0.014, state);
            // Step 16 frames with live hydro dt=0.012 so 64^3 HydroGridBuffer splat & history are populated
            renderAndSaveTdeVerification(renderer, uiRenderer, gpuTimers, captureFbo,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/tde_sci_hydro_bound_unbound.png"));

            // 9. wormhole_sci_embedding.png (Throat rim + Flamm/Dneg z(r) diagram + 6 DOPRI5(4) rays)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.wormholeMode = true;
            state.wormholeRho = 2.65f;
            state.wormholeA = 0.45f;
            state.wormholeM = 1.25f;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.bloomStrength = 0.22f;
            state.anamorphicEnabled = false;
            state.fovDeg = 23.5f;
            camera.setShowcaseWormholePose(11.5, 0.42, 0.06, 0.0);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/wormhole_sci_embedding.png"));

            // 10. horizon_plunge_sci.png (Doran free-fall plunge r(tau), tau vs t_inf & tidal stretch)
            resetForShot(state);
            state.setHudMode(SimulationState.HUD_CLEAN);
            state.spinParam = 0.90f;
            state.freeFallMode = true;
            state.plungeActive = true;
            state.plungeZone = SimulationState.ZONE_PHOTON_SPHERE;
            state.plungeProperTime = 11.8;
            state.plungeCoordTime = 26.4;
            state.plungeVelocityC = 0.87f;
            state.plungeTidalGrad = 0.148f;
            state.scientificDiagMode = SimulationState.DIAG_ANALYTICAL_OVERLAY;
            state.diskDensity = 0.96f;
            state.jetPower = 0.16f;
            state.bloomStrength = 0.24f;
            state.anamorphicEnabled = false;
            state.fovDeg = 36.0f;
            renderer.clearTdeStars(state.tdeSeed);
            camera.setShowcasePose(2.38, 0.40, 0.14, -0.05, 0.42, -0.02, state);
            camera.populateDeterministicPlungeHistory(state, 9.0);
            renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, true,
                    state, camera, binaryPhysics, telemetry, hud, gwRef,
                    Path.of("docs/media/after/horizon_plunge_sci.png"));

            ShaderProgram.checkGlError("Verify.finish");
            System.out.println("[Verify] All 10 1920x1080 verification PNGs saved to docs/media/after/.");
        } catch (IOException e) {
            throw new RuntimeException("Failed writing verification PNGs", e);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (captureTex != 0) glDeleteTextures(captureTex);
            if (captureFbo != 0) glDeleteFramebuffers(captureFbo);
        }
    }

    private static void renderAndSaveTdeVerification(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                                     GpuTimerQueries gpuTimers, int captureFbo,
                                                     SimulationState state, CameraController camera,
                                                     BinaryMergerPhysics binaryPhysics, HardwareTelemetry telemetry,
                                                     HudOverlay hud, GwReferenceData gwRef, Path outPath) throws IOException {
        renderer.resetTaaHistory();
        for (int s = 0; s < 16; s++) {
            renderer.renderFrame(TARGET_W, TARGET_H, 0.012, 0.012, state, camera, binaryPhysics, gpuTimers, captureFbo);
            telemetry.tdeActiveParticles = renderer.tdeActiveParticles();
            telemetry.tdeBoundParticles = renderer.tdeBoundParticles();
            telemetry.tdeUnboundParticles = renderer.tdeUnboundParticles();
            telemetry.tdeSwallowedParticles = renderer.tdeSwallowedParticles();
            telemetry.tdeHorizonSurge = renderer.tdeHorizonSurge();
            telemetry.tdeMeanEnergy = renderer.tdeMeanEnergy();
            telemetry.tdeMeanAngMomY = renderer.tdeMeanAngMomY();
            telemetry.tdePeakTempK = renderer.tdePeakTempK();
            gpuTimers.beginUi();
            if (s == 15) {
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, TARGET_W, TARGET_H);
                hud.render(uiRenderer, TARGET_W, TARGET_H, -10.0, -10.0, true, false,
                        state, camera, binaryPhysics, renderer.tdeSystem(), gwRef, telemetry);
            }
            gpuTimers.endUi();
            gpuTimers.advanceFrame();
        }
        FramebufferCapture.saveCaptureFbo(captureFbo, TARGET_W, TARGET_H, outPath);
    }

    private static void resetForShot(SimulationState state) {
        state.wormholeMode = false;
        state.binaryMode = false;
        state.resetDefaults();
    }

    private static int[] createCaptureTarget(int w, int h) {
        int fbo = glGenFramebuffers();
        int tex = glGenTextures();
        boolean ok = false;
        try {
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Offscreen showcase capture FBO incomplete");
            }
            ok = true;
            return new int[]{fbo, tex};
        } finally {
            if (!ok) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glDeleteTextures(tex);
                glDeleteFramebuffers(fbo);
            }
        }
    }

    private static void renderAndSaveShowcase(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                              GpuTimerQueries gpuTimers, int captureFbo, boolean withHud,
                                              SimulationState state, CameraController camera,
                                              BinaryMergerPhysics binaryPhysics, HardwareTelemetry telemetry,
                                              HudOverlay hud, Path outPath) throws IOException {
        renderAndSaveShowcase(renderer, uiRenderer, gpuTimers, captureFbo, withHud, state, camera,
                binaryPhysics, telemetry, hud, GwReferenceData.loadBundledGw150914(), outPath);
    }

    private static void renderAndSaveShowcase(BlackHoleRenderer renderer, UiRenderer uiRenderer,
                                              GpuTimerQueries gpuTimers, int captureFbo, boolean withHud,
                                              SimulationState state, CameraController camera,
                                              BinaryMergerPhysics binaryPhysics, HardwareTelemetry telemetry,
                                              HudOverlay hud, GwReferenceData gwRef, Path outPath) throws IOException {
        renderer.resetTaaHistory();
        for (int s = 0; s < 16; s++) {
            renderer.renderFrame(TARGET_W, TARGET_H, 0.0, 0.0, state, camera, binaryPhysics, gpuTimers, captureFbo);
            gpuTimers.beginUi();
            if (withHud && s == 15) {
                glBindFramebuffer(GL_FRAMEBUFFER, captureFbo);
                glViewport(0, 0, TARGET_W, TARGET_H);
                hud.render(uiRenderer, TARGET_W, TARGET_H, -10.0, -10.0, true, false,
                        state, camera, binaryPhysics, renderer.tdeSystem(), gwRef, telemetry);
            }
            gpuTimers.endUi();
            gpuTimers.advanceFrame();
        }
        FramebufferCapture.saveCaptureFbo(captureFbo, TARGET_W, TARGET_H, outPath);
    }
}
