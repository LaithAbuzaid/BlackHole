package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.math.PlanckBlackbody;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * Manages GPU startup precomputations (128^3 3D turbulence volume, dual mipmapped
 * 1024x1024 HDR skybox cubemaps, and 512x1 RGB16F Planckian blackbody LUT) and
 * executes the zero-allocation multi-pass Kerr-Schild / Morris-Thorne raytrace,
 * OpenGL 4.3 TDE compute + HDR sprite pass, 5-level Karis bloom pyramid, and ACES composite.
 */
public final class BlackHoleRenderer implements AutoCloseable {

    public static final int BLOOM_LEVELS = 5;
    private static final int NOISE_VOL_SIZE = 128;
    private static final int SKYBOX_FACE_SIZE = 1024;
    private static final int BLACKBODY_LUT_WIDTH = 512;

    // 16-phase low-discrepancy Halton(2, 3) sub-pixel TAA jitter sequence in [-0.5, +0.5) px
    private static final float[] HALTON_X = {
        0.000000f, -0.250000f,  0.250000f, -0.375000f,
        0.125000f, -0.125000f,  0.375000f, -0.437500f,
        0.062500f, -0.187500f,  0.312500f, -0.312500f,
        0.187500f, -0.062500f,  0.437500f, -0.468750f
    };
    private static final float[] HALTON_Y = {
       -0.166667f,  0.166667f, -0.388889f, -0.055556f,
        0.277778f, -0.277778f,  0.055556f,  0.388889f,
       -0.462963f, -0.129630f,  0.203704f, -0.351852f,
       -0.018519f,  0.314815f, -0.240741f,  0.092593f
    };

    private final ShaderProgram rayProgram;
    private final ShaderProgram rayDopriProgram;
    private final ShaderProgram wormholeProgram;
    private final ShaderProgram binaryProgram;
    private final ShaderProgram binaryDopriProgram;
    private final ShaderProgram taaProgram;
    private final ShaderProgram downsampleProgram;
    private final ShaderProgram upsampleProgram;
    private final ShaderProgram anamorphicProgram;
    private final ShaderProgram compositeProgram;
    private final TidalDisruptionSystem tdeSystem;

    private final int quadVao;
    private final int noise3dTex;
    private final int blackbodyLutTex;
    private final int skyboxUniverseA;
    private final int skyboxUniverseB;
    private RenderTargets renderTargets;

    // Temporal Anti-Aliasing (TAA) state (zero per-frame heap allocations)
    private int frameIndex = 0;
    private int taaWriteIdx = 0;
    private int taaAccumCount = 0;
    private boolean taaHistoryValid = false;
    private float prevCamPosX, prevCamPosY, prevCamPosZ;
    private float prevCamFwdX, prevCamFwdY, prevCamFwdZ;
    private float prevCamRightX, prevCamRightY, prevCamRightZ;
    private float prevCamUpX, prevCamUpY, prevCamUpZ;
    private float prevTanHalfFov = 0.4f;
    private boolean prevWormholeMode;
    private boolean prevBinaryMode;
    private boolean prevBinaryGwOverlay;
    private boolean prevOrderVis;
    private boolean prevEhtPol;
    private boolean prevEhtRadio;
    private boolean prevShowGrid;
    private boolean prevLensing;
    private boolean prevShowDisk;
    private boolean prevShowJets;
    private boolean prevDoppler;
    private boolean prevKerrSpin;
    private boolean prevFreeFall;
    private int prevIntegratorMode;
    private int prevDiagMode;
    private int prevKerrDiskVisMode;
    private int prevEhtModel;
    private int prevBinModeFilter;
    private int prevTdeColorMode;
    private int prevTdeHydroVisMode;
    private int prevTdeSlicePlane;
    private float prevWormholeL;
    private boolean closed = false;

    // Cached Black Hole uniform locations (separate specialized RK4 vs DOPRI5(4) programs for optimal GPU VGPR occupancy)
    private static final class KerrUniforms {
        final int uResolution, uTime, uCamPos, uCamForward, uCamRight, uCamUp;
        final int uSkybox, uNoiseVol, uBlackbodyLut;
        final int uSpinA, uHorizonR, uIscoR, uDiskTempScale, uDiskDensity, uJetPower, uTanHalfFov;
        final int uCauchyFlash, uSpaghettify, uSingularityFade, uAccretionSurge;
        final int uDoppler, uLensing, uShowDisk, uShowJets, uFreeFall, uShowGrid;
        final int uOrderVis, uEhtPolarization, uEhtRadio, uDiskVisMode, uEhtModel, uIntegTol, uDiagMode, uJitter, uFrameIndex;

        KerrUniforms(ShaderProgram prog, boolean isDopri) {
            uResolution = prog.uniformLocation("uResolution");
            uTime = prog.uniformLocation("uTime");
            uCamPos = prog.uniformLocation("uCamPos");
            uCamForward = prog.uniformLocation("uCamForward");
            uCamRight = prog.uniformLocation("uCamRight");
            uCamUp = prog.uniformLocation("uCamUp");
            uSkybox = prog.uniformLocation("uSkybox");
            uNoiseVol = prog.uniformLocation("uNoiseVol");
            uBlackbodyLut = prog.uniformLocation("uBlackbodyLut");
            uSpinA = prog.uniformLocation("uSpinA");
            uHorizonR = prog.uniformLocation("uHorizonR");
            uIscoR = prog.uniformLocation("uIscoR");
            uDiskTempScale = prog.uniformLocation("uDiskTempScale");
            uDiskDensity = prog.uniformLocation("uDiskDensity");
            uJetPower = prog.uniformLocation("uJetPower");
            uTanHalfFov = prog.uniformLocation("uTanHalfFov");
            uCauchyFlash = prog.uniformLocation("uCauchyFlash");
            uSpaghettify = prog.uniformLocation("uSpaghettify");
            uSingularityFade = prog.uniformLocation("uSingularityFade");
            uAccretionSurge = prog.uniformLocation("uAccretionSurge");
            uDoppler = prog.uniformLocation("uDoppler");
            uLensing = prog.uniformLocation("uLensing");
            uShowDisk = prog.uniformLocation("uShowDisk");
            uShowJets = prog.uniformLocation("uShowJets");
            uFreeFall = prog.uniformLocation("uFreeFall");
            uShowGrid = prog.uniformLocation("uShowGrid");
            uOrderVis = prog.uniformLocation("uOrderVis");
            uEhtPolarization = prog.uniformLocation("uEhtPolarization");
            uEhtRadio = prog.uniformLocation("uEhtRadio");
            uDiskVisMode = prog.uniformLocation("uDiskVisMode");
            uEhtModel = prog.uniformLocation("uEhtModel");
            uIntegTol = isDopri ? prog.uniformLocation("uIntegTol") : -1;
            uDiagMode = prog.uniformLocation("uDiagMode");
            uJitter = prog.uniformLocation("uJitter");
            uFrameIndex = prog.uniformLocation("uFrameIndex");
        }
    }

