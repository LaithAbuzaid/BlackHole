package io.github.laithabuzaid.blackhole.telemetry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

/**
 * Collects live 6-pass GPU, CPU-stage, JVM/GC, and TDE conservation telemetry with a
 * 120-frame oscilloscope and raw P50/P95/P99 frame-time percentiles — zero render-thread allocations.
 */
public final class HardwareTelemetry implements AutoCloseable {

    public static final int HISTORY_LEN = 120;

    // NVIDIA GL_NVX_gpu_memory_info query constants
    private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;

    public String gpuFullName = "GPU";
    public String gpuShortName = "GPU";
    public final int cpuCores = Runtime.getRuntime().availableProcessors();

    // Live per-frame timings updated by the render loop
    public float currentFps = 60.0f;
    public float wallFrameMs = 16.6f;
    public float cpuPhysicsMs = 0.02f;
    public float cpuSubmitMs = 0.15f;
    public float cpuHudMs = 0.02f;
    public float gpuRayMs = 2.5f;
    public float gpuTdeComputeMs = 0.0f;
    public float gpuTdeRenderMs = 0.0f;
    public float gpuTdeMs = 0.0f;
    public float gpuTaaMs = 0.15f;
    public float gpuPostMs = 0.25f;
    public float gpuBloomMs = 0.40f;
    public float gpuUiMs = 0.02f;
    public float gpuTotalMs = 2.92f;
    public float gpuP95Ms = 2.92f;
    public float gpuP99Ms = 2.92f;

    // TDE physics & throughput telemetry
    public int tdeActiveParticles = 0;
    public int tdeBoundParticles = 0;
    public int tdeUnboundParticles = 0;
    public int tdeSwallowedParticles = 0;
    public float tdeHorizonSurge = 0.0f;
    public float tdeMeanEnergy = 0.0f;
    public float tdeMeanAngMomY = 0.0f;
    public int tdePeakTempK = 0;
    public int tdeSubsteps = 0;
    public double tdeParticleStepsPerSec = 0.0;

    // Rolling 120-frame oscilloscope buffers (unsmoothed raw GPU/CPU ms so spikes remain visible!)
    public final float[] gpuHistory = new float[HISTORY_LEN];
    public final float[] cpuHistory = new float[HISTORY_LEN];
    private final float[] sortScratch = new float[HISTORY_LEN];
    public int historyCursor = 0;
    private int historyFilled = 0;

    // Zero-GC render-loop verification metrics
    public volatile long gcCollectionCount = 0L;
    public volatile long gcCollectionTimeMs = 0L;
    public long renderAllocBytesLastFrame = 0L;
    private final com.sun.management.ThreadMXBean sunThreadBean;
    private long renderThreadId = -1L;
    private long lastAllocatedBytes = -1L;

    // Async background metrics
    public volatile float processCpuPct = 0f;
    public volatile float systemCpuPct = 0f;
    public volatile long heapUsedMb = 0;
    public volatile long heapMaxMb = 0;

    public volatile int hardwareGpuUtilPct = -1;
    public volatile int vramUsedMb = -1;
    public volatile int vramTotalMb = -1;
    public volatile int gpuTempC = -1;
    public volatile int gpuClockMhz = -1;

    private final int[] nvxTotalKb = new int[1];
    private final int[] nvxAvailKb = new int[1];
    private volatile boolean nvxSupported = true;

    private volatile boolean running = true;
    private volatile Process activeSmiProcess = null;
    private Thread samplerThread;

    public HardwareTelemetry() {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        this.sunThreadBean = (tb instanceof com.sun.management.ThreadMXBean st && st.isThreadAllocatedMemorySupported())
                ? st : null;
    }

