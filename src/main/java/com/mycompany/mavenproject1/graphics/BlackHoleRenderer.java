package com.mycompany.mavenproject1.graphics;

import com.mycompany.mavenproject1.core.CameraController;
import com.mycompany.mavenproject1.core.SimulationState;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * Manages GPU startup precomputations (128^3 3D turbulence volume + dual mipmapped
 * 1024x1024 HDR skybox cubemaps for Universe A and Universe B) and executes the
 * zero-allocation multi-pass Kerr-Schild / Morris-Thorne raytrace, OpenGL 4.3 TDE
 * compute + HDR sprite pass, 5-level Karis bloom pyramid, and ACES composite pipeline.
 */
public final class BlackHoleRenderer implements AutoCloseable {

    public static final int BLOOM_LEVELS = 5;
    private static final int NOISE_VOL_SIZE = 128;
    private static final int SKYBOX_FACE_SIZE = 1024;

    private final ShaderProgram rayProgram;
    private final ShaderProgram wormholeProgram;
    private final ShaderProgram downsampleProgram;
    private final ShaderProgram upsampleProgram;
    private final ShaderProgram compositeProgram;
    private final TdeParticleSystem tdeSystem;

    private final int quadVao;
    private final int noise3dTex;
    private final int skyboxUniverseA;
    private final int skyboxUniverseB;
    private FramebufferSet framebuffers;

    // Cached Black Hole uniform locations
    private final int uResolution;
    private final int uTime;
    private final int uCamPos;
    private final int uCamForward;
    private final int uCamRight;
    private final int uCamUp;
    private final int uSkybox;
    private final int uNoiseVol;
    private final int uSpinA;
    private final int uDiskTempScale;
    private final int uDiskDensity;
    private final int uJetPower;
    private final int uTanHalfFov;
    private final int uDoppler;
    private final int uLensing;
    private final int uShowDisk;
    private final int uShowJets;
    private final int uFreeFall;
    private final int uShowGrid;
    private final int uOrderVis;
    private final int uAdaptiveAa;

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
    private final int uWhAdaptiveAa;

    // Bloom & Composite uniform locations
    private final int uDownSource;
    private final int uIsFirstPass;
    private final int uUpSource;
    private final int uFilterRadius;
    private final int uCompHdr;
    private final int uCompBloom;
    private final int uCompStrength;
    private final int uCompExposure;

    public BlackHoleRenderer(int initialWidth, int initialHeight) {
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        this.rayProgram = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/blackhole.frag");
        this.wormholeProgram = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/wormhole.frag");
        this.downsampleProgram = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/bloom_downsample.frag");
        this.upsampleProgram = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/bloom_upsample.frag");
        this.compositeProgram = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/composite.frag");
        this.tdeSystem = new TdeParticleSystem();

        this.quadVao = glGenVertexArrays();
        glBindVertexArray(quadVao);

        try (ShaderProgram noiseProg = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/noise3d_bake.frag")) {
            this.noise3dTex = bake3dNoiseVolume(noiseProg, NOISE_VOL_SIZE);
        }

        try (ShaderProgram skyProgA = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/skybox_bake.frag")) {
            this.skyboxUniverseA = bakeHdrSkyboxCubemap(skyProgA, noise3dTex, SKYBOX_FACE_SIZE, "UniverseA");
        }

        try (ShaderProgram skyProgB = ShaderProgram.fromResources("/shaders/fullscreen.vert", "/shaders/skybox_b_bake.frag")) {
            this.skyboxUniverseB = bakeHdrSkyboxCubemap(skyProgB, noise3dTex, SKYBOX_FACE_SIZE, "UniverseB");
        }

        this.framebuffers = new FramebufferSet(initialWidth, initialHeight, BLOOM_LEVELS);
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
        uSpinA = rayProgram.uniformLocation("uSpinA");
        uDiskTempScale = rayProgram.uniformLocation("uDiskTempScale");
        uDiskDensity = rayProgram.uniformLocation("uDiskDensity");
        uJetPower = rayProgram.uniformLocation("uJetPower");
        uTanHalfFov = rayProgram.uniformLocation("uTanHalfFov");
        uDoppler = rayProgram.uniformLocation("uDoppler");
        uLensing = rayProgram.uniformLocation("uLensing");
        uShowDisk = rayProgram.uniformLocation("uShowDisk");
        uShowJets = rayProgram.uniformLocation("uShowJets");
        uFreeFall = rayProgram.uniformLocation("uFreeFall");
        uShowGrid = rayProgram.uniformLocation("uShowGrid");
        uOrderVis = rayProgram.uniformLocation("uOrderVis");
        uAdaptiveAa = rayProgram.uniformLocation("uAdaptiveAa");

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
        uWhAdaptiveAa = wormholeProgram.uniformLocation("uAdaptiveAa");

        // Cache Bloom & Composite uniform locations
        uDownSource = downsampleProgram.uniformLocation("uSource");
        uIsFirstPass = downsampleProgram.uniformLocation("uIsFirstPass");
        uUpSource = upsampleProgram.uniformLocation("uSource");
        uFilterRadius = upsampleProgram.uniformLocation("uFilterRadius");

        uCompHdr = compositeProgram.uniformLocation("uHdrScene");
        uCompBloom = compositeProgram.uniformLocation("uBloomTex");
        uCompStrength = compositeProgram.uniformLocation("uBloomStrength");
        uCompExposure = compositeProgram.uniformLocation("uExposure");
    }