    private final KerrUniforms kerrRk4Uniforms;
    private final KerrUniforms kerrDopriUniforms;

    // Cached Wormhole uniform locations
    private final int uWhResolution;
    private final int uWhCamRadial;
    private final int uWhCamForward;
    private final int uWhCamRight;
    private final int uWhCamUp;
    private final int uWhSkyboxA;
    private final int uWhSkyboxB;
    private final int uWhL;
    private final int uWhRho;
    private final int uWhA;
    private final int uWhM;
    private final int uWhTanHalfFov;
    private final int uWhShowGrid;
    private final int uWhDiagMode;
    private final int uWhJitter;

    // Cached Binary Merger uniform locations (specialized RK4 vs DOPRI5(4) programs)
    private static final class BinaryUniforms {
        final int uResolution, uTime, uGeomDt, uCamPos, uCamForward, uCamRight, uCamUp, uTanHalfFov;
        final int uPrevCamPos, uPrevCamForward, uPrevCamRight, uPrevCamUp, uPrevTanHalfFov;
        final int uSkybox, uNoiseVol, uBlackbodyLut;
        final int uPos1, uPos2, uVel1, uVel2, uMass1, uMass2, uChi1, uChi2;
        final int uRemnantMass, uRemnantSpin, uMergeBlend, uSeparation, uOrbitalPhase;
        final int uGwPhase, uGwOmegaGeom, uStrainAmp, uRingdownEnv, uMergerPulse;
        final int uOrbE1, uOrbLHat, uOrbE3;
        final int uDiskTempScale, uDiskDensity, uDoppler, uLensing, uShowDisk, uShowGrid, uGwOverlay;
        final int uModeAmp, uModePhase, uGwModeFilter, uGwVisScale, uDiskVisMode;
        final int uDiagMode, uIntegTol, uJitter, uFrameIndex;

        BinaryUniforms(ShaderProgram prog, boolean isDopri) {
            uResolution = prog.uniformLocation("uResolution");
            uTime = prog.uniformLocation("uTime");
            uGeomDt = prog.uniformLocation("uGeomDt");
            uCamPos = prog.uniformLocation("uCamPos");
            uCamForward = prog.uniformLocation("uCamForward");
            uCamRight = prog.uniformLocation("uCamRight");
            uCamUp = prog.uniformLocation("uCamUp");
            uTanHalfFov = prog.uniformLocation("uTanHalfFov");
            uPrevCamPos = prog.uniformLocation("uPrevCamPos");
            uPrevCamForward = prog.uniformLocation("uPrevCamForward");
            uPrevCamRight = prog.uniformLocation("uPrevCamRight");
            uPrevCamUp = prog.uniformLocation("uPrevCamUp");
            uPrevTanHalfFov = prog.uniformLocation("uPrevTanHalfFov");
            uSkybox = prog.uniformLocation("uSkybox");
            uNoiseVol = prog.uniformLocation("uNoiseVol");
            uBlackbodyLut = prog.uniformLocation("uBlackbodyLut");
            uPos1 = prog.uniformLocation("uPos1");
            uPos2 = prog.uniformLocation("uPos2");
            uVel1 = prog.uniformLocation("uVel1");
            uVel2 = prog.uniformLocation("uVel2");
            uMass1 = prog.uniformLocation("uMass1");
            uMass2 = prog.uniformLocation("uMass2");
            uChi1 = prog.uniformLocation("uChi1");
            uChi2 = prog.uniformLocation("uChi2");
            uRemnantMass = prog.uniformLocation("uRemnantMass");
            uRemnantSpin = prog.uniformLocation("uRemnantSpin");
            uMergeBlend = prog.uniformLocation("uMergeBlend");
            uSeparation = prog.uniformLocation("uSeparation");
            uOrbitalPhase = prog.uniformLocation("uOrbitalPhase");
            uGwPhase = prog.uniformLocation("uGwPhase");
            uGwOmegaGeom = prog.uniformLocation("uGwOmegaGeom");
            uStrainAmp = prog.uniformLocation("uStrainAmp");
            uRingdownEnv = prog.uniformLocation("uRingdownEnv");
            uMergerPulse = prog.uniformLocation("uMergerPulse");
            uOrbE1 = prog.uniformLocation("uOrbE1");
            uOrbLHat = prog.uniformLocation("uOrbLHat");
            uOrbE3 = prog.uniformLocation("uOrbE3");
            uDiskTempScale = prog.uniformLocation("uDiskTempScale");
            uDiskDensity = prog.uniformLocation("uDiskDensity");
            uDoppler = prog.uniformLocation("uDoppler");
            uLensing = prog.uniformLocation("uLensing");
            uShowDisk = prog.uniformLocation("uShowDisk");
            uShowGrid = prog.uniformLocation("uShowGrid");
            uGwOverlay = prog.uniformLocation("uGwOverlay");
            uModeAmp = prog.uniformLocation("uModeAmp");
            uModePhase = prog.uniformLocation("uModePhase");
            uGwModeFilter = prog.uniformLocation("uGwModeFilter");
            uGwVisScale = prog.uniformLocation("uGwVisScale");
            uDiskVisMode = prog.uniformLocation("uDiskVisMode");
            uDiagMode = prog.uniformLocation("uDiagMode");
            uIntegTol = isDopri ? prog.uniformLocation("uIntegTol") : -1;
            uJitter = prog.uniformLocation("uJitter");
            uFrameIndex = prog.uniformLocation("uFrameIndex");
        }
    }

