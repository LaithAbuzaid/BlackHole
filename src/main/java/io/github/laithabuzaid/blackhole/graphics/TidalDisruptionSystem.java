package io.github.laithabuzaid.blackhole.graphics;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * OpenGL 4.3 Compute Shader Tidal Disruption Event (TDE Lab) solver and HDR volumetric
 * plasma ribbon renderer.
 *
 * <p><b>v2.0 Physics &amp; GPU Architecture:</b>
 * <ul>
 *   <li>Standard Paczynski-Wiita potential ({@code V = -1/(r - 2M)}) paired with exact
 *       kinetic-energy-preserving Boris rotation for Kerr Lense-Thirring precession
 *       (zero artificial azimuthal thrust).</li>
 *   <li>Cleanly separated, toggleable {@code 64^3} GPU Eulerian-Lagrangian hydrodynamic grid
 *       ({@code tidal_hydro_splat.comp} -&gt; {@code tidal_disruption.comp}) providing polytropic
 *       pressure gradients ({@code -grad P / rho}) and momentum-conserving stream-intersection
 *       shock viscosity vs. Pure Gravity Conservation Mode.</li>
 *   <li>Single-dispatch in-shader substep loop (eliminating 14x-760x redundant SSBO traffic),
 *       256-thread workgroup {@code shared} atomic reductions, explicit
 *       {@code GL_BUFFER_UPDATE_BARRIER_BIT} before async readback, and GPU-side
 *       {@code glClearBufferData} zeroing.</li>
 * </ul>
 */
public final class TidalDisruptionSystem implements AutoCloseable {

    public static final int PARTICLES_PER_STAR = 65536;
    public static final int MAX_STAR_SLOTS = 2;
    public static final int TOTAL_PARTICLES = PARTICLES_PER_STAR * MAX_STAR_SLOTS;
    private static final int FLOATS_PER_PARTICLE = 8; // posMass(4) + velTemp(4)
    private static final int WORKGROUP_SIZE = 256;
    private static final float TDE_TIME_SCALE = 6.5f;
    private static final int COUNTER_UINTS = 8; // active, shear, bound, unbound, swallowed, energyFixed, angMomYFixed, peakTempK
    private static final int HYDRO_GRID_CELLS = 64 * 64 * 64; // 262,144 cells * 16 bytes = 4 MiB
    public static final int TDE_HIST_LEN = 160;

    private final ShaderProgram hydroSplatProgram;
    private final ShaderProgram computeProgram;
    private final ShaderProgram renderProgram;
    private final ShaderProgram hydroVisProgram;

    private final int particleSsbo;
    private final int hydroGridSsbo;
    private final int[] counterSsbos = new int[2];
    private final long[] counterFences = new long[2];
    private int counterWriteIdx = 0;
    private final int emptyVao;

    // Pre-allocated off-heap staging buffers (zero heap allocation on spawn or readback)
    private final FloatBuffer spawnStaging;
    private final IntBuffer readCounters;
    private final FloatBuffer slotShockBetaBuf;
    private final FloatBuffer slotStyleBuf;

    // Per-slot archetype metadata
    private final StellarArchetype[] slotArchetype = {
            StellarArchetype.SOLAR_MAIN_SEQ, StellarArchetype.SOLAR_MAIN_SEQ
    };
    private final float[] slotBeta = {1.55f, 1.55f};

    // Cached compute uniform locations
    private final int uSplatParticleCount;
    private final int uCompParticleCount;
    private final int uCompDt;
    private final int uCompSubsteps;
    private final int uCompSpinA;
    private final int uCompHorizonR;
    private final int uCompHydroEnabled;
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
    private final int uRendColorMode;

    // Cached hydro grid visualizer uniform locations
    private final int uHydResolution;
    private final int uHydCamPos;
    private final int uHydCamForward;
    private final int uHydCamRight;
    private final int uHydCamUp;
    private final int uHydTanHalfFov;
    private final int uHydVisMode;
    private final int uHydSlicePlane;
    private final int uHydHorizonR;

    private boolean hydroGridSplatCurrent = false;

