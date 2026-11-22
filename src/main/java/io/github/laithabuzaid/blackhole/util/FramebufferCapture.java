package io.github.laithabuzaid.blackhole.util;

import io.github.laithabuzaid.blackhole.core.SimulationState;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Manages OpenGL framebuffer RGB readback and bounded single-thread asynchronous PNG snapshots
 * with zero {@code java.desktop} dependency and safe shutdown draining.
 */
public final class FramebufferCapture implements AutoCloseable {

    private static final DateTimeFormatter SNAPSHOT_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlackHole-PNG-Encoder");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean snapshotInFlight = new AtomicBoolean(false);

    public void captureLiveSnapshotAsync(int width, int height, SimulationState state) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!snapshotInFlight.compareAndSet(false, true)) {
            state.showToast("SNAPSHOT IN PROGRESS...");
            return;
        }
        byte[] rgb = readFramebufferRgb(0, width, height);
        String filename = "blackhole_" + LocalDateTime.now().format(SNAPSHOT_TS) + ".png";
        Path captureDir = resolveCaptureDirectory();
        Path outPath = captureDir.resolve(filename);
        String toastDisplay = captureDir.getFileName() + "/" + filename;

        ioExecutor.submit(() -> {
            try {
                PngWriter.writeRgbPng(outPath, width, height, rgb);
                state.showSavedToast(toastDisplay);
            } catch (IOException e) {
                state.showToast("SNAPSHOT FAILED: " + e.getMessage());
            } finally {
                snapshotInFlight.set(false);
            }
        });
    }

    public static void saveCaptureFbo(int fbo, int width, int height, Path outPath) throws IOException {
        glFinish();
        byte[] rgb = readFramebufferRgb(fbo, width, height);
        PngWriter.writeRgbPng(outPath, width, height, rgb);
        long bytes = Files.size(outPath);
        System.out.println("[Showcase] Wrote " + outPath + " (" + width + "x" + height + ", " + (bytes / 1024) + " KB)");
    }

    public static byte[] readFramebufferRgb(int fbo, int width, int height) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        if (fbo == 0) {
            glReadBuffer(GL_BACK);
        }
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        int byteCount = width * height * 3;
        ByteBuffer nativeBuf = MemoryUtil.memAlloc(byteCount);
        try {
            glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, nativeBuf);
            byte[] rgb = new byte[byteCount];
            nativeBuf.get(rgb);
            return rgb;
        } finally {
            MemoryUtil.memFree(nativeBuf);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
    }

    public static Path resolveCaptureDirectory() {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path pictures = Path.of(home, "Pictures");
            if (Files.isDirectory(pictures) && Files.isWritable(pictures)) {
                return pictures.resolve("BlackHole");
            }
        }
        return Path.of("captures");
    }

    @Override
    public void close() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