    public void initOpenGlInfo(String renderer, String version) {
        this.renderThreadId = Thread.currentThread().getId();
        if (renderer != null) {
            this.gpuFullName = renderer;
            String clean = renderer.replace("NVIDIA GeForce ", "")
                    .replace("AMD Radeon ", "")
                    .replace("/PCIe/SSE2", "")
                    .trim();
            this.gpuShortName = clean.length() > 26 ? clean.substring(0, 26) : clean;
        }
        pollOpenGlVram();
    }

    /** Zero-allocation render-thread VRAM query via GL_NVX_gpu_memory_info. */
    public void pollOpenGlVram() {
        if (!nvxSupported) {
            return;
        }
        nvxTotalKb[0] = 0;
        nvxAvailKb[0] = 0;
        glGetIntegerv(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX, nvxTotalKb);
        glGetIntegerv(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, nvxAvailKb);
        if (glGetError() == GL_NO_ERROR && nvxTotalKb[0] > 0) {
            vramTotalMb = nvxTotalKb[0] / 1024;
            vramUsedMb = Math.max(0, (nvxTotalKb[0] - nvxAvailKb[0]) / 1024);
        } else {
            nvxSupported = false;
        }
    }

    /** Updates all 6 GPU pass timings (EMA for display, raw total for P95/P99 & oscilloscope). */
    public void updateGpuPassTimings(float rayMs, float tdeCompMs, float tdeRendMs,
                                     float taaMs, float postMs, float uiMs) {
        gpuRayMs = gpuRayMs * 0.85f + rayMs * 0.15f;
        gpuTdeComputeMs = gpuTdeComputeMs * 0.85f + tdeCompMs * 0.15f;
        gpuTdeRenderMs = gpuTdeRenderMs * 0.85f + tdeRendMs * 0.15f;
        gpuTdeMs = gpuTdeComputeMs + gpuTdeRenderMs;
        gpuTaaMs = gpuTaaMs * 0.85f + taaMs * 0.15f;
        gpuPostMs = gpuPostMs * 0.85f + postMs * 0.15f;
        gpuBloomMs = gpuTaaMs + gpuPostMs;
        gpuUiMs = gpuUiMs * 0.85f + uiMs * 0.15f;
        gpuTotalMs = gpuRayMs + gpuTdeMs + gpuBloomMs + gpuUiMs;
    }

    /** Backward-compatible 4-pass overload. */
    public void updateGpuPassTimings(float rayMs, float tdeMs, float bloomMs, float uiMs) {
        updateGpuPassTimings(rayMs, tdeMs, 0.0f, 0.0f, bloomMs, uiMs);
    }

    /** Records raw CPU & GPU frame samples and updates P95/P99 and render-thread allocation counter. */
    public void recordFrameSample(float rawCpuSubmitMs, float rawGpuTotalMs) {
        cpuSubmitMs = cpuSubmitMs * 0.85f + rawCpuSubmitMs * 0.15f;
        gpuHistory[historyCursor] = (rawGpuTotalMs > 0.0f) ? rawGpuTotalMs : gpuTotalMs;
        cpuHistory[historyCursor] = rawCpuSubmitMs;
        historyCursor = (historyCursor + 1) % HISTORY_LEN;
        if (historyFilled < HISTORY_LEN) {
            historyFilled++;
        }

        // Update P95/P99 every 16 frames without allocations using sortScratch
        if ((historyCursor & 15) == 0 && historyFilled >= 16) {
            System.arraycopy(gpuHistory, 0, sortScratch, 0, historyFilled);
            Arrays.sort(sortScratch, 0, historyFilled);
            gpuP95Ms = sortScratch[Math.min(historyFilled - 1, (int) (historyFilled * 0.95f))];
            gpuP99Ms = sortScratch[Math.min(historyFilled - 1, (int) (historyFilled * 0.99f))];
        }

        if (sunThreadBean != null && renderThreadId >= 0L) {
            long allocNow = sunThreadBean.getThreadAllocatedBytes(renderThreadId);
            if (lastAllocatedBytes >= 0L && allocNow >= lastAllocatedBytes) {
                renderAllocBytesLastFrame = allocNow - lastAllocatedBytes;
            }
            lastAllocatedBytes = allocNow;
        }
    }

