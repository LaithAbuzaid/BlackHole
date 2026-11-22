package io.github.laithabuzaid.blackhole.util;

import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.InflaterInputStream;

import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;

/** Loads the pre-compressed 64x64 RGBA taskbar icon with zero {@code java.desktop} dependency. */
public final class WindowIconLoader {

    private static final int MAGIC_BHIC = 0x42484943;

    private WindowIconLoader() {
    }

    public static void applyWindowIcon(long winHandle) {
        try (InputStream raw = WindowIconLoader.class.getResourceAsStream("/icons/blackhole_64.bin")) {
            if (raw == null) {
                return;
            }
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(raw))) {
                if (dis.readInt() != MAGIC_BHIC) {
                    return;
                }
                int w = dis.readInt();
                int h = dis.readInt();
                int expected = w * h * 4;
                byte[] rgba = new byte[expected];
                try (InflaterInputStream iis = new InflaterInputStream(dis)) {
                    int off = 0;
                    while (off < expected) {
                        int n = iis.read(rgba, off, expected - off);
                        if (n < 0) return;
                        off += n;
                    }
                }
                ByteBuffer buf = MemoryUtil.memAlloc(expected);
                GLFWImage.Buffer images = GLFWImage.malloc(1);
                try {
                    buf.put(rgba).flip();
                    images.position(0).width(w).height(h).pixels(buf);
                    glfwSetWindowIcon(winHandle, images);
                } finally {
                    images.free();
                    MemoryUtil.memFree(buf);
                }
            }
        } catch (Throwable ignored) {
            // Wayland compositors ignore glfwSetWindowIcon; safely no-op
        }
    }
}
