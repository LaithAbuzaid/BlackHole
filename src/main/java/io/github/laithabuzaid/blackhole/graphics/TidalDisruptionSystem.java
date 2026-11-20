package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * OpenGL 4.3 Compute Shader Tidal Disruption Event (TDE Lab) solver and HDR volumetric
 * plasma ribbon renderer.
 *
 * <p>Supports 4 distinct {@link StellarArchetype} classes, interactive penetration factor
 * {@code beta = r_t / r_p}, orbital inclination {@code [-65°, +65°]}, physics-safe fixed
 * sub-stepping up to {@code 25×} timelapse, Paczyński–Wiita bound/unbound energy dynamics
 * with shock circularization, and zero-stall ping-pong GPU atomic counter readback.</p>
 */
public final class TidalDisruptionSystem implements AutoCloseable {

    public static final int PARTICLES_PER_STAR = 65536;
    public static final int MAX_STAR_SLOTS = 2;
    public static final int TOTAL_PARTICLES = PARTICLES_PER_STAR * MAX_STAR_SLOTS;
    private static final int FLOATS_PER_PARTICLE = 8; // posMass(4) + velTemp(4)
    private static final int WORKGROUP_SIZE = 256;
    private static final float TDE_TIME_SCALE = 6.5f;
    private static final int COUNTER_UINTS = 5; // active, shear, bound, unbound, swallowed

    private final ShaderProgram computeProgram;
    private final ShaderProgram renderProgram;

    private final int particleSsbo;
    private final int[] counterSsbos = new int[2];
    private final long[] counterFences = new long[2];
    private int counterWriteIdx = 0;
    private final int emptyVao;

    // Pre-allocated off-heap staging buffers (zero heap allocation on spawn or readback)
    private final FloatBuffer spawnStaging;
    private final IntBuffer readCounters;
    private final FloatBuffer slotShockBetaBuf;
    private final FloatBuffer slotStyleBuf;

    // Per-slot archetype metadata (2 concurrent stars can be different stellar classes)
    private final StellarArchetype[] slotArchetype = {
            StellarArchetype.SOLAR_MAIN_SEQ, StellarArchetype.SOLAR_MAIN_SEQ
    };
    private final float[] slotBeta = {1.55f, 1.55f};

    // Cached compute uniform locations
    private final int uCompParticleCount;
    private final int uCompDt;
    private final int uCompSpinA;
    private final int uCompHorizonR;
    private final int uCompCountSnapshot;
    private final int uCompSlotShockBeta;

    // Cached render uniform locations
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
    private final int uRendSlotStyle;