    private int nextSlot = 0;
    private int spawnCounter = 0;
    private int activeSlotCount = 0;
    private boolean hasAnyParticles = false;
    private int activeParticles = 0;
    private int boundParticles = 0;
    private int unboundParticles = 0;
    private int totalSwallowedParticles = 0;
    private float shearEnergy = 0.0f;
    private float horizonAccretionSurge = 0.0f;
    private float totalSpecificEnergy = 0.0f;
    private float totalAngularMomentumY = 0.0f;
    private int peakTemperatureK = 0;
    private int lastSubstepsPerFrame = 0;
    private double particleStepsPerSec = 0.0;
    private long rngState = TdeInitialConditions.DEFAULT_SEED;
    private boolean closed = false;

    // Synchronized TDE launch metadata & disruption timeline ring buffers (zero per-frame alloc)
    private double tdeSimTime = 0.0;
    private int lastLaunchVariant = 0;
    private float lastInclinationDeg = 22.0f;
    private float lastSpinA = 0.98f;
    private float lastBeta = 1.55f;
    private float lastTidalRadius = StellarArchetype.SOLAR_MAIN_SEQ.tidalRadius;
    private float lastPeriapsis = (float) StellarArchetype.SOLAR_MAIN_SEQ.computePeriapsis(1.55, 1.2);
    private StellarArchetype lastArchetype = StellarArchetype.SOLAR_MAIN_SEQ;
    private float lastSwallowRatePerSec = 0.0f;

    private final float[] histTime = new float[TDE_HIST_LEN];
    private final float[] histBoundFrac = new float[TDE_HIST_LEN];
    private final float[] histUnboundFrac = new float[TDE_HIST_LEN];
    private final float[] histSwallowedFrac = new float[TDE_HIST_LEN];
    private final float[] histMeanEnergy = new float[TDE_HIST_LEN];
    private final float[] histMeanAngMomY = new float[TDE_HIST_LEN];
    private final float[] histPeakTempK = new float[TDE_HIST_LEN];
    private final float[] histSwallowRate = new float[TDE_HIST_LEN];
    private int histCursor = 0;
    private int histCount = 0;
    private double histSampleAccum = 0.0;

