package io.github.laithabuzaid.blackhole.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.glDrawBuffers;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

/**
 * Owns the resizable HDR scene target ({@code GL_RGBA16F}), Binary Merger MRT velocity target
 * ({@code GL_RG32F} + {@code GL_NEAREST}), TAA history ping-pong targets ({@code GL_RGBA16F}
 * preserving {@code hitDist} for disocclusion detection), and bandwidth-optimized
 * {@code GL_R11F_G11F_B10F} bloom &amp; anamorphic targets with immutable storage and
 * leak-free/idempotent lifecycle management.
 */
public final class RenderTargets implements AutoCloseable {
    private static final int[] MRT_COLOR_AND_VEL = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};

    private final int bloomLevels;
    private int hdrFbo;
    private int binaryHdrFbo;
    private int hdrColorTexture;
    private int velocityTexture;
    private final int[] taaFbo = new int[2];
    private final int[] taaTexture = new int[2];
    private final int[] bloomFbo;
    private final int[] bloomTexture;
    private final int[] bloomWidth;
    private final int[] bloomHeight;
    private final int[] anamorphicFbo = new int[2];
    private final int[] anamorphicTexture = new int[2];
    private final int anamorphicWidth;
    private final int anamorphicHeight;
    private boolean closed = false;

    public RenderTargets(int width, int height, int bloomLevels) {
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        this.bloomLevels = bloomLevels;
        this.bloomFbo = new int[bloomLevels];
        this.bloomTexture = new int[bloomLevels];
        this.bloomWidth = new int[bloomLevels];
        this.bloomHeight = new int[bloomLevels];

        int anaW = 1;
        int anaH = 1;
        boolean ok = false;
        try {
            hdrFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, hdrFbo);
            hdrColorTexture = createImmutableTexture(safeW, safeH, GL_RGBA16F, GL_LINEAR);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hdrColorTexture, 0);
            assertFboComplete("HDR Framebuffer");
            clearBoundFbo(safeW, safeH);

            // Binary MRT FBO: shares hdrColorTexture (LOC 0) + exact RG32F NEAREST velocity (LOC 1)
            binaryHdrFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, binaryHdrFbo);
            velocityTexture = createImmutableTexture(safeW, safeH, GL_RG32F, GL_NEAREST);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hdrColorTexture, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, velocityTexture, 0);
            glDrawBuffers(MRT_COLOR_AND_VEL);
            assertFboComplete("Binary MRT Framebuffer");
            clearBoundFbo(safeW, safeH);

            // TAA ping-pong targets keep RGBA16F so alpha stores hitDist for disocclusion detection
            for (int i = 0; i < 2; i++) {
                taaFbo[i] = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, taaFbo[i]);
                taaTexture[i] = createImmutableTexture(safeW, safeH, GL_RGBA16F, GL_LINEAR);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, taaTexture[i], 0);
                assertFboComplete("TAA history framebuffer " + i);
                clearBoundFbo(safeW, safeH);
            }

            // RGB-only bloom pyramid uses packed 32-bit GL_R11F_G11F_B10F (halves VRAM & bandwidth)
            int bw = safeW;
            int bh = safeH;
            for (int i = 0; i < bloomLevels; i++) {
                bw = Math.max(1, bw / 2);
                bh = Math.max(1, bh / 2);
                bloomWidth[i] = bw;
                bloomHeight[i] = bh;

                bloomFbo[i] = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
                bloomTexture[i] = createImmutableTexture(bw, bh, GL_R11F_G11F_B10F, GL_LINEAR);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTexture[i], 0);
                assertFboComplete("Bloom mip framebuffer " + i);
                clearBoundFbo(bw, bh);
            }

            int anaLevel = Math.min(2, bloomLevels - 1);
            anaW = bloomWidth[anaLevel];
            anaH = bloomHeight[anaLevel];
            for (int i = 0; i < 2; i++) {
                anamorphicFbo[i] = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, anamorphicFbo[i]);
                anamorphicTexture[i] = createImmutableTexture(anaW, anaH, GL_R11F_G11F_B10F, GL_LINEAR);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, anamorphicTexture[i], 0);
                assertFboComplete("Anamorphic framebuffer " + i);
                clearBoundFbo(anaW, anaH);
            }

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            this.anamorphicWidth = anaW;
            this.anamorphicHeight = anaH;
            ok = true;
        } finally {
            if (!ok) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                close();
            }
        }
    }

    public int hdrFbo() { return hdrFbo; }
    public int binaryHdrFbo() { return binaryHdrFbo; }
    public int hdrColorTexture() { return hdrColorTexture; }
    public int velocityTexture() { return velocityTexture; }
    public int taaFbo(int idx) { return taaFbo[idx]; }
    public int taaTexture(int idx) { return taaTexture[idx]; }
    public int bloomFbo(int level) { return bloomFbo[level]; }
    public int bloomTexture(int level) { return bloomTexture[level]; }
    public int bloomWidth(int level) { return bloomWidth[level]; }
    public int bloomHeight(int level) { return bloomHeight[level]; }
    public int anamorphicFbo(int idx) { return anamorphicFbo[idx]; }
    public int anamorphicTexture(int idx) { return anamorphicTexture[idx]; }
    public int anamorphicWidth() { return anamorphicWidth; }
    public int anamorphicHeight() { return anamorphicHeight; }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (hdrFbo != 0) glDeleteFramebuffers(hdrFbo);
        if (binaryHdrFbo != 0) glDeleteFramebuffers(binaryHdrFbo);
        if (hdrColorTexture != 0) glDeleteTextures(hdrColorTexture);
        if (velocityTexture != 0) glDeleteTextures(velocityTexture);
        for (int i = 0; i < 2; i++) {
            if (taaFbo[i] != 0) glDeleteFramebuffers(taaFbo[i]);
            if (taaTexture[i] != 0) glDeleteTextures(taaTexture[i]);
        }
        for (int i = 0; i < bloomLevels; i++) {
            if (bloomFbo[i] != 0) glDeleteFramebuffers(bloomFbo[i]);
            if (bloomTexture[i] != 0) glDeleteTextures(bloomTexture[i]);
        }
        for (int i = 0; i < 2; i++) {
            if (anamorphicFbo[i] != 0) glDeleteFramebuffers(anamorphicFbo[i]);
            if (anamorphicTexture[i] != 0) glDeleteTextures(anamorphicTexture[i]);
        }
    }

    private static int createImmutableTexture(int width, int height, int internalFormat, int filter) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static void clearBoundFbo(int w, int h) {
        glViewport(0, 0, w, h);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void assertFboComplete(String name) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException(name + " incomplete (status=0x" + Integer.toHexString(status) + ")");
        }
    }
}