    private final BinaryUniforms binaryRk4Uniforms;
    private final BinaryUniforms binaryDopriUniforms;

    // Cached TAA Resolve uniform locations
    private final int uTaaCurrent;
    private final int uTaaHistory;
    private final int uTaaVelocity;
    private final int uTaaUseVelocity;
    private final int uTaaResolution;
    private final int uTaaCamPos;
    private final int uTaaCamForward;
    private final int uTaaCamRight;
    private final int uTaaCamUp;
    private final int uTaaTanHalfFov;
    private final int uTaaPrevCamPos;
    private final int uTaaPrevCamForward;
    private final int uTaaPrevCamRight;
    private final int uTaaPrevCamUp;
    private final int uTaaPrevTanHalfFov;
    private final int uTaaResetHistory;
    private final int uTaaMinAlpha;

    // Bloom, Anamorphic & Composite uniform locations
    private final int uDownSource;
    private final int uIsFirstPass;
    private final int uUpSource;
    private final int uFilterRadius;
    private final int uAnaSource;
    private final int uAnaStepScale;
    private final int uAnaExtractThreshold;
    private final int uCompHdr;
    private final int uCompBloom;
    private final int uCompAnamorphic;
    private final int uCompStrength;
    private final int uCompAnamorphicStrength;
    private final int uCompExposure;

    public BlackHoleRenderer(int initialWidth, int initialHeight) {
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        this.rayProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/simulation/kerr_blackhole.frag");
        this.rayDopriProgram = ShaderProgram.fromResourcesWithDefines(
                "/shaders/common/fullscreen.vert", "/shaders/simulation/kerr_blackhole.frag", "#define USE_DOPRI54 1");
        this.wormholeProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/simulation/morris_thorne_wormhole.frag");
        this.binaryProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/simulation/binary_merger.frag");
        this.binaryDopriProgram = ShaderProgram.fromResourcesWithDefines(
                "/shaders/common/fullscreen.vert", "/shaders/simulation/binary_merger.frag", "#define USE_DOPRI54 1");
        this.taaProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/post/taa_resolve.frag");
        this.downsampleProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/post/bloom_downsample.frag");
        this.upsampleProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/post/bloom_upsample.frag");
        this.anamorphicProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/post/anamorphic_blur.frag");
        this.compositeProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/post/tonemap_composite.frag");
        this.tdeSystem = new TidalDisruptionSystem();

        this.quadVao = glGenVertexArrays();
        glBindVertexArray(quadVao);

        this.blackbodyLutTex = createBlackbodyLutTexture(BLACKBODY_LUT_WIDTH);

        try (ShaderProgram noiseProg = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/bake/noise_volume.frag")) {
            this.noise3dTex = bake3dNoiseVolume(noiseProg, NOISE_VOL_SIZE);
        }

        try (ShaderProgram skyProgA = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/bake/skybox_milkyway.frag")) {
            this.skyboxUniverseA = bakeHdrSkyboxCubemap(skyProgA, noise3dTex, SKYBOX_FACE_SIZE, "UniverseA");
        }

        try (ShaderProgram skyProgB = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/bake/skybox_nebula.frag")) {
            this.skyboxUniverseB = bakeHdrSkyboxCubemap(skyProgB, noise3dTex, SKYBOX_FACE_SIZE, "UniverseB");
        }

        this.renderTargets = new RenderTargets(initialWidth, initialHeight, BLOOM_LEVELS);
        ShaderProgram.checkGlError("BlackHoleRenderer.init");

        // Cache Black Hole uniform locations for both specialized Kerr programs
        this.kerrRk4Uniforms = new KerrUniforms(rayProgram, false);
        this.kerrDopriUniforms = new KerrUniforms(rayDopriProgram, true);

        // Cache Wormhole uniform locations
        uWhResolution = wormholeProgram.uniformLocation("uResolution");
        uWhCamRadial = wormholeProgram.uniformLocation("uCamRadial");
        uWhCamForward = wormholeProgram.uniformLocation("uCamForward");
        uWhCamRight = wormholeProgram.uniformLocation("uCamRight");
        uWhCamUp = wormholeProgram.uniformLocation("uCamUp");
        uWhSkyboxA = wormholeProgram.uniformLocation("uSkyboxA");
        uWhSkyboxB = wormholeProgram.uniformLocation("uSkyboxB");
        uWhL = wormholeProgram.uniformLocation("uWormholeL");
        uWhRho = wormholeProgram.uniformLocation("uWormholeRho");
        uWhA = wormholeProgram.uniformLocation("uWormholeA");
        uWhM = wormholeProgram.uniformLocation("uWormholeM");
        uWhTanHalfFov = wormholeProgram.uniformLocation("uTanHalfFov");
        uWhShowGrid = wormholeProgram.uniformLocation("uShowGrid");
        uWhDiagMode = wormholeProgram.uniformLocation("uDiagMode");
        uWhJitter = wormholeProgram.uniformLocation("uJitter");

        // Cache Binary Merger uniform locations for both RK4 and DOPRI5(4) programs
        this.binaryRk4Uniforms = new BinaryUniforms(binaryProgram, false);
        this.binaryDopriUniforms = new BinaryUniforms(binaryDopriProgram, true);

        // Cache TAA Resolve uniform locations
        uTaaCurrent = taaProgram.uniformLocation("uCurrentTex");
        uTaaHistory = taaProgram.uniformLocation("uHistoryTex");
        uTaaVelocity = taaProgram.uniformLocation("uVelocityTex");
        uTaaUseVelocity = taaProgram.uniformLocation("uUseVelocityTex");
        uTaaResolution = taaProgram.uniformLocation("uResolution");
        uTaaCamPos = taaProgram.uniformLocation("uCamPos");
        uTaaCamForward = taaProgram.uniformLocation("uCamForward");
        uTaaCamRight = taaProgram.uniformLocation("uCamRight");
        uTaaCamUp = taaProgram.uniformLocation("uCamUp");
        uTaaTanHalfFov = taaProgram.uniformLocation("uTanHalfFov");
        uTaaPrevCamPos = taaProgram.uniformLocation("uPrevCamPos");
        uTaaPrevCamForward = taaProgram.uniformLocation("uPrevCamForward");
        uTaaPrevCamRight = taaProgram.uniformLocation("uPrevCamRight");
        uTaaPrevCamUp = taaProgram.uniformLocation("uPrevCamUp");
        uTaaPrevTanHalfFov = taaProgram.uniformLocation("uPrevTanHalfFov");
        uTaaResetHistory = taaProgram.uniformLocation("uResetHistory");
        uTaaMinAlpha = taaProgram.uniformLocation("uMinAlpha");

        // Cache Bloom, Anamorphic & Composite uniform locations
        uDownSource = downsampleProgram.uniformLocation("uSource");
        uIsFirstPass = downsampleProgram.uniformLocation("uIsFirstPass");
        uUpSource = upsampleProgram.uniformLocation("uSource");
        uFilterRadius = upsampleProgram.uniformLocation("uFilterRadius");

        uAnaSource = anamorphicProgram.uniformLocation("uSource");
        uAnaStepScale = anamorphicProgram.uniformLocation("uStepScale");
        uAnaExtractThreshold = anamorphicProgram.uniformLocation("uExtractThreshold");

        uCompHdr = compositeProgram.uniformLocation("uHdrScene");
        uCompBloom = compositeProgram.uniformLocation("uBloomTex");
        uCompAnamorphic = compositeProgram.uniformLocation("uAnamorphicTex");
        uCompStrength = compositeProgram.uniformLocation("uBloomStrength");
        uCompAnamorphicStrength = compositeProgram.uniformLocation("uAnamorphicStrength");
        uCompExposure = compositeProgram.uniformLocation("uExposure");
    }