    public TidalDisruptionSystem() {
        ShaderProgram splat = null;
        ShaderProgram comp = null;
        ShaderProgram rend = null;
        ShaderProgram hvis = null;
        int vao = 0;
        int pSsbo = 0;
        int hSsbo = 0;
        FloatBuffer spawnBuf = null;
        IntBuffer cntBuf = null;
        FloatBuffer sbBuf = null;
        FloatBuffer stBuf = null;
        boolean ok = false;
        try {
            splat = ShaderProgram.fromComputeResource("/shaders/simulation/tidal_hydro_splat.comp");
            comp = ShaderProgram.fromComputeResource("/shaders/simulation/tidal_disruption.comp");
            rend = ShaderProgram.fromResources("/shaders/simulation/tidal_disruption.vert",
                    "/shaders/simulation/tidal_disruption.frag");
            hvis = ShaderProgram.fromResources("/shaders/common/fullscreen.vert",
                    "/shaders/simulation/tidal_hydro_vis.frag");

            vao = glGenVertexArrays();

            pSsbo = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, pSsbo);
            long particleBytes = (long) TOTAL_PARTICLES * FLOATS_PER_PARTICLE * Float.BYTES;
            glBufferData(GL_SHADER_STORAGE_BUFFER, particleBytes, GL_DYNAMIC_DRAW);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            hSsbo = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, hSsbo);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) HYDRO_GRID_CELLS * 4L * Integer.BYTES, GL_DYNAMIC_DRAW);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            for (int i = 0; i < 2; i++) {
                counterSsbos[i] = glGenBuffers();
                counterFences[i] = NULL;
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSsbos[i]);
                glBufferData(GL_SHADER_STORAGE_BUFFER, (long) COUNTER_UINTS * Integer.BYTES, GL_DYNAMIC_READ);
                glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
            }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

            spawnBuf = MemoryUtil.memAllocFloat(PARTICLES_PER_STAR * FLOATS_PER_PARTICLE);
            cntBuf = MemoryUtil.memAllocInt(COUNTER_UINTS);
            sbBuf = MemoryUtil.memAllocFloat(MAX_STAR_SLOTS * 2);
            stBuf = MemoryUtil.memAllocFloat(MAX_STAR_SLOTS * 4);

            this.hydroSplatProgram = splat;
            this.computeProgram = comp;
            this.renderProgram = rend;
            this.hydroVisProgram = hvis;
            this.emptyVao = vao;
            this.particleSsbo = pSsbo;
            this.hydroGridSsbo = hSsbo;
            this.spawnStaging = spawnBuf;
            this.readCounters = cntBuf;
            this.slotShockBetaBuf = sbBuf;
            this.slotStyleBuf = stBuf;
            syncSlotUniformBuffers();

            this.uSplatParticleCount = hydroSplatProgram.uniformLocation("uParticleCount");
            this.uCompParticleCount = computeProgram.uniformLocation("uParticleCount");
            this.uCompDt = computeProgram.uniformLocation("uDt");
            this.uCompSubsteps = computeProgram.uniformLocation("uSubsteps");
            this.uCompSpinA = computeProgram.uniformLocation("uSpinA");
            this.uCompHorizonR = computeProgram.uniformLocation("uHorizonR");
            this.uCompHydroEnabled = computeProgram.uniformLocation("uHydroEnabled");
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
            this.uRendColorMode = renderProgram.uniformLocation("uColorMode");

            this.uHydResolution = hydroVisProgram.uniformLocation("uResolution");
            this.uHydCamPos = hydroVisProgram.uniformLocation("uCamPos");
            this.uHydCamForward = hydroVisProgram.uniformLocation("uCamForward");
            this.uHydCamRight = hydroVisProgram.uniformLocation("uCamRight");
            this.uHydCamUp = hydroVisProgram.uniformLocation("uCamUp");
            this.uHydTanHalfFov = hydroVisProgram.uniformLocation("uTanHalfFov");
            this.uHydVisMode = hydroVisProgram.uniformLocation("uHydroVisMode");
            this.uHydSlicePlane = hydroVisProgram.uniformLocation("uSlicePlane");
            this.uHydHorizonR = hydroVisProgram.uniformLocation("uHorizonR");

            ShaderProgram.checkGlError("TidalDisruptionSystem.init");
            ok = true;
        } finally {
            if (!ok) {
                if (spawnBuf != null) MemoryUtil.memFree(spawnBuf);
                if (cntBuf != null) MemoryUtil.memFree(cntBuf);
                if (sbBuf != null) MemoryUtil.memFree(sbBuf);
                if (stBuf != null) MemoryUtil.memFree(stBuf);
                if (pSsbo != 0) glDeleteBuffers(pSsbo);
                if (hSsbo != 0) glDeleteBuffers(hSsbo);
                for (int i = 0; i < 2; i++) {
                    if (counterSsbos[i] != 0) glDeleteBuffers(counterSsbos[i]);
                }
                if (vao != 0) glDeleteVertexArrays(vao);
                if (splat != null) splat.close();
                if (comp != null) comp.close();
                if (rend != null) rend.close();
                if (hvis != null) hvis.close();
            }
        }
    }

    /**
     * Spawns a deterministic, centrally concentrated 65,536-particle star matching the selected
     * {@link StellarArchetype}, penetration factor {@code beta}, and orbital inclination with zero
     * Java heap allocations.
     */
    public void spawnStar(SimulationState state) {
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % MAX_STAR_SLOTS;
        int variant = spawnCounter++;
        activeSlotCount = Math.min(MAX_STAR_SLOTS, activeSlotCount + 1);

        StellarArchetype arch = state.currentTdeArchetype();
        float beta = StellarArchetype.clampBeta(state.tdeBeta);
        float incDeg = StellarArchetype.clampInclinationDeg(state.tdeInclinationDeg);

        slotArchetype[slot] = arch;
        slotBeta[slot] = beta;
        syncSlotUniformBuffers();

        lastLaunchVariant = variant;
        lastArchetype = arch;
        lastBeta = beta;
        lastInclinationDeg = incDeg;
        lastSpinA = state.effectiveSpin();
        lastTidalRadius = arch.tidalRadius;
        lastPeriapsis = (float) arch.computePeriapsis(beta, state.outerHorizonRadius());

        long seedToUse = (variant == 0) ? state.tdeSeed : rngState;
        rngState = TdeInitialConditions.generateStar(
                arch, beta, incDeg, state.effectiveSpin(), variant,
                PARTICLES_PER_STAR, seedToUse, spawnStaging);

        long byteOffset = (long) slot * PARTICLES_PER_STAR * FLOATS_PER_PARTICLE * Float.BYTES;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particleSsbo);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, spawnStaging);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        hasAnyParticles = true;
        hydroGridSplatCurrent = false;
        activeParticles = Math.min(TOTAL_PARTICLES, activeParticles + PARTICLES_PER_STAR);
        if (variant == 0) {
            tdeSimTime = 0.0;
            histCursor = 0;
            histCount = 0;
            histSampleAccum = 0.0;
            boundParticles = PARTICLES_PER_STAR;
            unboundParticles = 0;
            peakTemperatureK = Math.round(arch.coreTempK);
            recordTdeHistorySample();
        }
    }

    /** Clears all particle SSBO slots on GPU and resets deterministic launch counters & RNG seed. */
    public void clearAll() {
        clearAll(TdeInitialConditions.DEFAULT_SEED);
    }

    public void clearAll(long resetSeed) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particleSsbo);
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, hydroGridSsbo);
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        deletePendingFences();
        hasAnyParticles = false;
        activeSlotCount = 0;
        activeParticles = 0;
        boundParticles = 0;
        unboundParticles = 0;
        totalSwallowedParticles = 0;
        shearEnergy = 0.0f;
        horizonAccretionSurge = 0.0f;
        totalSpecificEnergy = 0.0f;
        totalAngularMomentumY = 0.0f;
        peakTemperatureK = 0;
        lastSubstepsPerFrame = 0;
        particleStepsPerSec = 0.0;
        nextSlot = 0;
        spawnCounter = 0;
        rngState = (resetSeed == 0L) ? TdeInitialConditions.DEFAULT_SEED : resetSeed;
        tdeSimTime = 0.0;
        lastSwallowRatePerSec = 0.0f;
        histCursor = 0;
        histCount = 0;
        histSampleAccum = 0.0;
        hydroGridSplatCurrent = false;
    }

    /** Executes TDE compute pass (64^3 hydro splat + single-dispatch substepped Verlet-Boris solver). */
    public void dispatchCompute(double wallDt, double simDt, SimulationState state) {
        if (!hasAnyParticles || state.wormholeMode || state.binaryMode) {
            shearEnergy *= 0.92f;
            horizonAccretionSurge *= 0.90f;
            state.tdeHorizonSurge = horizonAccretionSurge;
            lastSubstepsPerFrame = 0;
            particleStepsPerSec = 0.0;
            return;
        }

        pollAsyncCounters(Math.max(0.004, wallDt), !state.paused && simDt > 0.0);

        int dispatchCount = Math.max(PARTICLES_PER_STAR, activeSlotCount * PARTICLES_PER_STAR);
        int workgroups = dispatchCount / WORKGROUP_SIZE;

        boolean needHydroSplat = state.tdeHydroEnabled || (state.tdeHydroVisMode > 0 && !hydroGridSplatCurrent);
        if (!state.paused && simDt > 0.0) {
            int substeps = StellarArchetype.computeSubstepCount(simDt);
            double subDt = StellarArchetype.computeSubstepDt(simDt);
            double frameTdeDt = substeps * subDt * TDE_TIME_SCALE;
            tdeSimTime += frameTdeDt;
            histSampleAccum += frameTdeDt;
            lastSubstepsPerFrame = substeps;
            particleStepsPerSec = (activeParticles * (double) (substeps * 2)) / Math.max(1e-4, wallDt);

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
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hydroGridSsbo);

            glBindBuffer(GL_SHADER_STORAGE_BUFFER, writeSsbo);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            // Pass 1: 64^3 GPU Eulerian-Lagrangian density & momentum splat (for hydro solver or visualization)
            if (needHydroSplat) {
                dispatchHydroSplat(dispatchCount, workgroups);
            }

            // Pass 2: Single-dispatch in-shader substepped Verlet-Boris + optional grid hydro pressure/viscosity
            computeProgram.use();
            glUniform1ui(uCompParticleCount, dispatchCount);
            glUniform1f(uCompDt, (float) (subDt * TDE_TIME_SCALE));
            glUniform1i(uCompSubsteps, substeps);
            glUniform1f(uCompSpinA, state.effectiveSpin());
            glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());
            glUniform1i(uCompHydroEnabled, state.tdeHydroEnabled ? 1 : 0);
            glUniform2fv(uCompSlotShockBeta, slotShockBetaBuf);

            glDispatchCompute(workgroups, 1, 1);
            // Explicit GL_BUFFER_UPDATE_BARRIER_BIT guarantees coherent host glGetBufferSubData readback
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);

            // Refresh hydro grid after movement if visualizer is active so slice aligns with current particle state
            if (state.tdeHydroVisMode > 0) {
                dispatchHydroSplat(dispatchCount, workgroups);
            } else {
                hydroGridSplatCurrent = state.tdeHydroEnabled;
            }

            counterFences[writeIdx] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            counterWriteIdx = 1 - writeIdx;
        } else {
            if (state.tdeHydroVisMode > 0 && !hydroGridSplatCurrent) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hydroGridSsbo);
                dispatchHydroSplat(dispatchCount, workgroups);
            }
            horizonAccretionSurge *= 0.96f;
            lastSubstepsPerFrame = 0;
            particleStepsPerSec = 0.0;
        }
        state.tdeHorizonSurge = horizonAccretionSurge;
    }

    private void dispatchHydroSplat(int dispatchCount, int workgroups) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, hydroGridSsbo);
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

        hydroSplatProgram.use();
        glUniform1ui(uSplatParticleCount, dispatchCount);
        glDispatchCompute(workgroups, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        hydroGridSplatCurrent = true;
    }

    /** Renders active HDR volumetric plasma ribbons (and optional 64^3 Eulerian hydro grid) into bound {@code hdrFbo}. */
    public void renderParticles(int width, int height, int blackbodyLutTex, int noise3dTex,
                                float jitterX, float jitterY,
                                SimulationState state, CameraController camera) {
        if (!hasAnyParticles || state.wormholeMode || state.binaryMode) {
            return;
        }

        if (state.tdeHydroVisMode > 0) {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            hydroVisProgram.use();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hydroGridSsbo);
            glUniform2f(uHydResolution, width, height);
            glUniform3f(uHydCamPos, camera.posX, camera.posY, camera.posZ);
            glUniform3f(uHydCamForward, camera.forwardX, camera.forwardY, camera.forwardZ);
            glUniform3f(uHydCamRight, camera.rightX, camera.rightY, camera.rightZ);
            glUniform3f(uHydCamUp, camera.upX, camera.upY, camera.upZ);
            glUniform1f(uHydTanHalfFov, (float) Math.tan(Math.toRadians(state.fovDeg)));
            glUniform1i(uHydVisMode, state.tdeHydroVisMode);
            glUniform1i(uHydSlicePlane, state.tdeHydroSlicePlane);
            glUniform1f(uHydHorizonR, (float) state.outerHorizonRadius());
            glBindVertexArray(emptyVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindVertexArray(0);
            glDisable(GL_BLEND);
        }

        int drawCount = Math.max(PARTICLES_PER_STAR, activeSlotCount * PARTICLES_PER_STAR);

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
        glUniform1i(uRendColorMode, state.tdeParticleColorMode);

        glBindVertexArray(emptyVao);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, drawCount);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
    }

    /** Convenience combined compute + render call preserving backward compatibility. */
    public void updateAndRender(int width, int height, double wallDt, double simDt,
                                int blackbodyLutTex, int noise3dTex, float jitterX, float jitterY,
                                SimulationState state, CameraController camera) {
        dispatchCompute(wallDt, simDt, state);
        renderParticles(width, height, blackbodyLutTex, noise3dTex, jitterX, jitterY, state, camera);
    }

    /** Zero-stall non-blocking poll of ping-pong atomic counter SSBOs via GLsync fence. */
    private void pollAsyncCounters(double wallDt, boolean simRunning) {
        int readIdx = 1 - counterWriteIdx;
        for (int pass = 0; pass < 2; pass++) {
            int idx = (pass == 0) ? readIdx : counterWriteIdx;
            long fence = counterFences[idx];
            if (fence == NULL) {
                continue;
            }
            int status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
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
                int energyFixed = readCounters.get(5);
                int angMomFixed = readCounters.get(6);
                peakTemperatureK = readCounters.get(7);

                totalSwallowedParticles += swallowedDelta;
                if (activeParticles > 0) {
                    totalSpecificEnergy = (energyFixed / 256.0f) / activeParticles;
                    totalAngularMomentumY = (angMomFixed / 256.0f) / activeParticles;
                } else {
                    totalSpecificEnergy = 0.0f;
                    totalAngularMomentumY = 0.0f;
                }

                float targetShear = Math.min(1.0f, hotShear / 6200.0f);
                shearEnergy = shearEnergy * 0.78f + targetShear * 0.22f;

                float swallowRatePerSec = (float) (swallowedDelta / Math.max(0.008, wallDt));
                lastSwallowRatePerSec = lastSwallowRatePerSec * 0.80f + swallowRatePerSec * 0.20f;
                float targetSurge = Math.min(1.0f, swallowRatePerSec / 4200.0f);
                float blend = (targetSurge > horizonAccretionSurge) ? 0.35f : 0.08f;
                horizonAccretionSurge = horizonAccretionSurge * (1.0f - blend) + targetSurge * blend;

                if (simRunning && histSampleAccum >= 0.35) {
                    histSampleAccum = 0.0;
                    recordTdeHistorySample();
                }

                if (activeParticles == 0) {
                    hasAnyParticles = false;
                    activeSlotCount = 0;
                    boundParticles = 0;
                    unboundParticles = 0;
                    shearEnergy = 0.0f;
                }
                break;
            }
        }
    }

    private void recordTdeHistorySample() {
        float denom = Math.max(1.0f, activeParticles + totalSwallowedParticles);
        int i = histCursor;
        histTime[i] = (float) tdeSimTime;
        histBoundFrac[i] = boundParticles / denom;
        histUnboundFrac[i] = unboundParticles / denom;
        histSwallowedFrac[i] = totalSwallowedParticles / denom;
        histMeanEnergy[i] = totalSpecificEnergy;
        histMeanAngMomY[i] = totalAngularMomentumY;
        histPeakTempK[i] = peakTemperatureK;
        histSwallowRate[i] = lastSwallowRatePerSec;
        histCursor = (histCursor + 1) % TDE_HIST_LEN;
        if (histCount < TDE_HIST_LEN) {
            histCount++;
        }
    }

    public int activeParticleCount() { return activeParticles; }
    public int boundParticleCount() { return boundParticles; }
    public int unboundParticleCount() { return unboundParticles; }
    public int totalSwallowedCount() { return totalSwallowedParticles; }
    public float shearEnergy() { return shearEnergy; }
    public float horizonAccretionSurge() { return horizonAccretionSurge; }
    public float meanSpecificEnergy() { return totalSpecificEnergy; }
    public float meanAngularMomentumY() { return totalAngularMomentumY; }
    public int peakTemperatureK() { return peakTemperatureK; }
    public int lastSubstepsPerFrame() { return lastSubstepsPerFrame; }
    public double particleStepsPerSec() { return particleStepsPerSec; }
    public double tdeSimTime() { return tdeSimTime; }
    public StellarArchetype lastArchetype() { return lastArchetype; }
    public float lastBeta() { return lastBeta; }
    public float lastInclinationDeg() { return lastInclinationDeg; }
    public float lastSpinA() { return lastSpinA; }
    public int lastLaunchVariant() { return lastLaunchVariant; }
    public float lastTidalRadius() { return lastTidalRadius; }
    public float lastPeriapsis() { return lastPeriapsis; }
    public float lastSwallowRatePerSec() { return lastSwallowRatePerSec; }
    public int histCount() { return histCount; }
    public int histStartIdx() { return (histCount < TDE_HIST_LEN) ? 0 : histCursor; }
    public float[] histTime() { return histTime; }
    public float[] histBoundFrac() { return histBoundFrac; }
    public float[] histUnboundFrac() { return histUnboundFrac; }
    public float[] histSwallowedFrac() { return histSwallowedFrac; }
    public float[] histMeanEnergy() { return histMeanEnergy; }
    public float[] histMeanAngMomY() { return histMeanAngMomY; }
    public float[] histPeakTempK() { return histPeakTempK; }
    public float[] histSwallowRate() { return histSwallowRate; }

    /**
     * Advances the TDE compute solver by {@code steps} fixed ticks without rendering
     * (used by offline showcase capture). Executes in chunked multi-substep batches so
     * GPU VRAM bandwidth drops by >20x compared to 1-step dispatches.
     */
    public void stepComputeOnly(int steps, double dt, SimulationState state) {
        if (!hasAnyParticles || state.wormholeMode || state.binaryMode || steps <= 0) {
            return;
        }
        deletePendingFences();
        int ssbo = counterSsbos[0];
        int dispatchCount = Math.max(PARTICLES_PER_STAR, activeSlotCount * PARTICLES_PER_STAR);
        int workgroups = dispatchCount / WORKGROUP_SIZE;

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particleSsbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssbo);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hydroGridSsbo);

        int remaining = steps;
        int chunkSize = state.tdeHydroEnabled ? 4 : 32;
        while (remaining > 0) {
            int batch = Math.min(chunkSize, remaining);
            remaining -= batch;

            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);

            if (state.tdeHydroEnabled) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, hydroGridSsbo);
                glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (IntBuffer) null);
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

                hydroSplatProgram.use();
                glUniform1ui(uSplatParticleCount, dispatchCount);
                glDispatchCompute(workgroups, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            }

            computeProgram.use();
            glUniform1ui(uCompParticleCount, dispatchCount);
            glUniform1f(uCompDt, (float) (dt * TDE_TIME_SCALE));
            glUniform1i(uCompSubsteps, batch);
            glUniform1f(uCompSpinA, state.effectiveSpin());
            glUniform1f(uCompHorizonR, (float) state.outerHorizonRadius());
            glUniform1i(uCompHydroEnabled, state.tdeHydroEnabled ? 1 : 0);
            glUniform2fv(uCompSlotShockBeta, slotShockBetaBuf);
            glDispatchCompute(workgroups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);

            // Accumulate swallowed particles from each batch so telemetry stays strictly exact across chunks
            readCounters.clear();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readCounters);
            totalSwallowedParticles += readCounters.get(4);

            double chunkTdeDt = batch * dt * TDE_TIME_SCALE;
            tdeSimTime += chunkTdeDt;
            histSampleAccum += chunkTdeDt;
            activeParticles = readCounters.get(0);
            boundParticles = readCounters.get(2);
            unboundParticles = readCounters.get(3);
            int energyFixed = readCounters.get(5);
            int angMomFixed = readCounters.get(6);
            peakTemperatureK = readCounters.get(7);
            if (activeParticles > 0) {
                totalSpecificEnergy = (energyFixed / 256.0f) / activeParticles;
                totalAngularMomentumY = (angMomFixed / 256.0f) / activeParticles;
            }
            if (histSampleAccum >= 0.35) {
                histSampleAccum = 0.0;
                recordTdeHistorySample();
            }
        }

        if (state.tdeHydroVisMode > 0 || state.tdeHydroEnabled) {
            dispatchHydroSplat(dispatchCount, workgroups);
        } else {
            hydroGridSplatCurrent = false;
        }

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        activeParticles = readCounters.get(0);
        int hotShear = readCounters.get(1);
        boundParticles = readCounters.get(2);
        unboundParticles = readCounters.get(3);
        int energyFixed = readCounters.get(5);
        int angMomFixed = readCounters.get(6);
        peakTemperatureK = readCounters.get(7);
        if (activeParticles > 0) {
            totalSpecificEnergy = (energyFixed / 256.0f) / activeParticles;
            totalAngularMomentumY = (angMomFixed / 256.0f) / activeParticles;
        }
        shearEnergy = Math.min(1.0f, hotShear / 6200.0f);
        horizonAccretionSurge = Math.min(1.0f, totalSwallowedParticles / 18000.0f);
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        deletePendingFences();
        MemoryUtil.memFree(spawnStaging);
        MemoryUtil.memFree(readCounters);
        MemoryUtil.memFree(slotShockBetaBuf);
        MemoryUtil.memFree(slotStyleBuf);
        glDeleteBuffers(particleSsbo);
        glDeleteBuffers(hydroGridSsbo);
        glDeleteBuffers(counterSsbos[0]);
        glDeleteBuffers(counterSsbos[1]);
        glDeleteVertexArrays(emptyVao);
        hydroSplatProgram.close();
        computeProgram.close();
        renderProgram.close();
        hydroVisProgram.close();
    }
}
