package com.mycompany.mavenproject1.graphics;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.glBeginQuery;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glEndQuery;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33.glGetQueryObjectui64;

/**
 * Zero-allocation asynchronous 3-slot ring-buffered OpenGL {@code GL_TIME_ELAPSED}
 * timer queries tracking Raytrace, TDE Compute+Particles, Bloom, and UI passes
 * without GPU pipeline stalls.
 */
public final class GpuTimerQueries implements AutoCloseable {

    private static final int SLOTS = 3;

    private final int[] ray = new int[SLOTS];
    private final int[] tde = new int[SLOTS];
    private final int[] bloom = new int[SLOTS];
    private final int[] ui = new int[SLOTS];

    private int writeIndex = 0;
    private int framesRecorded = 0;

    private long lastRayNs = 0L;
    private long lastTdeNs = 0L;
    private long lastBloomNs = 0L;
    private long lastUiNs = 0L;

    public GpuTimerQueries() {
        for (int i = 0; i < SLOTS; i++) {
            ray[i] = glGenQueries();
            tde[i] = glGenQueries();
            bloom[i] = glGenQueries();
            ui[i] = glGenQueries();
        }
    }

    public void beginRay() {
        glBeginQuery(GL_TIME_ELAPSED, ray[writeIndex]);
    }

    public void endRay() {
        glEndQuery(GL_TIME_ELAPSED);
    }

    public void beginTde() {
        glBeginQuery(GL_TIME_ELAPSED, tde[writeIndex]);
    }

    public void endTde() {
        glEndQuery(GL_TIME_ELAPSED);
    }

    public void beginBloom() {
        glBeginQuery(GL_TIME_ELAPSED, bloom[writeIndex]);
    }

    public void endBloom() {
        glEndQuery(GL_TIME_ELAPSED);
    }

    public void beginUi() {
        glBeginQuery(GL_TIME_ELAPSED, ui[writeIndex]);
    }

    public void endUi() {
        glEndQuery(GL_TIME_ELAPSED);
    }

    /**
     * Reads the oldest completed frame (2 frames behind) into primitive fields.
     *
     * @return {@code true} if updated timings were read from the GPU.
     */
    public boolean pollPreviousFrame() {
        if (framesRecorded < SLOTS) {
            return false;
        }
        int readIdx = (writeIndex + 1) % SLOTS;
        if (glGetQueryObjecti(ui[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0) {
            return false;
        }
        lastRayNs = glGetQueryObjectui64(ray[readIdx], GL_QUERY_RESULT);
        lastTdeNs = glGetQueryObjectui64(tde[readIdx], GL_QUERY_RESULT);
        lastBloomNs = glGetQueryObjectui64(bloom[readIdx], GL_QUERY_RESULT);
        lastUiNs = glGetQueryObjectui64(ui[readIdx], GL_QUERY_RESULT);
        return true;
    }

    public float rayMs() {
        return lastRayNs * 1e-6f;
    }

    public float tdeMs() {
        return lastTdeNs * 1e-6f;
    }

    public float bloomMs() {
        return lastBloomNs * 1e-6f;
    }

    public float uiMs() {
        return lastUiNs * 1e-6f;
    }

    public void advanceFrame() {
        writeIndex = (writeIndex + 1) % SLOTS;
        if (framesRecorded < SLOTS) {
            framesRecorded++;
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < SLOTS; i++) {
            glDeleteQueries(ray[i]);
            glDeleteQueries(tde[i]);
            glDeleteQueries(bloom[i]);
            glDeleteQueries(ui[i]);
        }
    }
}