    public void resize(int width, int height) {
        // Construct replacement BEFORE closing old RenderTargets so failure never leaves stale handles
        RenderTargets next = new RenderTargets(width, height, BLOOM_LEVELS);
        RenderTargets prev = this.renderTargets;
        this.renderTargets = next;
        if (prev != null) {
            prev.close();
        }
        resetTaaHistory();
    }

    /** Invalidates temporal history cleanly on camera cuts, mode switches, or showcase shots. */
    public void resetTaaHistory() {
        taaHistoryValid = false;
        taaAccumCount = 0;
        frameIndex = 0;
    }

    public void spawnTdeStar(SimulationState state) {
        if (!state.wormholeMode && !state.binaryMode) {
            tdeSystem.spawnStar(state);
        }
    }

    public void clearTdeStars() {
        tdeSystem.clearAll();
    }

    public void clearTdeStars(long resetSeed) {
        tdeSystem.clearAll(resetSeed);
    }

    public void stepTdeComputeOnly(int steps, double dt, SimulationState state) {
        tdeSystem.stepComputeOnly(steps, dt, state);
    }

    public int tdeActiveParticles() { return tdeSystem.activeParticleCount(); }
    public int tdeBoundParticles() { return tdeSystem.boundParticleCount(); }
    public int tdeUnboundParticles() { return tdeSystem.unboundParticleCount(); }
    public int tdeSwallowedParticles() { return tdeSystem.totalSwallowedCount(); }
    public float tdeShearEnergy() { return tdeSystem.shearEnergy(); }
    public float tdeHorizonSurge() { return tdeSystem.horizonAccretionSurge(); }
    public float tdeMeanEnergy() { return tdeSystem.meanSpecificEnergy(); }
    public float tdeMeanAngMomY() { return tdeSystem.meanAngularMomentumY(); }
    public int tdePeakTempK() { return tdeSystem.peakTemperatureK(); }
    public int tdeSubsteps() { return tdeSystem.lastSubstepsPerFrame(); }
    public double tdeParticleStepsPerSec() { return tdeSystem.particleStepsPerSec(); }
    public TidalDisruptionSystem tdeSystem() { return tdeSystem; }

    /** Executes Passes 1, 1B (TDE), 1C (TAA), 2 (Bloom), and 3 (Composite) to default framebuffer with zero heap allocations. */
    public void renderFrame(int width, int height, double wallDt, double simDt,
                            SimulationState state, CameraController camera, GpuTimerQueries timers) {
        renderFrame(width, height, wallDt, simDt, state, camera, null, timers, 0);
    }