    public void resize(int width, int height) {
        if (framebuffers != null) {
            framebuffers.close();
        }
        framebuffers = new FramebufferSet(width, height, BLOOM_LEVELS);
    }

    public void spawnTdeStar(SimulationState state) {
        if (!state.wormholeMode) {
            tdeSystem.spawnStar(state);
        }
    }

    public void clearTdeStars() {
        tdeSystem.clearAll();
    }

    public int tdeActiveParticles() {
        return tdeSystem.activeParticleCount();
    }

    public float tdeShearEnergy() {
        return tdeSystem.shearEnergy();
    }

    /** Executes Passes 1, 1B (TDE), 2 (Bloom), and 3 (Composite) with zero heap allocations. */
    public void renderFrame(int width, int height, double dt, boolean readbackTdeCounters,
                            SimulationState state, CameraController camera, GpuTimerQueries timers) {
        glBindVertexArray(quadVao);

        // PASS 1: Exact Kerr-Schild Black Hole OR Morris-Thorne Wormhole Ray-Tracer (GPU Timed)
        timers.beginRay();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffers.hdrFbo());
        glViewport(0, 0, width, height);

        float tanHalfFov = (float) Math.tan(Math.toRadians(state.fovDeg));

        if (state.wormholeMode) {
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
            glUniform1i(uWhAdaptiveAa, state.adaptiveAa4x ? 1 : 0);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        } else {
            rayProgram.use();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxUniverseA);
            glUniform1i(uSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(uNoiseVol, 1);

            glUniform2f(uResolution, width, height);
            glUniform1f(uTime, (float) state.simTime);
            glUniform3f(uCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(uCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uSpinA, state.effectiveSpin());
            glUniform1f(uDiskTempScale, state.diskTempScale);
            glUniform1f(uDiskDensity, state.diskDensity);
            glUniform1f(uJetPower, state.jetPower);
            glUniform1f(uTanHalfFov, tanHalfFov);
            glUniform1i(uDoppler, state.dopplerEnabled ? 1 : 0);
            glUniform1i(uLensing, state.lensingEnabled ? 1 : 0);
            glUniform1i(uShowDisk, state.showDisk ? 1 : 0);
            glUniform1i(uShowJets, state.showJets ? 1 : 0);
            glUniform1i(uFreeFall, state.freeFallMode ? 1 : 0);
            glUniform1i(uShowGrid, state.showGrid ? 1 : 0);
            glUniform1i(uOrderVis, state.orderVisualizer ? 1 : 0);
            glUniform1i(uAdaptiveAa, state.adaptiveAa4x ? 1 : 0);

            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        timers.endRay();

        // PASS 1B: OpenGL 4.3 Compute TDE + Additive HDR Sprites into hdrFbo (Always GPU Timed)
        timers.beginTde();
        tdeSystem.updateAndRender(width, height, dt, readbackTdeCounters, state, camera);
        timers.endTde();

        // PASS 2: 5-Level Karis-Averaged Bloom Pyramid (GPU Timed)
        glBindVertexArray(quadVao);
        timers.beginBloom();
        downsampleProgram.use();
        glUniform1i(uDownSource, 0);
        glActiveTexture(GL_TEXTURE0);

        for (int i = 0; i < BLOOM_LEVELS; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers.bloomFbo(i));
            glViewport(0, 0, framebuffers.bloomWidth(i), framebuffers.bloomHeight(i));
            glBindTexture(GL_TEXTURE_2D, (i == 0) ? framebuffers.hdrColorTexture() : framebuffers.bloomTexture(i - 1));
            glUniform1i(uIsFirstPass, (i == 0) ? 1 : 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        upsampleProgram.use();
        glUniform1i(uUpSource, 0);
        glUniform1f(uFilterRadius, 1.15f);

        for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers.bloomFbo(i));
            glViewport(0, 0, framebuffers.bloomWidth(i), framebuffers.bloomHeight(i));
            glBindTexture(GL_TEXTURE_2D, framebuffers.bloomTexture(i + 1));
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glDisable(GL_BLEND);
        timers.endBloom();

        // PASS 3: Final Composite + Lens Dispersion + Vignette + ACES Tone Mapping
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        compositeProgram.use();
        glUniform1i(uCompHdr, 0);
        glUniform1i(uCompBloom, 1);
        glUniform1f(uCompStrength, state.bloomStrength);
        glUniform1f(uCompExposure, state.exposure);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, framebuffers.hdrColorTexture());
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, framebuffers.bloomTexture(0));
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Override
    public void close() {
        if (framebuffers != null) {
            framebuffers.close();
        }
        tdeSystem.close();
        glDeleteTextures(skyboxUniverseA);
        glDeleteTextures(skyboxUniverseB);
        glDeleteTextures(noise3dTex);
        glDeleteVertexArrays(quadVao);
        rayProgram.close();
        wormholeProgram.close();
        downsampleProgram.close();
        upsampleProgram.close();
        compositeProgram.close();
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

        // Generate full mipmap chain for screen-space ray differentials (textureGrad)
        glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
        glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

        ShaderProgram.checkGlError("bakeHdrSkyboxCubemap(" + tag + ")");
        return cubemap;
    }
}