    private int nextSlot = 0;
    private int spawnCounter = 0;
    private boolean hasAnyParticles = false;
    private int activeParticles = 0;
    private int boundParticles = 0;
    private int unboundParticles = 0;
    private int totalSwallowedParticles = 0;
    private float shearEnergy = 0.0f;
    private float horizonAccretionSurge = 0.0f;

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
        ByteBuffer zeroInit = MemoryUtil.memCalloc((int) particleBytes);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, zeroInit);
        MemoryUtil.memFree(zeroInit);

        // Ping-pong GPU atomic counter SSBOs for stall-free asynchronous telemetry readback
        for (int i = 0; i < 2; i++) {
            counterSsbos[i] = glGenBuffers();
            counterFences[i] = NULL;
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbos[i]);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) COUNTER_UINTS * Integer.BYTES, GL_DYNAMIC_READ);
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        this.spawnStaging = MemoryUtil.memAllocFloat(PARTICLES_PER_STAR * FLOATS_PER_PARTICLE);
        this.readCounters = MemoryUtil.memAllocInt(COUNTER_UINTS);
        this.slotShockBetaBuf = MemoryUtil.memAllocFloat(MAX_STAR_SLOTS * 2);
        this.slotStyleBuf = MemoryUtil.memAllocFloat(MAX_STAR_SLOTS * 4);
        syncSlotUniformBuffers();

        this.uCompParticleCount = computeProgram.uniformLocation("uParticleCount");
        this.uCompDt = computeProgram.uniformLocation("uDt");
        this.uCompSpinA = computeProgram.uniformLocation("uSpinA");
        this.uCompHorizonR = computeProgram.uniformLocation("uHorizonR");
        this.uCompCountSnapshot = computeProgram.uniformLocation("uCountSnapshot");
        this.uCompSlotShockBeta = computeProgram.uniformLocation("uSlotShockBeta");

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
        this.uRendSlotStyle = renderProgram.uniformLocation("uSlotStyle");

        ShaderProgram.checkGlError("TidalDisruptionSystem.init");
    }

    /**
     * Spawns a cohesive 65,536-particle star matching the selected {@link StellarArchetype},
     * penetration factor {@code beta = r_t / r_p}, and orbital inclination into the 2-slot
     * SSBO ring buffer with zero Java heap allocations.
     */
    public void spawnStar(SimulationState state) {
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % MAX_STAR_SLOTS;
        int variant = spawnCounter++;

        StellarArchetype arch = state.currentTdeArchetype();
        float beta = StellarArchetype.clampBeta(state.tdeBeta);
        double incRad = Math.toRadians(StellarArchetype.clampInclinationDeg(state.tdeInclinationDeg));

        slotArchetype[slot] = arch;
        slotBeta[slot] = beta;
        syncSlotUniformBuffers();

        double rPlus = state.outerHorizonRadius();
        double rEff = 0.88 * rPlus;
        double rP = arch.computePeriapsis(beta, rPlus);
        double startR = Math.max(13.8, Math.min(22.5, arch.tidalRadius * 2.15 + (variant % 2) * 0.8));

        // Vary entry azimuth cleanly across consecutive launches
        double azimuth = 0.52 + (variant % 5) * 1.18;
        double cosAz = Math.cos(azimuth);
        double sinAz = Math.sin(azimuth);

        // Launch center on the orbital entry radius with orthonormal radial & inclined prograde tangent
        double rx = cosAz, ry = 0.0, rz = sinAz;
        double cx = startR * rx;
        double cy = 0.0;
        double cz = startR * rz;

        double cosInc = Math.cos(incRad);
        double sinInc = Math.sin(incRad);
        // Prograde equatorial tangent (-sinAz, 0, cosAz) tilted by user orbital inclination
        double tx = -sinAz * cosInc;
        double ty = sinInc;
        double tz = cosAz * cosInc;

        // Exact near-parabolic Paczynski-Wiita grazing velocity targeting periapsis r_p = r_t / beta
        double phiP = -1.0 / Math.max(0.35, rP - rEff);
        double phi0 = -1.0 / Math.max(0.35, startR - rEff);
        // Slightly bound center-of-mass energy so inner half binds & circularizes while outer half escapes
        double eps0 = (beta > 2.2f) ? -0.008 : -0.003;
        double vPSq = Math.max(0.02, 2.0 * (eps0 - phiP));
        double angMomL = rP * Math.sqrt(vPSq);
        double v0Sq = Math.max(0.02, 2.0 * (eps0 - phi0));
        double vTan = Math.min(Math.sqrt(v0Sq) * 0.94, angMomL / startR);
        double vRadIn = -Math.sqrt(Math.max(0.002, v0Sq - vTan * vTan));

        double vBaseX = tx * vTan + rx * vRadIn;
        double vBaseY = ty * vTan + ry * vRadIn;
        double vBaseZ = tz * vTan + rz * vRadIn;

        double starRadius = arch.starRadius;
        double densExp = arch.densityExponent;
        double baseTemp = arch.photosphereTempK;
        double tempSpan = arch.coreTempK - arch.photosphereTempK;
        // Tighter internal dispersion for low-beta partial disruptions so a luminous core survives periapsis
        double dispBase = (beta < 1.15f) ? 0.0055 : 0.0095;

        spawnStaging.clear();
        for (int i = 0; i < PARTICLES_PER_STAR; i++) {
            double u = nextRandom01();
            double radialFrac = Math.pow(u, densExp);
            double rad = starRadius * radialFrac;

            double cosTheta = 2.0 * nextRandom01() - 1.0;
            double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
            double phi = 2.0 * Math.PI * nextRandom01();

            double ox = rad * sinTheta * Math.cos(phi);
            double oy = rad * cosTheta;
            double oz = rad * sinTheta * Math.sin(phi);

            double disp = dispBase * (0.45 + 0.85 * radialFrac);
            double vx = vBaseX + (-oz * 0.011) + (nextRandom01() - 0.5) * disp;
            double vy = vBaseY + (nextRandom01() - 0.5) * disp;
            double vz = vBaseZ + (ox * 0.011) + (nextRandom01() - 0.5) * disp;

            float tempK = (float) (baseTemp + tempSpan * Math.pow(1.0 - radialFrac, 1.55));
            float mass = (float) (1.0 - 0.48 * radialFrac);

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

        deletePendingFences();
        hasAnyParticles = false;
        activeParticles = 0;
        boundParticles = 0;
        unboundParticles = 0;
        totalSwallowedParticles = 0;
        shearEnergy = 0.0f;
        horizonAccretionSurge = 0.0f;
        nextSlot = 0;
    }

    /**
     * Dispatches physics-safe fixed-substep compute passes (driven by {@code simDt}) and renders
     * active HDR volumetric velocity-stretched plasma ribbons directly into the bound {@code hdrFbo}.
     */
    public void updateAndRender(int width, int height, double wallDt, double simDt,
                                int blackbodyLutTex, int noise3dTex, float jitterX, float jitterY,
                                SimulationState state, CameraController camera) {
        if (!hasAnyParticles || state.wormholeMode || state.binaryMode) {
            shearEnergy *= 0.92f;
            horizonAccretionSurge *= 0.90f;
            state.tdeHorizonSurge = horizonAccretionSurge;
            return;
        }

        // Poll any completed previous-frame ping-pong counter fence without ever stalling the GPU
        pollAsyncCounters(Math.max(0.004, wallDt));

        // 1. Physics-safe fixed-substep compute pass (never passes a huge raw simDt at 10x–25x timelapse)
        if (!state.paused && simDt > 0.0) {
            int substeps = StellarArchetype.computeSubstepCount(simDt);
            double subDt = StellarArchetype.computeSubstepDt(simDt);

            // Pick a counter slot that has no unread fence (avoid overwriting unread telemetry)
            int writeIdx = counterWriteIdx;
            if (counterFences[writeIdx] != NULL) {
                int alt = 1 - writeIdx;
                if (counterFences[alt] == NULL) {
                    writeIdx = alt;
                } else {
                    glDeleteSync(counterFences[writeIdx]);
                    counterFences[writeIdx] = NULL;
                }
            }
            int writeSsbo = counterSsbos[writeIdx];

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, writeSsbo);

            glBindBuffer(GL_SHADER_STORAGE_BUFFER, writeSsbo);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            computeProgram.use();
            glUniform1ui(uCompParticleCount, TOTAL_PARTICLES);
            glUniform1f(uCompDt, (float) (subDt * TDE_TIME_SCALE));
            glUniform1f(uCompSpinA, state.effectiveSpin());
            glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());
            glUniform2fv(uCompSlotShockBeta, slotShockBetaBuf);

            for (int s = 0; s < substeps; s++) {
                glUniform1i(uCompCountSnapshot, (s == substeps - 1) ? 1 : 0);
                glDispatchCompute(TOTAL_PARTICLES / WORKGROUP_SIZE, 1, 1);
                if (s < substeps - 1) {
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                }
            }
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

            counterFences[writeIdx] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            counterWriteIdx = 1 - writeIdx;
        } else {
            horizonAccretionSurge *= 0.96f;
        }
        state.tdeHorizonSurge = horizonAccretionSurge;

        // 2. Volumetric Instanced Velocity-Stretched HDR Plasma Ribbon Pass directly into hdrFbo
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
        glUniform4fv(uRendSlotStyle, slotStyleBuf);

        glBindVertexArray(emptyVao);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, TOTAL_PARTICLES);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
    }

    /** Zero-stall non-blocking poll of ping-pong atomic counter SSBOs via GLsync fence. */
    private void pollAsyncCounters(double wallDt) {
        int readIdx = 1 - counterWriteIdx;
        for (int pass = 0; pass < 2; pass++) {
            int idx = (pass == 0) ? readIdx : counterWriteIdx;
            long fence = counterFences[idx];
            if (fence == NULL) {
                continue;
            }
            int status = glClientWaitSync(fence, 0, 0L);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
                glDeleteSync(fence);
                counterFences[idx] = NULL;

                readCounters.clear();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbos[idx]);
                glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readCounters);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

                activeParticles = readCounters.get(0);
                int hotShear = readCounters.get(1);
                boundParticles = readCounters.get(2);
                unboundParticles = readCounters.get(3);
                int swallowedDelta = readCounters.get(4);

                totalSwallowedParticles += swallowedDelta;

                float targetShear = Math.min(1.0f, hotShear / 6200.0f);
                shearEnergy = shearEnergy * 0.78f + targetShear * 0.22f;

                float swallowRatePerSec = (float) (swallowedDelta / Math.max(0.008, wallDt));
                float targetSurge = Math.min(1.0f, swallowRatePerSec / 4200.0f);
                float blend = (targetSurge > horizonAccretionSurge) ? 0.35f : 0.08f;
                horizonAccretionSurge = horizonAccretionSurge * (1.0f - blend) + targetSurge * blend;

                if (activeParticles == 0) {
                    hasAnyParticles = false;
                    boundParticles = 0;
                    unboundParticles = 0;
                    shearEnergy = 0.0f;
                }
                break;
            }
        }
    }

    public int activeParticleCount() {
        return activeParticles;
    }

    public int boundParticleCount() {
        return boundParticles;
    }

    public int unboundParticleCount() {
        return unboundParticles;
    }

    public int totalSwallowedCount() {
        return totalSwallowedParticles;
    }

    public float shearEnergy() {
        return shearEnergy;
    }

    public float horizonAccretionSurge() {
        return horizonAccretionSurge;
    }

    /**
     * Advances the TDE compute solver by {@code steps} fixed ticks without rendering.
     * Used by offline showcase capture to position a star cleanly at mid-disruption periapsis.
     */
    public void stepComputeOnly(int steps, double dt, SimulationState state) {
        if (!hasAnyParticles || state.wormholeMode || state.binaryMode || steps <= 0) {
            return;
        }
        deletePendingFences();
        int ssbo = counterSsbos[0];
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

        computeProgram.use();
        glUniform1ui(uCompParticleCount, TOTAL_PARTICLES);
        glUniform1f(uCompDt, (float) (dt * TDE_TIME_SCALE));
        glUniform1f(uCompSpinA, state.effectiveSpin());
        glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());
        glUniform2fv(uCompSlotShockBeta, slotShockBetaBuf);

        for (int i = 0; i < steps; i++) {
            glUniform1i(uCompCountSnapshot, (i == steps - 1) ? 1 : 0);
            glDispatchCompute(TOTAL_PARTICLES / WORKGROUP_SIZE, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        readCounters.clear();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readCounters);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        activeParticles = readCounters.get(0);
        int hotShear = readCounters.get(1);
        boundParticles = readCounters.get(2);
        unboundParticles = readCounters.get(3);
        totalSwallowedParticles += readCounters.get(4);
        shearEnergy = Math.min(1.0f, hotShear / 6200.0f);
        horizonAccretionSurge = Math.min(1.0f, readCounters.get(4) / 14000.0f);
        state.tdeHorizonSurge = horizonAccretionSurge;
    }

    private void syncSlotUniformBuffers() {
        slotShockBetaBuf.clear();
        slotStyleBuf.clear();
        for (int i = 0; i < MAX_STAR_SLOTS; i++) {
            StellarArchetype a = slotArchetype[i];
            slotShockBetaBuf.put(a.shockGain).put(slotBeta[i]);
            slotStyleBuf.put(a.tintR).put(a.tintG).put(a.tintB).put(a.ribbonScale);
        }
        slotShockBetaBuf.flip();
        slotStyleBuf.flip();
    }

    private void deletePendingFences() {
        for (int i = 0; i < 2; i++) {
            if (counterFences[i] != NULL) {
                glDeleteSync(counterFences[i]);
                counterFences[i] = NULL;
            }
        }
    }

    private double nextRandom01() {
        rngState = rngState * 6364136223846793005L + 1442695040888963407L;
        return ((rngState >>> 11) * 0x1.0p-53);
    }

    @Override
    public void close() {
        deletePendingFences();
        MemoryUtil.memFree(spawnStaging);
        MemoryUtil.memFree(readCounters);
        MemoryUtil.memFree(slotShockBetaBuf);
        MemoryUtil.memFree(slotStyleBuf);
        glDeleteBuffers(particleSsbo);
        glDeleteBuffers(counterSsbos[0]);
        glDeleteBuffers(counterSsbos[1]);
        glDeleteVertexArrays(emptyVao);
        computeProgram.close();
        renderProgram.close();
    }
}