    /** Executes Passes 1, 1B (TDE Compute/Render), 1C (TAA), 2 (Bloom + Anamorphic), and 3 (Composite) with zero heap allocations. */
    public void renderFrame(int width, int height, double wallDt, double simDt,
                            SimulationState state, CameraController camera, BinaryMergerPhysics binary,
                            GpuTimerQueries timers, int outputFbo) {
        // Enforce clean OpenGL state hygiene at frame boundary
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBindVertexArray(quadVao);

        int shaderDiagMode = (state.scientificDiagMode == SimulationState.DIAG_HAMILTONIAN_ERR
                || state.scientificDiagMode == SimulationState.DIAG_STEP_DENSITY)
                ? state.scientificDiagMode : 0;

        boolean useTaa = state.adaptiveAa4x;
        boolean runBinary = state.binaryMode && binary != null;
        int phase = frameIndex & 15;
        float jitterX = useTaa ? HALTON_X[phase] : 0.0f;
        float jitterY = useTaa ? HALTON_Y[phase] : 0.0f;
        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));

        // Effective 3D camera origin for exact parallax TAA reprojection (BH origin or wormhole throat)
        float effCamX, effCamY, effCamZ;
        if (state.wormholeMode) {
            float absL = Math.abs((float) camera.currentWormholeL());
            effCamX = camera.radialX * absL;
            effCamY = camera.radialY * absL;
            effCamZ = camera.radialZ * absL;
        } else {
            effCamX = camera.posX;
            effCamY = camera.posY;
            effCamZ = camera.posZ;
        }

        // PASS 1: Exact Kerr-Schild Black Hole, Binary Merger (MRT + Velocity), OR Morris-Thorne Wormhole Ray-Tracer
        timers.beginRay();
        glBindFramebuffer(GL_FRAMEBUFFER, runBinary ? renderTargets.binaryHdrFbo() : renderTargets.hdrFbo());
        glViewport(0, 0, width, height);

        if (runBinary) {
            boolean useDopriBin = (state.integratorMode == SimulationState.INTEGRATOR_DOPRI54);
            BinaryUniforms bu = useDopriBin ? binaryDopriUniforms : binaryRk4Uniforms;
            (useDopriBin ? binaryDopriProgram : binaryProgram).use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(bu.uSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(bu.uNoiseVol, 1);

            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blackbodyLutTex);
            glUniform1i(bu.uBlackbodyLut, 2);

            float binGeomDt = (state.paused || simDt <= 0.0) ? 0.0f : binary.lastStepGeomDt;
            float pCamX = taaHistoryValid ? prevCamPosX : effCamX;
            float pCamY = taaHistoryValid ? prevCamPosY : effCamY;
            float pCamZ = taaHistoryValid ? prevCamPosZ : effCamZ;
            float pFwdX = taaHistoryValid ? prevCamFwdX : camera.forwardX;
            float pFwdY = taaHistoryValid ? prevCamFwdY : camera.forwardY;
            float pFwdZ = taaHistoryValid ? prevCamFwdZ : camera.forwardZ;
            float pRightX = taaHistoryValid ? prevCamRightX : camera.rightX;
            float pRightY = taaHistoryValid ? prevCamRightY : camera.rightY;
            float pRightZ = taaHistoryValid ? prevCamRightZ : camera.rightZ;
            float pUpX = taaHistoryValid ? prevCamUpX : camera.upX;
            float pUpY = taaHistoryValid ? prevCamUpY : camera.upY;
            float pUpZ = taaHistoryValid ? prevCamUpZ : camera.upZ;
            float pFov = taaHistoryValid ? prevTanHalfFov : tanHalfFov;

            glUniform2f(bu.uResolution, width, height);
            glUniform1f(bu.uTime, binary.binaryTime);
            glUniform1f(bu.uGeomDt, binGeomDt);
            glUniform3f(bu.uCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(bu.uCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(bu.uCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(bu.uCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(bu.uTanHalfFov, tanHalfFov);
            glUniform3f(bu.uPrevCamPos, pCamX, pCamY, pCamZ);
            glUniform3f(bu.uPrevCamForward, pFwdX, pFwdY, pFwdZ);
            glUniform3f(bu.uPrevCamRight, pRightX, pRightY, pRightZ);
            glUniform3f(bu.uPrevCamUp, pUpX, pUpY, pUpZ);
            glUniform1f(bu.uPrevTanHalfFov, pFov);

            glUniform3f(bu.uPos1, binary.pos1X, binary.pos1Y, binary.pos1Z);
            glUniform3f(bu.uPos2, binary.pos2X, binary.pos2Y, binary.pos2Z);
            glUniform3f(bu.uVel1, binary.vel1X, binary.vel1Y, binary.vel1Z);
            glUniform3f(bu.uVel2, binary.vel2X, binary.vel2Y, binary.vel2Z);
            glUniform1f(bu.uMass1, binary.mass1);
            glUniform1f(bu.uMass2, binary.mass2);
            glUniform1f(bu.uChi1, binary.chi1);
            glUniform1f(bu.uChi2, binary.chi2);
            glUniform1f(bu.uRemnantMass, binary.remnantMass);
            glUniform1f(bu.uRemnantSpin, binary.remnantSpin);
            glUniform1f(bu.uMergeBlend, binary.mergeBlend);
            glUniform1f(bu.uSeparation, binary.currentSeparation);
            glUniform1f(bu.uOrbitalPhase, binary.currentOrbitalPhase);
            glUniform1f(bu.uGwPhase, binary.gwPhaseOut);
            glUniform1f(bu.uGwOmegaGeom, binary.gwFreqGeom);
            glUniform1f(bu.uStrainAmp, binary.strainAmplitude);
            glUniform1f(bu.uRingdownEnv, binary.ringdownEnv);
            glUniform1f(bu.uMergerPulse, binary.mergerPulse);
            glUniform3f(bu.uOrbE1, binary.orbE1X, binary.orbE1Y, binary.orbE1Z);
            glUniform3f(bu.uOrbLHat, binary.orbLHX, binary.orbLHY, binary.orbLHZ);
            glUniform3f(bu.uOrbE3, binary.orbE3X, binary.orbE3Y, binary.orbE3Z);

            glUniform1f(bu.uDiskTempScale, state.diskTempScale);
            glUniform1f(bu.uDiskDensity, state.diskDensity);
            glUniform1i(bu.uDoppler, state.dopplerEnabled ? 1 : 0);
            glUniform1i(bu.uLensing, state.lensingEnabled ? 1 : 0);
            glUniform1i(bu.uShowDisk, state.showDisk ? 1 : 0);
            glUniform1i(bu.uShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(bu.uGwOverlay, state.binaryGwOverlay ? 1 : 0);
            glUniform4f(bu.uModeAmp, (float) binary.modeAmp22, (float) binary.modeAmp21,
                    (float) binary.modeAmp33, (float) binary.modeAmp44);
            glUniform4f(bu.uModePhase, (float) binary.modePhase22, (float) binary.modePhase21,
                    (float) binary.modePhase33, (float) binary.modePhase44);
            glUniform1i(bu.uGwModeFilter, state.binGwModeFilter);
            glUniform1f(bu.uGwVisScale, 1.0f);
            glUniform1i(bu.uDiskVisMode, state.kerrDiskVisMode);
            glUniform1i(bu.uDiagMode, shaderDiagMode);
            if (useDopriBin) {
                glUniform1f(bu.uIntegTol, state.integratorRtol);
            }
            glUniform2f(bu.uJitter, jitterX, jitterY);
            glUniform1i(bu.uFrameIndex, frameIndex);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else if (state.wormholeMode) {
            wormholeProgram.use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(uWhSkyboxA, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseB);
            glUniform1i(uWhSkyboxB, 1);

            glUniform2f(uWhResolution, width, height);
            glUniform3f(uWhCamRadial, camera.radialX, camera.radialY, camera.radialZ);
            glUniform3f(uWhCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uWhCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uWhCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uWhL, (float) camera.currentWormholeL());
            glUniform1f(uWhRho, state.wormholeRho);
            glUniform1f(uWhA, state.wormholeA);
            glUniform1f(uWhM, state.wormholeM);
            glUniform1f(uWhTanHalfFov, tanHalfFov);
            glUniform1i(uWhShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(uWhDiagMode, shaderDiagMode);
            glUniform2f(uWhJitter, jitterX, jitterY);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else {
            boolean useDopri = (state.integratorMode == SimulationState.INTEGRATOR_DOPRI54);
            KerrUniforms ku = useDopri ? kerrDopriUniforms : kerrRk4Uniforms;
            (useDopri ? rayDopriProgram : rayProgram).use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(ku.uSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(ku.uNoiseVol, 1);

            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blackbodyLutTex);
            glUniform1i(ku.uBlackbodyLut, 2);

            glUniform2f(ku.uResolution, width, height);
            glUniform1f(ku.uTime, (float) state.simTime);
            glUniform3f(ku.uCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(ku.uCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(ku.uCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(ku.uCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(ku.uSpinA, state.effectiveSpin());
            glUniform1f(ku.uHorizonR, (float) state.outerHorizonRadius());
            glUniform1f(ku.uIscoR, (float) state.progradeIscoRadius());
            glUniform1f(ku.uDiskTempScale, state.diskTempScale);
            glUniform1f(ku.uDiskDensity, state.diskDensity);
            glUniform1f(ku.uJetPower, state.jetPower);
            glUniform1f(ku.uTanHalfFov, tanHalfFov);
            glUniform1f(ku.uCauchyFlash, state.cauchyFlashIntensity);
            glUniform1f(ku.uSpaghettify, state.spaghettification);
            glUniform1f(ku.uSingularityFade, state.singularityFade);
            glUniform1f(ku.uAccretionSurge, state.tdeHorizonSurge);
            glUniform1i(ku.uDoppler, state.dopplerEnabled ? 1 : 0);
            glUniform1i(ku.uLensing, state.lensingEnabled ? 1 : 0);
            glUniform1i(ku.uShowDisk, state.showDisk ? 1 : 0);
            glUniform1i(ku.uShowJets, state.showJets ? 1 : 0);
            glUniform1i(ku.uFreeFall, (state.freeFallMode || state.plungeActive) ? 1 : 0);
            glUniform1i(ku.uShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(ku.uOrderVis, state.orderVisualizer ? 1 : 0);
            glUniform1i(ku.uEhtPolarization, state.ehtPolarization ? 1 : 0);
            glUniform1i(ku.uEhtRadio, state.ehtRadioPalette ? 1 : 0);
            glUniform1i(ku.uDiskVisMode, state.kerrDiskVisMode);
            glUniform1i(ku.uEhtModel, state.ehtMagneticModel);
            if (useDopri) {
                glUniform1f(ku.uIntegTol, state.integratorRtol);
            }
            glUniform1i(ku.uDiagMode, shaderDiagMode);
            glUniform2f(ku.uJitter, jitterX, jitterY);
            glUniform1i(ku.uFrameIndex, frameIndex);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        timers.endRay();

        // PASS 1B-1: TDE Compute (64^3 Hydro Splat + Single-Dispatch Substepped Verlet-Boris)
        timers.beginTdeCompute();
        if (!runBinary) {
            tdeSystem.dispatchCompute(wallDt, simDt, state);
        }
        timers.endTdeCompute();

        // PASS 1B-2: TDE Volumetric Instanced HDR Plasma Ribbons (suppressed during |H|/Step heatmaps)
        timers.beginTdeRender();
        if (!runBinary && shaderDiagMode == 0) {
            tdeSystem.renderParticles(width, height, blackbodyLutTex, noise3dTex, jitterX, jitterY, state, camera);
        }
        timers.endTdeRender();

        // PASS 1C: Relativistic Temporal Anti-Aliasing (TAA Resolve with Disocclusion & 3x3 Velocity Dilation)
        glBindVertexArray(quadVao);
        timers.beginTaa();

        int resolvedHdrTex = renderTargets.hdrColorTexture();
        float curWhL = (float) camera.currentWormholeL();
        if (useTaa && shaderDiagMode == 0) {
            boolean curFreeFall = state.freeFallMode || state.plungeActive;
            float dxCam = effCamX - prevCamPosX;
            float dyCam = effCamY - prevCamPosY;
            float dzCam = effCamZ - prevCamPosZ;
            float camJumpSq = dxCam * dxCam + dyCam * dyCam + dzCam * dzCam;
            float fwdDot = camera.forwardX * prevCamFwdX + camera.forwardY * prevCamFwdY + camera.forwardZ * prevCamFwdZ;
            boolean wormholeCrossedThroat = state.wormholeMode && ((curWhL * prevWormholeL) < 0.0f);

            boolean resetHist = !taaHistoryValid
                    || (state.wormholeMode != prevWormholeMode)
                    || wormholeCrossedThroat
                    || (runBinary != prevBinaryMode)
                    || (state.binaryGwOverlay != prevBinaryGwOverlay)
                    || (state.orderVisualizer != prevOrderVis)
                    || (state.ehtPolarization != prevEhtPol)
                    || (state.ehtRadioPalette != prevEhtRadio)
                    || (state.showGrid != prevShowGrid)
                    || (state.lensingEnabled != prevLensing)
                    || (state.showDisk != prevShowDisk)
                    || (state.showJets != prevShowJets)
                    || (state.dopplerEnabled != prevDoppler)
                    || (state.kerrSpinEnabled != prevKerrSpin)
                    || (curFreeFall != prevFreeFall)
                    || (state.integratorMode != prevIntegratorMode)
                    || (shaderDiagMode != prevDiagMode)
                    || (state.kerrDiskVisMode != prevKerrDiskVisMode)
                    || (state.ehtMagneticModel != prevEhtModel)
                    || (state.binGwModeFilter != prevBinModeFilter)
                    || (state.tdeParticleColorMode != prevTdeColorMode)
                    || (state.tdeHydroVisMode != prevTdeHydroVisMode)
                    || (state.tdeHydroSlicePlane != prevTdeSlicePlane)
                    || (camJumpSq > 6.25f)
                    || (fwdDot < 0.94f)
                    || (Math.abs(tanHalfFov - prevTanHalfFov) > 0.04f);

            if (resetHist) {
                taaAccumCount = 0;
            }
            int readIdx = 1 - taaWriteIdx;
            float baseMinAlpha = 0.0625f;
            if (!state.paused && simDt > 0.0 && state.timeScale > 4.0f) {
                float tsT = Math.min(1.0f, (state.timeScale - 4.0f) / 21.0f);
                baseMinAlpha = 0.18f + 0.12f * tsT;
            }
            float minAlpha = Math.max(baseMinAlpha, 1.0f / (taaAccumCount + 1));

            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.taaFbo(taaWriteIdx));
            glViewport(0, 0, width, height);
            taaProgram.use();
            glUniform1i(uTaaCurrent, 0);
            glUniform1i(uTaaHistory, 1);
            glUniform1i(uTaaVelocity, 2);
            glUniform1i(uTaaUseVelocity, runBinary ? 1 : 0);
            glUniform2f(uTaaResolution, width, height);
            glUniform3f(uTaaCamPos, effCamX, effCamY, effCamZ);
            glUniform3f(uTaaCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uTaaCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uTaaCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uTaaTanHalfFov, tanHalfFov);
            glUniform3f(uTaaPrevCamPos, prevCamPosX, prevCamPosY, prevCamPosZ);
            glUniform3f(uTaaPrevCamForward, prevCamFwdX, prevCamFwdY, prevCamFwdZ);
            glUniform3f(uTaaPrevCamRight, prevCamRightX, prevCamRightY, prevCamRightZ);
            glUniform3f(uTaaPrevCamUp, prevCamUpX, prevCamUpY, prevCamUpZ);
            glUniform1f(uTaaPrevTanHalfFov, prevTanHalfFov);
            glUniform1i(uTaaResetHistory, resetHist ? 1 : 0);
            glUniform1f(uTaaMinAlpha, minAlpha);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, renderTargets.hdrColorTexture());
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, renderTargets.taaTexture(readIdx));
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, renderTargets.velocityTexture());
            glDrawArrays(GL_TRIANGLES, 0, 3);

            resolvedHdrTex = renderTargets.taaTexture(taaWriteIdx);
            taaWriteIdx = readIdx;
            taaHistoryValid = true;
            taaAccumCount = Math.min(16, taaAccumCount + 1);
            prevWormholeMode = state.wormholeMode;
            prevBinaryMode = runBinary;
            prevBinaryGwOverlay = state.binaryGwOverlay;
            prevOrderVis = state.orderVisualizer;
            prevEhtPol = state.ehtPolarization;
            prevEhtRadio = state.ehtRadioPalette;
            prevShowGrid = state.showGrid;
            prevLensing = state.lensingEnabled;
            prevShowDisk = state.showDisk;
            prevShowJets = state.showJets;
            prevDoppler = state.dopplerEnabled;
            prevKerrSpin = state.kerrSpinEnabled;
            prevFreeFall = curFreeFall;
            prevIntegratorMode = state.integratorMode;
            prevDiagMode = shaderDiagMode;
            prevKerrDiskVisMode = state.kerrDiskVisMode;
            prevEhtModel = state.ehtMagneticModel;
            prevBinModeFilter = state.binGwModeFilter;
            prevTdeColorMode = state.tdeParticleColorMode;
            prevTdeHydroVisMode = state.tdeHydroVisMode;
            prevTdeSlicePlane = state.tdeHydroSlicePlane;
        } else {
            taaHistoryValid = false;
            taaAccumCount = 0;
        }
        timers.endTaa();

        prevCamPosX = effCamX; prevCamPosY = effCamY; prevCamPosZ = effCamZ;
        prevCamFwdX = camera.forwardX; prevCamFwdY = camera.forwardY; prevCamFwdZ = camera.forwardZ;
        prevCamRightX = camera.rightX; prevCamRightY = camera.rightY; prevCamRightZ = camera.rightZ;
        prevCamUpX = camera.upX; prevCamUpY = camera.upY; prevCamUpZ = camera.upZ;
        prevTanHalfFov = tanHalfFov;
        prevWormholeL = curWhL;
        frameIndex++;

        // PASS 2 + 2B + 3: Karis Bloom Downsample -> Clean Pre-Upsample Anamorphic -> Bloom Upsample -> Hill ACES + IEC sRGB
        timers.beginPost();
        boolean runBloom = (shaderDiagMode == 0) && (state.bloomStrength > 0.001f);
        boolean runAnamorphic = (shaderDiagMode == 0) && state.anamorphicEnabled && (state.anamorphicStrength > 0.001f);

        if (runBloom || runAnamorphic) {
            downsampleProgram.use();
            glUniform1i(uDownSource, 0);
            glActiveTexture(GL_TEXTURE0);
            int maxDown = runBloom ? BLOOM_LEVELS : Math.min(3, BLOOM_LEVELS);
            for (int i = 0; i < maxDown; i++) {
                glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.bloomFbo(i));
                glViewport(0, 0, renderTargets.bloomWidth(i), renderTargets.bloomHeight(i));
                glBindTexture(GL_TEXTURE_2D, (i == 0) ? resolvedHdrTex : renderTargets.bloomTexture(i - 1));
                glUniform1i(uIsFirstPass, (i == 0) ? 1 : 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
        }

        // PASS 2B BEFORE BLOOM UPSAMPLE: reads clean un-bloated 1/8-res bloomTexture(2)!
        if (runAnamorphic) {
            anamorphicProgram.use();
            glUniform1i(uAnaSource, 0);
            glActiveTexture(GL_TEXTURE0);
            glViewport(0, 0, renderTargets.anamorphicWidth(), renderTargets.anamorphicHeight());

            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.anamorphicFbo(0));
            glBindTexture(GL_TEXTURE_2D, renderTargets.bloomTexture(2));
            glUniform1f(uAnaStepScale, 1.35f);
            glUniform1i(uAnaExtractThreshold, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.anamorphicFbo(1));
            glBindTexture(GL_TEXTURE_2D, renderTargets.anamorphicTexture(0));
            glUniform1f(uAnaStepScale, 4.20f);
            glUniform1i(uAnaExtractThreshold, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

        if (runBloom) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            upsampleProgram.use();
            glUniform1i(uUpSource, 0);
            glUniform1f(uFilterRadius, 1.15f);

            for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
                glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.bloomFbo(i));
                glViewport(0, 0, renderTargets.bloomWidth(i), renderTargets.bloomHeight(i));
                glBindTexture(GL_TEXTURE_2D, renderTargets.bloomTexture(i + 1));
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
            glDisable(GL_BLEND);
        }

        // PASS 3: Final Composite (Stephen Hill ACES RRT+ODT + Exact IEC 61966-2-1 sRGB + Inverse-CDF TPDF Dither)
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo);
        glViewport(0, 0, width, height);
        compositeProgram.use();
        glUniform1i(uCompHdr, 0);
        glUniform1i(uCompBloom, 1);
        glUniform1i(uCompAnamorphic, 2);
        glUniform1f(uCompStrength, runBloom ? state.bloomStrength : 0.0f);
        glUniform1f(uCompAnamorphicStrength, runAnamorphic ? state.anamorphicStrength : 0.0f);
        glUniform1f(uCompExposure, (shaderDiagMode != 0) ? 1.0f : state.exposure);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, resolvedHdrTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, renderTargets.bloomTexture(0));
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, renderTargets.anamorphicTexture(1));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glActiveTexture(GL_TEXTURE0);
        timers.endPost();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (renderTargets != null) {
            renderTargets.close();
        }
        tdeSystem.close();
        glDeleteTextures(blackbodyLutTex);
        glDeleteTextures(skyboxUniverseA);
        glDeleteTextures(skyboxUniverseB);
        glDeleteTextures(noise3dTex);
        glDeleteVertexArrays(quadVao);
        rayProgram.close();
        rayDopriProgram.close();
        wormholeProgram.close();
        binaryProgram.close();
        binaryDopriProgram.close();
        taaProgram.close();
        downsampleProgram.close();
        upsampleProgram.close();
        anamorphicProgram.close();
        compositeProgram.close();
    }

    /** Precomputes a 512x1 CIE 1931 2-degree spectral Planckian blackbody RGBA16F LUT (900K..38000K). */
    private static int createBlackbodyLutTexture(int width) {
        float[] lutRgba = PlanckBlackbody.buildLutRgba(width, 900.0, 38000.0);
        FloatBuffer data = MemoryUtil.memAllocFloat(lutRgba.length);
        try {
            data.put(lutRgba).flip();
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, 1, GL_RGBA, GL_FLOAT, data);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);
            ShaderProgram.checkGlError("createBlackbodyLutTexture");
            return tex;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static int bake3dNoiseVolume(ShaderProgram noiseProg, int size) {
        int tex = glGenTextures();
        int fbo = glGenFramebuffers();
        boolean ok = false;
        try {
            glBindTexture(GL_TEXTURE_3D, tex);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_R16F, size, size, size, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);

            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, size, size);
            noiseProg.use();
            glUniform1f(noiseProg.uniformLocation("uSize"), (float) size);
            int uSliceZ = noiseProg.uniformLocation("uSliceZ");

            for (int z = 0; z < size; z++) {
                glFramebufferTexture3D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_3D, tex, 0, z);
                if (z == 0 && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new RuntimeException("3D Noise bake framebuffer incomplete");
                }
                glUniform1f(uSliceZ, (z + 0.5f) / size);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }

            glBindTexture(GL_TEXTURE_3D, tex);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glGenerateMipmap(GL_TEXTURE_3D);
            glBindTexture(GL_TEXTURE_3D, 0);

            ShaderProgram.checkGlError("bake3dNoiseVolume");
            ok = true;
            return tex;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            if (!ok) {
                glDeleteTextures(tex);
            }
        }
    }

    private static int bakeHdrSkyboxCubemap(ShaderProgram skyProg, int noiseVolTex, int faceSize, String tag) {
        int cubemap = glGenTextures();
        int fbo = glGenFramebuffers();
        boolean ok = false;
        try {
            glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
            // Packed 32-bit R11F_G11F_B10F halves cubemap VRAM (~32 MB vs 64 MB) & sampling bandwidth
            for (int face = 0; face < 6; face++) {
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_R11F_G11F_B10F,
                        faceSize, faceSize, 0, GL_RGB, GL_FLOAT, (ByteBuffer) null);
            }
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, faceSize, faceSize);
            skyProg.use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_3D, noiseVolTex);
            glUniform1i(skyProg.uniformLocation("uNoiseVol"), 0);
            glUniform1f(skyProg.uniformLocation("uFaceSize"), (float) faceSize);
            int uFace = skyProg.uniformLocation("uFace");

            for (int face = 0; face < 6; face++) {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cubemap, 0);
                if (face == 0 && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new RuntimeException("HDR Skybox (" + tag + ") bake framebuffer incomplete");
                }
                glUniform1i(uFace, face);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }

            glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
            glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
            glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
            glBindTexture(GL_TEXTURE_3D, 0);

            ShaderProgram.checkGlError("bakeHdrSkyboxCubemap(" + tag + ")");
            ok = true;
            return cubemap;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            if (!ok) {
                glDeleteTextures(cubemap);
            }
        }
    }
}
