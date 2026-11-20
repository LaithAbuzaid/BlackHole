package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL 4.3 Compute Shader Tidal Disruption Event (TDE) solver and HDR volumetric
 * particle renderer.
 *
 * <p>Each {@link #spawnStar(SimulationState)} call spawns a cohesive 65,536-particle
 * main-sequence star into a 2-slot ring-buffered SSBO (131,072 total capacity) using
 * pre-allocated direct memory so both spawning and per-frame updates incur zero Java
 * heap allocations.</p>
 */
public final class TidalDisruptionSystem implements AutoCloseable {

    public static final int PARTICLES_PER_STAR = 65536;
    public static final int MAX_STAR_SLOTS = 2;
    public static final int TOTAL_PARTICLES = PARTICLES_PER_STAR * MAX_STAR_SLOTS;
    private static final int FLOATS_PER_PARTICLE = 8; // posMass(4) + velTemp(4)
    private static final int WORKGROUP_SIZE = 256;
    private static final float TDE_TIME_SCALE = 6.5f;

    private final ShaderProgram computeProgram;
    private final ShaderProgram renderProgram;

    private final int particleSsbo;
    private final int counterSsbo;
    private final int emptyVao;

    // Pre-allocated off-heap staging buffers (zero heap allocation on spawn or readback)
    private final FloatBuffer spawnStaging;
    private final IntBuffer readCounters;

    // Cached uniform locations
    private final int uCompParticleCount;
    private final int uCompDt;
    private final int uCompSpinA;
    private final int uCompHorizonR;

    private final int uRendResolution;
    private final int uRendCamPos;
    private final int uRendCamForward;
    private final int uRendCamRight;
    private final int uRendCamUp;
    private final int uRendBlackbodyLut;
    private final int uRendNoiseVol;
    private final int uRendTime;
    private final int uRendTanHalfFov;
    private final int uRendSpinA;
    private final int uRendHorizonR;
    private final int uRendDoppler;
    private final int uRendJitter;

    private int nextSlot = 0;
    private int spawnCounter = 0;
    private boolean hasAnyParticles = false;
    private int activeParticles = 0;
    private float shearEnergy = 0.0f;

    // Deterministic fast PRNG state (zero heap allocation)
    private long rngState = 0x5DEECE66DL;

    public TidalDisruptionSystem() {
        this.computeProgram = ShaderProgram.fromComputeResource("/shaders/simulation/tidal_disruption.comp");
        this.renderProgram = ShaderProgram.fromResources("/shaders/simulation/tidal_disruption.vert", "/shaders/simulation/tidal_disruption.frag");

        this.emptyVao = glGenVertexArrays();

        this.particleSsbo = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particleSsbo);
        long particleBytes = (long) TOTAL_PARTICLES * FLOATS_PER_PARTICLE * Float.BYTES;
        glBufferData(GL_SHADER_STORAGE_BUFFER, particleBytes, GL_DYNAMIC_DRAW);
        // Zero-initialize so all particles start inactive (posMass.w == 0)
        ByteBuffer zeroInit = MemoryUtil.memCalloc((int) particleBytes);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, zeroInit);
        MemoryUtil.memFree(zeroInit);

        this.counterSsbo = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 2L * Integer.BYTES, GL_DYNAMIC_READ);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        this.spawnStaging = MemoryUtil.memAllocFloat(PARTICLES_PER_STAR * FLOATS_PER_PARTICLE);
        this.readCounters = MemoryUtil.memAllocInt(2);

        this.uCompParticleCount = computeProgram.uniformLocation("uParticleCount");
        this.uCompDt = computeProgram.uniformLocation("uDt");
        this.uCompSpinA = computeProgram.uniformLocation("uSpinA");
        this.uCompHorizonR = computeProgram.uniformLocation("uHorizonR");

        this.uRendResolution = renderProgram.uniformLocation("uResolution");
        this.uRendCamPos = renderProgram.uniformLocation("uCamPos");
        this.uRendCamForward = renderProgram.uniformLocation("uCamForward");
        this.uRendCamRight = renderProgram.uniformLocation("uCamRight");
        this.uRendCamUp = renderProgram.uniformLocation("uCamUp");
        this.uRendBlackbodyLut = renderProgram.uniformLocation("uBlackbodyLut");
        this.uRendNoiseVol = renderProgram.uniformLocation("uNoiseVol");
        this.uRendTime = renderProgram.uniformLocation("uTime");
        this.uRendTanHalfFov = renderProgram.uniformLocation("uTanHalfFov");
        this.uRendSpinA = renderProgram.uniformLocation("uSpinA");
        this.uRendHorizonR = renderProgram.uniformLocation("uHorizonR");
        this.uRendDoppler = renderProgram.uniformLocation("uDoppler");
        this.uRendJitter = renderProgram.uniformLocation("uJitter");

        ShaderProgram.checkGlError("TidalDisruptionSystem.init");
    }

    /**
     * Spawns a cohesive 65,536-particle spherical main-sequence star on an eccentric
     * grazing orbit inside the black hole's Roche limit.
     */
    public void spawnStar(SimulationState state) {
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % MAX_STAR_SLOTS;
        int variant = spawnCounter++;

        // Distinct cinematic grazing trajectories for consecutive star spawns
        double azimuth = 0.55 + (variant % 5) * 1.18;
        double inclination = (variant % 2 == 0) ? 0.34 : -0.42;
        double startR = 14.5 + (variant % 3) * 1.4;
        double starRadius = 1.18;

        double cosInc = Math.cos(inclination);
        double sinInc = Math.sin(inclination);
        double cosAz = Math.cos(azimuth);
        double sinAz = Math.sin(azimuth);

        // Star center position in Cartesian Kerr-Schild coordinates
        double cx = startR * cosInc * cosAz;
        double cy = startR * sinInc;
        double cz = startR * cosInc * sinAz;

        // Orthonormal radial-outward and prograde orbital tangent vectors
        double invR = 1.0 / startR;
        double rx = cx * invR, ry = cy * invR, rz = cz * invR;

        // Prograde tangent aligned with Kerr spin (-z, 0, +x) tilted by orbital inclination
        double tx = -sinAz;
        double ty = 0.24 * ((variant % 2 == 0) ? 1.0 : -1.0);
        double tz = cosAz;
        // Gram-Schmidt orthogonalize tangent against radial vector
        double dotTR = tx * rx + ty * ry + tz * rz;
        tx -= dotTR * rx;
        ty -= dotTR * ry;
        tz -= dotTR * rz;
        double invT = 1.0 / Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx *= invT; ty *= invT; tz *= invT;

        // Eccentric grazing orbit velocity tuned to plunge inside the Roche limit (r_p ~ 4.2 M)
        double vTan = 0.172;
        double vRadIn = -0.118;
        double vBaseX = tx * vTan + rx * vRadIn;
        double vBaseY = ty * vTan + ry * vRadIn;
        double vBaseZ = tz * vTan + rz * vRadIn;

        spawnStaging.clear();
        for (int i = 0; i < PARTICLES_PER_STAR; i++) {
            // Stratified polytropic-like density concentration (denser hot core, soft envelope)
            double u = nextRandom01();
            double radialFrac = Math.pow(u, 0.42);
            double rad = starRadius * radialFrac;

            double cosTheta = 2.0 * nextRandom01() - 1.0;
            double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
            double phi = 2.0 * Math.PI * nextRandom01();

            double ox = rad * sinTheta * Math.cos(phi);
            double oy = rad * cosTheta;
            double oz = rad * sinTheta * Math.sin(phi);

            // Slight internal stellar rotation + thermal velocity dispersion
            double disp = 0.0085 * (1.0 - 0.4 * radialFrac);
            double vx = vBaseX + (-oz * 0.012) + (nextRandom01() - 0.5) * disp;
            double vy = vBaseY + (nextRandom01() - 0.5) * disp;
            double vz = vBaseZ + (ox * 0.012) + (nextRandom01() - 0.5) * disp;

            // Internal main-sequence radial temperature gradient: 11,200 K core -> 4,200 K photosphere
            float tempK = (float) (4200.0 + 7000.0 * Math.pow(1.0 - radialFrac, 1.55));
            float mass = (float) (1.0 - 0.45 * radialFrac);

            spawnStaging.put((float) (cx + ox));
            spawnStaging.put((float) (cy + oy));
            spawnStaging.put((float) (cz + oz));
            spawnStaging.put(mass);

            spawnStaging.put((float) vx);
            spawnStaging.put((float) vy);
            spawnStaging.put((float) vz);
            spawnStaging.put(tempK);
        }
        spawnStaging.flip();

        long byteOffset = (long) slot * PARTICLES_PER_STAR * FLOATS_PER_PARTICLE * Float.BYTES;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particleSsbo);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, spawnStaging);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        hasAnyParticles = true;
        activeParticles = Math.min(TOTAL_PARTICLES, activeParticles + PARTICLES_PER_STAR);
    }

    public void clearAll() {
        spawnStaging.clear();
        for (int i = 0; i < PARTICLES_PER_STAR * FLOATS_PER_PARTICLE; i++) {
            spawnStaging.put(0.0f);
        }
        spawnStaging.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particleSsbo);
        for (int s = 0; s < MAX_STAR_SLOTS; s++) {
            long byteOffset = (long) s * PARTICLES_PER_STAR * FLOATS_PER_PARTICLE * Float.BYTES;
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, spawnStaging);
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        hasAnyParticles = false;
        activeParticles = 0;
        shearEnergy = 0.0f;
        nextSlot = 0;
    }

    /**
     * Dispatches the OpenGL 4.3 compute solver and renders active HDR volumetric
     * velocity-stretched plasma ribbons directly into the currently bound {@code hdrFbo}.
     */
    public void updateAndRender(int width, int height, double dt, boolean readbackCounters,
                                int blackbodyLutTex, int noise3dTex, float jitterX, float jitterY,
                                SimulationState state, CameraController camera) {
        if (!hasAnyParticles || state.wormholeMode) {
            shearEnergy *= 0.92f;
            return;
        }

        // 1. Compute pass (only step physics when unpaused and dt > 0)
        if (!state.paused && dt > 0.0) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, counterSsbo);

            glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbo);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            computeProgram.use();
            glUniform1ui(uCompParticleCount, TOTAL_PARTICLES);
            glUniform1f(uCompDt, (float) (dt * TDE_TIME_SCALE));
            glUniform1f(uCompSpinA, state.effectiveSpin());
            glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());

            glDispatchCompute(TOTAL_PARTICLES / WORKGROUP_SIZE, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

            if (readbackCounters) {
                readCounters.clear();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbo);
                glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readCounters);
                activeParticles = readCounters.get(0);
                int hotShear = readCounters.get(1);
                float targetShear = Math.min(1.0f, hotShear / 6500.0f);
                shearEnergy = shearEnergy * 0.75f + targetShear * 0.25f;
                if (activeParticles == 0) {
                    hasAnyParticles = false;
                    shearEnergy = 0.0f;
                }
            }
        }

        // 2. Volumetric Instanced Velocity-Stretched HDR Plasma Ribbon Pass directly into hdrFbo
        // Preserve hdrFbo.a (world hitDist) via separate alpha blend for exact TAA parallax reprojection.
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ZERO, GL_ONE);

        renderProgram.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, blackbodyLutTex);
        glUniform1i(uRendBlackbodyLut, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, noise3dTex);
        glUniform1i(uRendNoiseVol, 1);

        glUniform2f(uRendResolution, width, height);
        glUniform3f(uRendCamPos, camera.posX, camera.posY, camera.posZ);
        glUniform3f(uRendCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
        glUniform3f(uRendCamRight, camera.rightX, camera.rightY, camera.rightZ);
        glUniform3f(uRendCamUp, camera.upX, camera.upY, camera.upZ);
        glUniform1f(uRendTime, (float) state.simTime);
        glUniform1f(uRendTanHalfFov, (float) Math.tan(Math.toRadians(state.fovDeg)));
        glUniform1f(uRendSpinA, state.effectiveSpin());
        glUniform1f(uRendHorizonR, (float) state.outerHorizonRadius());
        glUniform1i(uRendDoppler, state.dopplerEnabled ? 1 : 0);
        glUniform2f(uRendJitter, jitterX, jitterY);

        glBindVertexArray(emptyVao);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, TOTAL_PARTICLES);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
    }

    public int activeParticleCount() {
        return activeParticles;
    }

    public float shearEnergy() {
        return shearEnergy;
    }

    /**
     * Advances the TDE compute solver by {@code steps} fixed ticks without rendering.
     * Used by offline showcase capture to position a star cleanly at mid-disruption periapsis.
     */
    public void stepComputeOnly(int steps, double dt, SimulationState state) {
        if (!hasAnyParticles || state.wormholeMode || steps <= 0) {
            return;
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, counterSsbo);
        computeProgram.use();
        glUniform1ui(uCompParticleCount, TOTAL_PARTICLES);
        glUniform1f(uCompDt, (float) (dt * TDE_TIME_SCALE));
        glUniform1f(uCompSpinA, state.effectiveSpin());
        glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());

        for (int i = 0; i < steps; i++) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbo);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
            glDispatchCompute(TOTAL_PARTICLES / WORKGROUP_SIZE, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        readCounters.clear();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbo);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readCounters);
        activeParticles = readCounters.get(0);
        int hotShear = readCounters.get(1);
        shearEnergy = Math.min(1.0f, hotShear / 6500.0f);
    }

    private double nextRandom01() {
        rngState = rngState * 6364136223846793005L + 1442695040888963407L;
        return ((rngState >>> 11) * 0x1.0p-53);
    }

    @Override
    public void close() {
        MemoryUtil.memFree(spawnStaging);
        MemoryUtil.memFree(readCounters);
        glDeleteBuffers(particleSsbo);
        glDeleteBuffers(counterSsbo);
        glDeleteVertexArrays(emptyVao);
        computeProgram.close();
        renderProgram.close();
    }
}
