package com.mycompany.mavenproject1.graphics;

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
public final class FramebufferSet implements AutoCloseable {
    private final int bloomLevels;
    private final int hdrFbo;
    private final int hdrColorTexture;
    private final int[] bloomFbo;
    private final int[] bloomTexture;
    private final int[] bloomWidth;
    private final int[] bloomHeight;

    public FramebufferSet(int width, int height, int bloomLevels) {
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
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public int hdrFbo() {
        return hdrFbo;
    }

    public int hdrColorTexture() {
        return hdrColorTexture;
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

    @Override
    public void close() {
        if (hdrFbo != 0) {
            glDeleteFramebuffers(hdrFbo);
        }
        if (hdrColorTexture != 0) {
            glDeleteTextures(hdrColorTexture);
        }
        for (int i = 0; i < bloomLevels; i++) {
            if (bloomFbo[i] != 0) {
                glDeleteFramebuffers(bloomFbo[i]);
            }
            if (bloomTexture[i] != 0) {
                glDeleteTextures(bloomTexture[i]);
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
