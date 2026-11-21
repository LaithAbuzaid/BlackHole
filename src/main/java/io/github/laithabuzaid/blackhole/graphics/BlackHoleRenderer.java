package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
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
    private final ShaderProgram wormholeProgram;
    private final ShaderProgram binaryProgram;
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
    private boolean prevFreeFall;

    // Cached Black Hole uniform locations
    private final int uResolution;
    private final int uTime;
    private final int uCamPos;
    private final int uCamForward;
    private final int uCamRight;
    private final int uCamUp;
    private final int uSkybox;
    private final int uNoiseVol;
    private final int uBlackbodyLut;
    private final int uSpinA;
    private final int uHorizonR;
    private final int uInnerHorizonR;
    private final int uIscoR;
    private final int uDiskTempScale;
    private final int uDiskDensity;
    private final int uJetPower;
    private final int uTanHalfFov;
    private final int uCauchyFlash;
    private final int uSpaghettify;
    private final int uSingularityFade;
    private final int uAccretionSurge;
    private final int uDoppler;
    private final int uLensing;
    private final int uShowDisk;
    private final int uShowJets;
    private final int uFreeFall;
    private final int uShowGrid;
    private final int uOrderVis;
    private final int uEhtPolarization;
    private final int uEhtRadio;
    private final int uJitter;
    private final int uFrameIndex;

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
    private final int uWhJitter;

    // Cached Binary Merger uniform locations
    private final int uBinResolution;
    private final int uBinTime;
    private final int uBinDt;
    private final int uBinCamPos;
    private final int uBinCamForward;
    private final int uBinCamRight;
    private final int uBinCamUp;
    private final int uBinTanHalfFov;
    private final int uBinPrevCamPos;
    private final int uBinPrevCamForward;
    private final int uBinPrevCamRight;
    private final int uBinPrevCamUp;
    private final int uBinPrevTanHalfFov;
    private final int uBinSkybox;
    private final int uBinNoiseVol;
    private final int uBinBlackbodyLut;
    private final int uBinPos1;
    private final int uBinPos2;
    private final int uBinVel1;
    private final int uBinVel2;
    private final int uBinMass1;
    private final int uBinMass2;
    private final int uBinRemnantMass;
    private final int uBinRemnantSpin;
    private final int uBinMergeBlend;
    private final int uBinSeparation;
    private final int uBinOrbitalPhase;
    private final int uBinGwPhase;
    private final int uBinGwFreqHz;
    private final int uBinStrainAmp;
    private final int uBinRingdownEnv;
    private final int uBinMergerPulse;
    private final int uBinDiskTempScale;
    private final int uBinDiskDensity;
    private final int uBinDoppler;
    private final int uBinLensing;
    private final int uBinShowDisk;
    private final int uBinShowGrid;
    private final int uBinGwOverlay;
    private final int uBinJitter;
    private final int uBinFrameIndex;

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
        this.wormholeProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/simulation/morris_thorne_wormhole.frag");
        this.binaryProgram = ShaderProgram.fromResources("/shaders/common/fullscreen.vert", "/shaders/simulation/binary_merger.frag");
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

        // Cache Black Hole uniform locations
        uResolution = rayProgram.uniformLocation("uResolution");
        uTime = rayProgram.uniformLocation("uTime");
        uCamPos = rayProgram.uniformLocation("uCamPos");
        uCamForward = rayProgram.uniformLocation("uCamForward");
        uCamRight = rayProgram.uniformLocation("uCamRight");
        uCamUp = rayProgram.uniformLocation("uCamUp");
        uSkybox = rayProgram.uniformLocation("uSkybox");
        uNoiseVol = rayProgram.uniformLocation("uNoiseVol");
        uBlackbodyLut = rayProgram.uniformLocation("uBlackbodyLut");
        uSpinA = rayProgram.uniformLocation("uSpinA");
        uHorizonR = rayProgram.uniformLocation("uHorizonR");
        uInnerHorizonR = rayProgram.uniformLocation("uInnerHorizonR");
        uIscoR = rayProgram.uniformLocation("uIscoR");
        uDiskTempScale = rayProgram.uniformLocation("uDiskTempScale");
        uDiskDensity = rayProgram.uniformLocation("uDiskDensity");
        uJetPower = rayProgram.uniformLocation("uJetPower");
        uTanHalfFov = rayProgram.uniformLocation("uTanHalfFov");
        uCauchyFlash = rayProgram.uniformLocation("uCauchyFlash");
        uSpaghettify = rayProgram.uniformLocation("uSpaghettify");
        uSingularityFade = rayProgram.uniformLocation("uSingularityFade");
        uAccretionSurge = rayProgram.uniformLocation("uAccretionSurge");
        uDoppler = rayProgram.uniformLocation("uDoppler");
        uLensing = rayProgram.uniformLocation("uLensing");
        uShowDisk = rayProgram.uniformLocation("uShowDisk");
        uShowJets = rayProgram.uniformLocation("uShowJets");
        uFreeFall = rayProgram.uniformLocation("uFreeFall");
        uShowGrid = rayProgram.uniformLocation("uShowGrid");
        uOrderVis = rayProgram.uniformLocation("uOrderVis");
        uEhtPolarization = rayProgram.uniformLocation("uEhtPolarization");
        uEhtRadio = rayProgram.uniformLocation("uEhtRadio");
        uJitter = rayProgram.uniformLocation("uJitter");
        uFrameIndex = rayProgram.uniformLocation("uFrameIndex");

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
        uWhJitter = wormholeProgram.uniformLocation("uJitter");

        // Cache Binary Merger uniform locations
        uBinResolution = binaryProgram.uniformLocation("uResolution");
        uBinTime = binaryProgram.uniformLocation("uTime");
        uBinDt = binaryProgram.uniformLocation("uDt");
        uBinCamPos = binaryProgram.uniformLocation("uCamPos");
        uBinCamForward = binaryProgram.uniformLocation("uCamForward");
        uBinCamRight = binaryProgram.uniformLocation("uCamRight");
        uBinCamUp = binaryProgram.uniformLocation("uCamUp");
        uBinTanHalfFov = binaryProgram.uniformLocation("uTanHalfFov");
        uBinPrevCamPos = binaryProgram.uniformLocation("uPrevCamPos");
        uBinPrevCamForward = binaryProgram.uniformLocation("uPrevCamForward");
        uBinPrevCamRight = binaryProgram.uniformLocation("uPrevCamRight");
        uBinPrevCamUp = binaryProgram.uniformLocation("uPrevCamUp");
        uBinPrevTanHalfFov = binaryProgram.uniformLocation("uPrevTanHalfFov");
        uBinSkybox = binaryProgram.uniformLocation("uSkybox");
        uBinNoiseVol = binaryProgram.uniformLocation("uNoiseVol");
        uBinBlackbodyLut = binaryProgram.uniformLocation("uBlackbodyLut");
        uBinPos1 = binaryProgram.uniformLocation("uPos1");
        uBinPos2 = binaryProgram.uniformLocation("uPos2");
        uBinVel1 = binaryProgram.uniformLocation("uVel1");
        uBinVel2 = binaryProgram.uniformLocation("uVel2");
        uBinMass1 = binaryProgram.uniformLocation("uMass1");
        uBinMass2 = binaryProgram.uniformLocation("uMass2");
        uBinRemnantMass = binaryProgram.uniformLocation("uRemnantMass");
        uBinRemnantSpin = binaryProgram.uniformLocation("uRemnantSpin");
        uBinMergeBlend = binaryProgram.uniformLocation("uMergeBlend");
        uBinSeparation = binaryProgram.uniformLocation("uSeparation");
        uBinOrbitalPhase = binaryProgram.uniformLocation("uOrbitalPhase");
        uBinGwPhase = binaryProgram.uniformLocation("uGwPhase");
        uBinGwFreqHz = binaryProgram.uniformLocation("uGwFreqHz");
        uBinStrainAmp = binaryProgram.uniformLocation("uStrainAmp");
        uBinRingdownEnv = binaryProgram.uniformLocation("uRingdownEnv");
        uBinMergerPulse = binaryProgram.uniformLocation("uMergerPulse");
        uBinDiskTempScale = binaryProgram.uniformLocation("uDiskTempScale");
        uBinDiskDensity = binaryProgram.uniformLocation("uDiskDensity");
        uBinDoppler = binaryProgram.uniformLocation("uDoppler");
        uBinLensing = binaryProgram.uniformLocation("uLensing");
        uBinShowDisk = binaryProgram.uniformLocation("uShowDisk");
        uBinShowGrid = binaryProgram.uniformLocation("uShowGrid");
        uBinGwOverlay = binaryProgram.uniformLocation("uGwOverlay");
        uBinJitter = binaryProgram.uniformLocation("uJitter");
        uBinFrameIndex = binaryProgram.uniformLocation("uFrameIndex");

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
        if (renderTargets != null) {
            renderTargets.close();
        }
        renderTargets = new RenderTargets(width, height, BLOOM_LEVELS);
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

    public void stepTdeComputeOnly(int steps, double dt, SimulationState state) {
        tdeSystem.stepComputeOnly(steps, dt, state);
    }

    public int tdeActiveParticles() {
        return tdeSystem.activeParticleCount();
    }

    public int tdeBoundParticles() {
        return tdeSystem.boundParticleCount();
    }

    public int tdeUnboundParticles() {
        return tdeSystem.unboundParticleCount();
    }

    public int tdeSwallowedParticles() {
        return tdeSystem.totalSwallowedCount();
    }

    public float tdeShearEnergy() {
        return tdeSystem.shearEnergy();
    }

    public float tdeHorizonSurge() {
        return tdeSystem.horizonAccretionSurge();
    }

    /** Executes Passes 1, 1B (TDE), 1C (TAA), 2 (Bloom), and 3 (Composite) to default framebuffer with zero heap allocations. */
    public void renderFrame(int width, int height, double wallDt, double simDt,
                            SimulationState state, CameraController camera, GpuTimerQueries timers) {
        renderFrame(width, height, wallDt, simDt, state, camera, null, timers, 0);
    }

    /** Executes Passes 1, 1B (TDE), 1C (TAA), 2 (Bloom), and 3 (Composite) to {@code outputFbo} with zero heap allocations. */
    public void renderFrame(int width, int height, double wallDt, double simDt,
                            SimulationState state, CameraController camera, BinaryMergerPhysics binary,
                            GpuTimerQueries timers, int outputFbo) {
        glBindVertexArray(quadVao);

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
            binaryProgram.use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(uBinSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(uBinNoiseVol, 1);

            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blackbodyLutTex);
            glUniform1i(uBinBlackbodyLut, 2);

            float binDt = (state.paused || simDt <= 0.0) ? 0.0f : (float) simDt;
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

            glUniform2f(uBinResolution, width, height);
            glUniform1f(uBinTime, binary.binaryTime);
            glUniform1f(uBinDt, binDt);
            glUniform3f(uBinCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(uBinCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uBinCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uBinCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uBinTanHalfFov, tanHalfFov);
            glUniform3f(uBinPrevCamPos, pCamX, pCamY, pCamZ);
            glUniform3f(uBinPrevCamForward, pFwdX, pFwdY, pFwdZ);
            glUniform3f(uBinPrevCamRight, pRightX, pRightY, pRightZ);
            glUniform3f(uBinPrevCamUp, pUpX, pUpY, pUpZ);
            glUniform1f(uBinPrevTanHalfFov, pFov);

            glUniform3f(uBinPos1, binary.pos1X, binary.pos1Y, binary.pos1Z);
            glUniform3f(uBinPos2, binary.pos2X, binary.pos2Y, binary.pos2Z);
            glUniform3f(uBinVel1, binary.vel1X, binary.vel1Y, binary.vel1Z);
            glUniform3f(uBinVel2, binary.vel2X, binary.vel2Y, binary.vel2Z);
            glUniform1f(uBinMass1, binary.mass1);
            glUniform1f(uBinMass2, binary.mass2);
            glUniform1f(uBinRemnantMass, binary.remnantMass);
            glUniform1f(uBinRemnantSpin, binary.remnantSpin);
            glUniform1f(uBinMergeBlend, binary.mergeBlend);
            glUniform1f(uBinSeparation, binary.currentSeparation);
            glUniform1f(uBinOrbitalPhase, binary.currentOrbitalPhase);
            glUniform1f(uBinGwPhase, binary.gwPhaseOut);
            glUniform1f(uBinGwFreqHz, binary.gwFreqHz);
            glUniform1f(uBinStrainAmp, binary.strainAmplitude);
            glUniform1f(uBinRingdownEnv, binary.ringdownEnv);
            glUniform1f(uBinMergerPulse, binary.mergerPulse);

            glUniform1f(uBinDiskTempScale, state.diskTempScale);
            glUniform1f(uBinDiskDensity, state.diskDensity);
            glUniform1i(uBinDoppler, state.dopplerEnabled ? 1 : 0);
            glUniform1i(uBinLensing, state.lensingEnabled ? 1 : 0);
            glUniform1i(uBinShowDisk, state.showDisk ? 1 : 0);
            glUniform1i(uBinShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(uBinGwOverlay, state.binaryGwOverlay ? 1 : 0);
            glUniform2f(uBinJitter, jitterX, jitterY);
            glUniform1i(uBinFrameIndex, frameIndex);

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
            glUniform2f(uWhJitter, jitterX, jitterY);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else {
            rayProgram.use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(uSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(uNoiseVol, 1);

            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blackbodyLutTex);
            glUniform1i(uBlackbodyLut, 2);

            glUniform2f(uResolution, width, height);
            glUniform1f(uTime, (float) state.simTime);
            glUniform3f(uCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(uCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uSpinA, state.effectiveSpin());
            glUniform1f(uHorizonR, (float) state.outerHorizonRadius());
            glUniform1f(uInnerHorizonR, (float) state.innerHorizonRadius());
            glUniform1f(uIscoR, (float) state.progradeIscoRadius());
            glUniform1f(uDiskTempScale, state.diskTempScale);
            glUniform1f(uDiskDensity, state.diskDensity);
            glUniform1f(uJetPower, state.jetPower);
            glUniform1f(uTanHalfFov, tanHalfFov);
            glUniform1f(uCauchyFlash, state.cauchyFlashIntensity);
            glUniform1f(uSpaghettify, state.spaghettification);
            glUniform1f(uSingularityFade, state.singularityFade);
            glUniform1f(uAccretionSurge, state.tdeHorizonSurge);
            glUniform1i(uDoppler, state.dopplerEnabled ? 1 : 0);
            glUniform1i(uLensing, state.lensingEnabled ? 1 : 0);
            glUniform1i(uShowDisk, state.showDisk ? 1 : 0);
            glUniform1i(uShowJets, state.showJets ? 1 : 0);
            glUniform1i(uFreeFall, (state.freeFallMode || state.plungeActive) ? 1 : 0);
            glUniform1i(uShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(uOrderVis, state.orderVisualizer ? 1 : 0);
            glUniform1i(uEhtPolarization, state.ehtPolarization ? 1 : 0);
            glUniform1i(uEhtRadio, state.ehtRadioPalette ? 1 : 0);
            glUniform2f(uJitter, jitterX, jitterY);
            glUniform1i(uFrameIndex, frameIndex);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        timers.endRay();

        // PASS 1B: OpenGL 4.3 Compute TDE + Volumetric Lensed Plasma Ribbons (Single Kerr mode only)
        timers.beginTde();
        if (!runBinary) {
            tdeSystem.updateAndRender(width, height, wallDt, simDt, blackbodyLutTex, noise3dTex, jitterX, jitterY, state, camera);
        }
        timers.endTde();

        // PASS 1C + 2 + 2B + 3: Relativistic TAA Resolve + Karis Bloom + Anamorphic Flare + ACES Composite
        glBindVertexArray(quadVao);
        timers.beginBloom();

        int resolvedHdrTex = renderTargets.hdrColorTexture();
        if (useTaa) {
            boolean curFreeFall = state.freeFallMode || state.plungeActive;
            float dxCam = effCamX - prevCamPosX;
            float dyCam = effCamY - prevCamPosY;
            float dzCam = effCamZ - prevCamPosZ;
            float camJumpSq = dxCam * dxCam + dyCam * dyCam + dzCam * dzCam;
            float fwdDot = camera.forwardX * prevCamFwdX + camera.forwardY * prevCamFwdY + camera.forwardZ * prevCamFwdZ;

            boolean resetHist = !taaHistoryValid
                    || (state.wormholeMode != prevWormholeMode)
                    || (runBinary != prevBinaryMode)
                    || (state.binaryGwOverlay != prevBinaryGwOverlay)
                    || (state.orderVisualizer != prevOrderVis)
                    || (state.ehtPolarization != prevEhtPol)
                    || (state.ehtRadioPalette != prevEhtRadio)
                    || (state.showGrid != prevShowGrid)
                    || (state.lensingEnabled != prevLensing)
                    || (state.showDisk != prevShowDisk)
                    || (curFreeFall != prevFreeFall)
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
            prevFreeFall = curFreeFall;
        } else {
            taaHistoryValid = false;
            taaAccumCount = 0;
        }

        // Record current camera pose for next frame's temporal reprojection
        prevCamPosX = effCamX; prevCamPosY = effCamY; prevCamPosZ = effCamZ;
        prevCamFwdX = camera.forwardX; prevCamFwdY = camera.forwardY; prevCamFwdZ = camera.forwardZ;
        prevCamRightX = camera.rightX; prevCamRightY = camera.rightY; prevCamRightZ = camera.rightZ;
        prevCamUpX = camera.upX; prevCamUpY = camera.upY; prevCamUpZ = camera.upZ;
        prevTanHalfFov = tanHalfFov;
        frameIndex++;

        // PASS 2: 5-Level Karis-Averaged Bloom Pyramid (reads temporally resolved HDR scene)
        downsampleProgram.use();
        glUniform1i(uDownSource, 0);
        glActiveTexture(GL_TEXTURE0);

        for (int i = 0; i < BLOOM_LEVELS; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.bloomFbo(i));
            glViewport(0, 0, renderTargets.bloomWidth(i), renderTargets.bloomHeight(i));
            glBindTexture(GL_TEXTURE_2D, (i == 0) ? resolvedHdrTex : renderTargets.bloomTexture(i - 1));
            glUniform1i(uIsFirstPass, (i == 0) ? 1 : 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

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

        // PASS 2B: 2-Pass Cascaded Horizontal Anamorphic Lens Flare at 1/8 Res (zero discrete ghosts)
        boolean runAnamorphic = state.anamorphicEnabled && state.anamorphicStrength > 0.001f;
        if (runAnamorphic) {
            anamorphicProgram.use();
            glUniform1i(uAnaSource, 0);
            glActiveTexture(GL_TEXTURE0);
            glViewport(0, 0, renderTargets.anamorphicWidth(), renderTargets.anamorphicHeight());

            // Horizontal pass 1: extract hot peaks from mip 2 + medium streak
            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.anamorphicFbo(0));
            glBindTexture(GL_TEXTURE_2D, renderTargets.bloomTexture(2));
            glUniform1f(uAnaStepScale, 1.35f);
            glUniform1i(uAnaExtractThreshold, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // Horizontal pass 2: wide cinema streak cascade
            glBindFramebuffer(GL_FRAMEBUFFER, renderTargets.anamorphicFbo(1));
            glBindTexture(GL_TEXTURE_2D, renderTargets.anamorphicTexture(0));
            glUniform1f(uAnaStepScale, 4.20f);
            glUniform1i(uAnaExtractThreshold, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

        // PASS 3: Final Composite + Anamorphic Glare + Lens Dispersion + Vignette + ACES + TPDF Dither
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo);
        glViewport(0, 0, width, height);
        compositeProgram.use();
        glUniform1i(uCompHdr, 0);
        glUniform1i(uCompBloom, 1);
        glUniform1i(uCompAnamorphic, 2);
        glUniform1f(uCompStrength, state.bloomStrength);
        glUniform1f(uCompAnamorphicStrength, runAnamorphic ? state.anamorphicStrength : 0.0f);
        glUniform1f(uCompExposure, state.exposure);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, resolvedHdrTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, renderTargets.bloomTexture(0));
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, renderTargets.anamorphicTexture(1));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        timers.endBloom();
    }

    @Override
    public void close() {
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
        wormholeProgram.close();
        binaryProgram.close();
        taaProgram.close();
        downsampleProgram.close();
        upsampleProgram.close();
        anamorphicProgram.close();
        compositeProgram.close();
    }

    /** Precomputes a 512x1 sqrt-spaced RGB16F Planckian blackbody lookup table (900K..38000K). */
    private static int createBlackbodyLutTexture(int width) {
        FloatBuffer data = MemoryUtil.memAllocFloat(width * 3);
        for (int i = 0; i < width; i++) {
            double u = (i + 0.5) / width;
            double tempK = 900.0 + (u * u) * 37100.0;
            double t = Math.max(9.0, Math.min(380.0, tempK / 100.0));

            double r, g, b;
            if (t <= 66.0) {
                r = 1.0;
                g = clamp01((99.4708025861 * Math.log(Math.max(1.0, t)) - 161.1195681661) / 255.0);
            } else {
                r = clamp01((329.698727446 * Math.pow(t - 60.0, -0.1332047592)) / 255.0);
                g = clamp01((288.1221695283 * Math.pow(t - 60.0, -0.0755148492)) / 255.0);
            }
            if (t >= 66.0) {
                b = 1.0;
            } else if (t <= 19.0) {
                b = 0.0;
            } else {
                b = clamp01((138.5177312231 * Math.log(t - 10.0) - 305.0447927307) / 255.0);
            }

            // Convert sRGB curve to linear HDR space once at startup
            data.put((float) Math.pow(r, 2.2));
            data.put((float) Math.pow(g, 2.2));
            data.put((float) Math.pow(b, 2.2));
        }
        data.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, width, 1, 0, GL_RGB, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        MemoryUtil.memFree(data);
        ShaderProgram.checkGlError("createBlackbodyLutTexture");
        return tex;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int bake3dNoiseVolume(ShaderProgram noiseProg, int size) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R16F, size, size, size, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);

        int fbo = glGenFramebuffers();
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

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(fbo);

        // Generate 3D mipmaps so grazing accretion-disk lookups stay alias-free and cache-friendly
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glGenerateMipmap(GL_TEXTURE_3D);
        glBindTexture(GL_TEXTURE_3D, 0);

        ShaderProgram.checkGlError("bake3dNoiseVolume");
        return tex;
    }

    private static int bakeHdrSkyboxCubemap(ShaderProgram skyProg, int noiseVolTex, int faceSize, String tag) {
        int cubemap = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
        for (int face = 0; face < 6; face++) {
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA16F,
                    faceSize, faceSize, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

        int fbo = glGenFramebuffers();
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

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(fbo);

        glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
        glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

        ShaderProgram.checkGlError("bakeHdrSkyboxCubemap(" + tag + ")");
        return cubemap;
    }
}
