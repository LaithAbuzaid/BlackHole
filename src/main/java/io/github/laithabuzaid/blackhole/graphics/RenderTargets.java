package io.github.laithabuzaid.blackhole.graphics;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

/**
 * Owns the resizable HDR scene target and bloom framebuffer pyramid.
 *
 * <p>The allocation and deletion order intentionally mirrors the original
 * application implementation.</p>
 */
public final class RenderTargets implements AutoCloseable {
    private final int bloomLevels;
    private final int hdrFbo;
    private final int hdrColorTexture;
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

    public RenderTargets(int width, int height, int bloomLevels) {
        this.bloomLevels = bloomLevels;
        this.bloomFbo = new int[bloomLevels];
        this.bloomTexture = new int[bloomLevels];
        this.bloomWidth = new int[bloomLevels];
        this.bloomHeight = new int[bloomLevels];

        hdrFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, hdrFbo);
        hdrColorTexture = createHdrTexture(width, height);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                hdrColorTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("HDR Framebuffer incomplete");
        }

        for (int i = 0; i < 2; i++) {
            taaFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, taaFbo[i]);
            taaTexture[i] = createHdrTexture(width, height);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    taaTexture[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("TAA history framebuffer " + i + " incomplete");
            }
        }

        int bloomWidthValue = width;
        int bloomHeightValue = height;
        for (int i = 0; i < bloomLevels; i++) {
            bloomWidthValue = Math.max(1, bloomWidthValue / 2);
            bloomHeightValue = Math.max(1, bloomHeightValue / 2);
            bloomWidth[i] = bloomWidthValue;
            bloomHeight[i] = bloomHeightValue;

            bloomFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
            bloomTexture[i] = createHdrTexture(bloomWidthValue, bloomHeightValue);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    bloomTexture[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Bloom mip framebuffer " + i + " incomplete");
            }
        }

        int anaLevel = Math.min(2, bloomLevels - 1);
        this.anamorphicWidth = bloomWidth[anaLevel];
        this.anamorphicHeight = bloomHeight[anaLevel];
        for (int i = 0; i < 2; i++) {
            anamorphicFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, anamorphicFbo[i]);
            anamorphicTexture[i] = createHdrTexture(anamorphicWidth, anamorphicHeight);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    anamorphicTexture[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Anamorphic framebuffer " + i + " incomplete");
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public int hdrFbo() {
        return hdrFbo;
    }

    public int hdrColorTexture() {
        return hdrColorTexture;
    }

    public int taaFbo(int idx) {
        return taaFbo[idx];
    }

    public int taaTexture(int idx) {
        return taaTexture[idx];
    }

    public int bloomFbo(int level) {
        return bloomFbo[level];
    }

    public int bloomTexture(int level) {
        return bloomTexture[level];
    }

    public int bloomWidth(int level) {
        return bloomWidth[level];
    }

    public int bloomHeight(int level) {
        return bloomHeight[level];
    }

    public int anamorphicFbo(int idx) {
        return anamorphicFbo[idx];
    }

    public int anamorphicTexture(int idx) {
        return anamorphicTexture[idx];
    }

    public int anamorphicWidth() {
        return anamorphicWidth;
    }

    public int anamorphicHeight() {
        return anamorphicHeight;
    }

    @Override
    public void close() {
        if (hdrFbo != 0) {
            glDeleteFramebuffers(hdrFbo);
        }
        if (hdrColorTexture != 0) {
            glDeleteTextures(hdrColorTexture);
        }
        for (int i = 0; i < 2; i++) {
            if (taaFbo[i] != 0) {
                glDeleteFramebuffers(taaFbo[i]);
            }
            if (taaTexture[i] != 0) {
                glDeleteTextures(taaTexture[i]);
            }
        }
        for (int i = 0; i < bloomLevels; i++) {
            if (bloomFbo[i] != 0) {
                glDeleteFramebuffers(bloomFbo[i]);
            }
            if (bloomTexture[i] != 0) {
                glDeleteTextures(bloomTexture[i]);
            }
        }
        for (int i = 0; i < 2; i++) {
            if (anamorphicFbo[i] != 0) {
                glDeleteFramebuffers(anamorphicFbo[i]);
            }
            if (anamorphicTexture[i] != 0) {
                glDeleteTextures(anamorphicTexture[i]);
            }
        }
    }

    private static int createHdrTexture(int width, int height) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }
}
