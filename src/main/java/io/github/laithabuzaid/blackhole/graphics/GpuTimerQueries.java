package io.github.laithabuzaid.blackhole.graphics;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL33.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33.glGetQueryObjectui64;

/**
 * Zero-allocation asynchronous 3-slot ring-buffered OpenGL {@code GL_TIME_ELAPSED} timer queries
 * providing 6-pass GPU timing breakdown:
 * {@code Raytrace}, {@code TDE Compute}, {@code TDE Render}, {@code TAA Resolve},
 * {@code Post (Bloom + Anamorphic + Tonemap)}, and {@code UI}.
 */
public final class GpuTimerQueries implements AutoCloseable {

    private static final int SLOTS = 3;

    private final int[] ray = new int[SLOTS];
    private final int[] tdeCompute = new int[SLOTS];
    private final int[] tdeRender = new int[SLOTS];
    private final int[] taa = new int[SLOTS];
    private final int[] post = new int[SLOTS];
    private final int[] ui = new int[SLOTS];

    private int writeIndex = 0;
    private int framesRecorded = 0;
    private boolean closed = false;

    private long lastRayNs = 0L;
    private long lastTdeComputeNs = 0L;
    private long lastTdeRenderNs = 0L;
    private long lastTaaNs = 0L;
    private long lastPostNs = 0L;
    private long lastUiNs = 0L;

    public GpuTimerQueries() {
        for (int i = 0; i < SLOTS; i++) {
            ray[i] = glGenQueries();
            tdeCompute[i] = glGenQueries();
            tdeRender[i] = glGenQueries();
            taa[i] = glGenQueries();
            post[i] = glGenQueries();
            ui[i] = glGenQueries();
        }
    }

    public void beginRay() { glBeginQuery(GL_TIME_ELAPSED, ray[writeIndex]); }
    public void endRay() { glEndQuery(GL_TIME_ELAPSED); }

    public void beginTdeCompute() { glBeginQuery(GL_TIME_ELAPSED, tdeCompute[writeIndex]); }
    public void endTdeCompute() { glEndQuery(GL_TIME_ELAPSED); }

    public void beginTdeRender() { glBeginQuery(GL_TIME_ELAPSED, tdeRender[writeIndex]); }
    public void endTdeRender() { glEndQuery(GL_TIME_ELAPSED); }

    public void beginTaa() { glBeginQuery(GL_TIME_ELAPSED, taa[writeIndex]); }
    public void endTaa() { glEndQuery(GL_TIME_ELAPSED); }

    public void beginPost() { glBeginQuery(GL_TIME_ELAPSED, post[writeIndex]); }
    public void endPost() { glEndQuery(GL_TIME_ELAPSED); }

    public void beginUi() { glBeginQuery(GL_TIME_ELAPSED, ui[writeIndex]); }
    public void endUi() { glEndQuery(GL_TIME_ELAPSED); }

    /**
     * Polls the oldest ring slot ({@code writeIndex}, 3 frames behind — maximum latency headroom)
     * after confirming ALL 6 query objects are ready (zero pipeline stalls).
     */
    public boolean pollPreviousFrame() {
        if (framesRecorded < SLOTS) {
            return false;
        }
        int readIdx = writeIndex;
        if (glGetQueryObjecti(ray[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0
                || glGetQueryObjecti(tdeCompute[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0
                || glGetQueryObjecti(tdeRender[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0
                || glGetQueryObjecti(taa[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0
                || glGetQueryObjecti(post[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0
                || glGetQueryObjecti(ui[readIdx], GL_QUERY_RESULT_AVAILABLE) == 0) {
            return false;
        }
        lastRayNs = glGetQueryObjectui64(ray[readIdx], GL_QUERY_RESULT);
        lastTdeComputeNs = glGetQueryObjectui64(tdeCompute[readIdx], GL_QUERY_RESULT);
        lastTdeRenderNs = glGetQueryObjectui64(tdeRender[readIdx], GL_QUERY_RESULT);
        lastTaaNs = glGetQueryObjectui64(taa[readIdx], GL_QUERY_RESULT);
        lastPostNs = glGetQueryObjectui64(post[readIdx], GL_QUERY_RESULT);
        lastUiNs = glGetQueryObjectui64(ui[readIdx], GL_QUERY_RESULT);
        return true;
    }

    public float rayMs() { return lastRayNs * 1e-6f; }
    public float tdeComputeMs() { return lastTdeComputeNs * 1e-6f; }
    public float tdeRenderMs() { return lastTdeRenderNs * 1e-6f; }
    public float tdeMs() { return (lastTdeComputeNs + lastTdeRenderNs) * 1e-6f; }
    public float taaMs() { return lastTaaNs * 1e-6f; }
    public float postMs() { return lastPostNs * 1e-6f; }
    public float bloomMs() { return (lastTaaNs + lastPostNs) * 1e-6f; }
    public float uiMs() { return lastUiNs * 1e-6f; }

    public float totalGpuMs() {
        return (lastRayNs + lastTdeComputeNs + lastTdeRenderNs + lastTaaNs + lastPostNs + lastUiNs) * 1e-6f;
    }

    public void advanceFrame() {
        writeIndex = (writeIndex + 1) % SLOTS;
        if (framesRecorded < SLOTS) {
            framesRecorded++;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = 0; i < SLOTS; i++) {
            glDeleteQueries(ray[i]);
            glDeleteQueries(tdeCompute[i]);
            glDeleteQueries(tdeRender[i]);
            glDeleteQueries(taa[i]);
            glDeleteQueries(post[i]);
            glDeleteQueries(ui[i]);
        }
    }
}
