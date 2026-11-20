package com.mycompany.mavenproject1.telemetry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

/**
 * Collects live GPU/CPU/JVM hardware telemetry and maintains a 120-frame
 * GPU vs. CPU oscilloscope history with zero render-thread heap allocations.
 */
public final class HardwareTelemetry {

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
    public float cpuSubmitMs = 0.15f;
    public float gpuRayMs = 2.5f;
    public float gpuTdeMs = 0.0f;
    public float gpuBloomMs = 0.3f;
    public float gpuUiMs = 0.08f;
    public float gpuTotalMs = 2.88f;
    public int tdeActiveParticles = 0;

    // Rolling 120-frame frame-time oscilloscope buffers
    public final float[] gpuHistory = new float[HISTORY_LEN];
    public final float[] cpuHistory = new float[HISTORY_LEN];
    public int historyCursor = 0;

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

    // Pre-allocated single-element arrays for zero-allocation GL VRAM polling
    private final int[] nvxTotalKb = new int[1];
    private final int[] nvxAvailKb = new int[1];
    private boolean nvxSupported = true;

    private volatile boolean running = true;
    private Thread samplerThread;

    public void initOpenGlInfo(String renderer, String version) {
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

    public void updateGpuPassTimings(float rayMs, float tdeMs, float bloomMs, float uiMs) {
        gpuRayMs = gpuRayMs * 0.85f + rayMs * 0.15f;
        gpuTdeMs = gpuTdeMs * 0.85f + tdeMs * 0.15f;
        gpuBloomMs = gpuBloomMs * 0.85f + bloomMs * 0.15f;
        gpuUiMs = gpuUiMs * 0.85f + uiMs * 0.15f;
        gpuTotalMs = gpuRayMs + gpuTdeMs + gpuBloomMs + gpuUiMs;
    }

    public void recordFrameSample(float rawCpuSubmitMs) {
        cpuSubmitMs = cpuSubmitMs * 0.85f + rawCpuSubmitMs * 0.15f;
        gpuHistory[historyCursor] = gpuTotalMs;
        cpuHistory[historyCursor] = cpuSubmitMs;
        historyCursor = (historyCursor + 1) % HISTORY_LEN;
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

            boolean nvidiaSmiAvailable = true;
            int smiDivider = 0;

            while (running) {
                try {
                    Runtime rt = Runtime.getRuntime();
                    heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
                    heapMaxMb = rt.maxMemory() / (1024L * 1024L);

                    if (sunOsBean != null) {
                        double proc = sunOsBean.getProcessCpuLoad();
                        if (proc >= 0.0) {
                            processCpuPct = (float) (proc * 100.0);
                        }
                        double sys = sunOsBean.getCpuLoad();
                        if (sys >= 0.0) {
                            systemCpuPct = (float) (sys * 100.0);
                        }
                    }

                    if (nvidiaSmiAvailable && (smiDivider++ % 2 == 0)) {
                        Process p = new ProcessBuilder(
                                "nvidia-smi",
                                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,clocks.current.graphics",
                                "--format=csv,noheader,nounits"
                        ).redirectErrorStream(true).start();

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
                                    if (memUsed >= 0) vramUsedMb = memUsed;
                                    if (memTotal > 0) vramTotalMb = memTotal;
                                    if (temp > 0) gpuTempC = temp;
                                    if (clock > 0) gpuClockMhz = clock;
                                }
                            } else {
                                nvidiaSmiAvailable = false;
                            }
                        }
                        p.waitFor();
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
        if (samplerThread != null) {
            samplerThread.interrupt();
        }
    }

    private static int parseRoundedInt(String raw, int fallback) {
        try {
            return Math.round(Float.parseFloat(raw.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