    public void recordFrameSample(float rawCpuSubmitMs) {
        recordFrameSample(rawCpuSubmitMs, gpuTotalMs);
    }

    public float getGpuLoadPercent(boolean vsync) {
        if (hardwareGpuUtilPct >= 0) {
            return hardwareGpuUtilPct;
        }
        float budget = vsync ? Math.max(6.94f, wallFrameMs) : Math.max(0.5f, wallFrameMs);
        return Math.min(100f, Math.max(1f, (gpuTotalMs / budget) * 100f));
    }

    public void startBackgroundSampler() {
        samplerThread = new Thread(() -> {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            com.sun.management.OperatingSystemMXBean sunOsBean =
                    (osBean instanceof com.sun.management.OperatingSystemMXBean s) ? s : null;
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            boolean nvidiaSmiAvailable = true;
            int smiDivider = 0;

            while (running) {
                try {
                    Runtime rt = Runtime.getRuntime();
                    heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
                    heapMaxMb = rt.maxMemory() / (1024L * 1024L);

                    long gcCnt = 0L;
                    long gcMs = 0L;
                    for (int i = 0; i < gcBeans.size(); i++) {
                        long c = gcBeans.get(i).getCollectionCount();
                        long t = gcBeans.get(i).getCollectionTime();
                        if (c > 0) gcCnt += c;
                        if (t > 0) gcMs += t;
                    }
                    gcCollectionCount = gcCnt;
                    gcCollectionTimeMs = gcMs;

                    if (sunOsBean != null) {
                        double proc = sunOsBean.getProcessCpuLoad();
                        if (proc >= 0.0) processCpuPct = (float) (proc * 100.0);
                        double sys = sunOsBean.getCpuLoad();
                        if (sys >= 0.0) systemCpuPct = (float) (sys * 100.0);
                    }

                    if (nvidiaSmiAvailable && (smiDivider++ % 2 == 0)) {
                        Process p = new ProcessBuilder(
                                "nvidia-smi",
                                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,clocks.current.graphics",
                                "--format=csv,noheader,nounits"
                        ).redirectErrorStream(true).start();
                        activeSmiProcess = p;

                        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                            String line = br.readLine();
                            if (line != null && line.contains(",")) {
                                String[] parts = line.split(",");
                                if (parts.length >= 5) {
                                    int util = parseRoundedInt(parts[0], -1);
                                    int memUsed = parseRoundedInt(parts[1], -1);
                                    int memTotal = parseRoundedInt(parts[2], -1);
                                    int temp = parseRoundedInt(parts[3], -1);
                                    int clock = parseRoundedInt(parts[4], -1);
                                    if (util >= 0) hardwareGpuUtilPct = util;
                                    // Avoid VRAM data race: only write VRAM from nvidia-smi when GL NVX is unavailable
                                    if (!nvxSupported) {
                                        if (memUsed >= 0) vramUsedMb = memUsed;
                                        if (memTotal > 0) vramTotalMb = memTotal;
                                    }
                                    if (temp > 0) gpuTempC = temp;
                                    if (clock > 0) gpuClockMhz = clock;
                                }
                            } else {
                                nvidiaSmiAvailable = false;
                            }
                        } finally {
                            p.waitFor();
                            activeSmiProcess = null;
                        }
                    }

                    Thread.sleep(350);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception ignored) {
                    nvidiaSmiAvailable = false;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }, "Hardware-Telemetry-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public void stop() {
        running = false;
        Process p = activeSmiProcess;
        if (p != null) {
            p.destroyForcibly();
            activeSmiProcess = null;
        }
        if (samplerThread != null) {
            samplerThread.interrupt();
            try {
                samplerThread.join(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            samplerThread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static int parseRoundedInt(String raw, int fallback) {
        try {
            return Math.round(Float.parseFloat(raw.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
