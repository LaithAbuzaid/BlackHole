package io.github.laithabuzaid.blackhole.ui;

import io.github.laithabuzaid.blackhole.graphics.ShaderProgram;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.zip.InflaterInputStream;

import static org.lwjgl.opengl.GL33.*;

/**
 * Single-batch OpenGL 3.3 SDF rounded-glass and resolution-independent 3-channel
 * MTSDF (Multi-Channel + True Signed Distance Field) typography renderer.
 *
 * <p>Loads the pre-baked JetBrains Mono Medium MTSDF atlas covering printable ASCII
 * (32..126) plus degree ({@code °}) and bullet ({@code •}) with zero external runtime
 * dependencies and zero heap allocations per frame.</p>
 */
public final class UiRenderer implements AutoCloseable {

    private static final int MAX_QUADS = 32768;
    private static final int FLOATS_PER_VERT = 14; // pos(2) + uv(2) + color(4) + rect(4) + params(2)
    private static final int MTSDF_MAGIC = 0x4D545344; // 'MTSD'
    private static final String MTSDF_RESOURCE = "/fonts/jetbrains_mono_mtsdf.bin";

    private final ShaderProgram shader;
    private final int uResolutionLoc;
    private final int uFontTexLoc;
    private final int uFontUnitRangeLoc;
    private final int vao;
    private final int vbo;
    private final int fontTex;
    private final FloatBuffer vertexBuffer;
    private int quadCount = 0;

    // MTSDF Atlas Metrics loaded from binary header
    private final int fontAtlasW;
    private final int fontAtlasH;
    private final int fontCols;
    private final int fontCellW;
    private final int fontCellH;
    private final float fontUnitRangeX;
    private final float fontUnitRangeY;
    private final float fontCapTopNorm;
    private final float fontCapHeightNorm;
    private final float fontAdvNorm;

    public UiRenderer() {
        this.shader = ShaderProgram.fromResources("/shaders/ui/hud.vert", "/shaders/ui/hud.frag");
        this.uResolutionLoc = shader.uniformLocation("uResolution");
        this.uFontTexLoc = shader.uniformLocation("uFontTex");
        this.uFontUnitRangeLoc = shader.uniformLocation("uFontUnitRange");

        this.vao = glGenVertexArrays();
        this.vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * 6 * FLOATS_PER_VERT * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERT * Float.BYTES;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 12L * Float.BYTES);

        glBindVertexArray(0);

        this.vertexBuffer = MemoryUtil.memAllocFloat(MAX_QUADS * 6 * FLOATS_PER_VERT);

        MtsdfAtlas atlas = loadMtsdfFontAtlas();
        this.fontTex = atlas.textureId;
        this.fontAtlasW = atlas.atlasW;
        this.fontAtlasH = atlas.atlasH;
        this.fontCols = atlas.cols;
        this.fontCellW = atlas.cellW;
        this.fontCellH = atlas.cellH;
        this.fontUnitRangeX = (2.0f * atlas.pxRange) / atlas.atlasW;
        this.fontUnitRangeY = (2.0f * atlas.pxRange) / atlas.atlasH;
        this.fontCapTopNorm = atlas.capTopNorm;
        this.fontCapHeightNorm = atlas.capHeightNorm;
        this.fontAdvNorm = atlas.advNorm;

        ShaderProgram.checkGlError("UiRenderer.init");
    }

    public void begin(int width, int height) {
        vertexBuffer.clear();
        quadCount = 0;
        shader.use();
        glUniform2f(uResolutionLoc, width, height);
        glUniform1i(uFontTexLoc, 0);
        glUniform2f(uFontUnitRangeLoc, fontUnitRangeX, fontUnitRangeY);
    }

    public void end() {
        if (quadCount == 0) {
            return;
        }
        vertexBuffer.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fontTex);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
        glDrawArrays(GL_TRIANGLES, 0, quadCount * 6);

        glBindVertexArray(0);
        glDisable(GL_BLEND);
    }

    public void drawGlassPanel(float x, float y, float w, float h, float radius,
                               float bgR, float bgG, float bgB, float bgA,
                               float bdR, float bdG, float bdB, float bdA) {
        pushRoundedQuad(x, y, w, h, radius, 0f, bgR, bgG, bgB, bgA);
        pushRoundedQuad(x, y, w, h, radius, 1f, bdR, bdG, bdB, bdA);
    }

    /** Frosted-glass panel with subtle top-to-bottom luminance gradient and top inner rim. */
    public void drawGradientGlassPanel(float x, float y, float w, float h, float radius,
                                       float bgR, float bgG, float bgB, float bgA,
                                       float bdR, float bdG, float bdB, float bdA) {
        pushRoundedQuad(x, y, w, h, radius, 3f, bgR, bgG, bgB, bgA);
        pushRoundedQuad(x, y, w, h, radius, 1f, bdR, bdG, bdB, bdA);
    }

    /** Thin rounded accent stripe for observatory panel headers and active tab indicators. */
    public void drawAccentBar(float x, float y, float w, float h,
                              float r, float g, float b, float a) {
        pushRoundedQuad(x, y, w, h, h * 0.5f, 0f, r, g, b, a);
    }

    /** Crisp circular SDF status LED dot for active toggle pills and mode badges. */
    public void drawIndicatorDot(float cx, float cy, float radiusPx,
                                 float r, float g, float b, float a) {
        float d = radiusPx * 2.0f;
        pushRoundedQuad(cx - radiusPx, cy - radiusPx, d, d, radiusPx, 0f, r, g, b, a);
    }

    public void drawRect(float x, float y, float w, float h, float radius,
                         float r, float g, float b, float a) {
        pushRoundedQuad(x, y, w, h, radius, 0f, r, g, b, a);
    }

    public void drawProgressBar(float x, float y, float w, float h, float pct,
                                float r, float g, float b) {
        float clamped = Math.max(0f, Math.min(1f, pct));
        pushRoundedQuad(x, y, w, h, h * 0.5f, 0f, 1f, 1f, 1f, 0.12f);
        if (clamped > 0.005f) {
            pushRoundedQuad(x, y, Math.max(h, w * clamped), h, h * 0.5f, 0f, r, g, b, 0.92f);
        }
    }

    public void drawLine(float x0, float y0, float x1, float y1, float thickness,
                         float r, float g, float b, float a) {
        if (quadCount >= MAX_QUADS) {
            return;
        }
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) {
            return;
        }
        float nx = (-dy / len) * (thickness * 0.5f);
        float ny = (dx / len) * (thickness * 0.5f);

        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);
        putVert(x0 - nx, y0 - ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);
        putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);

        putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);
        putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);
        putVert(x1 + nx, y1 + ny, 0f, 0f, r, g, b, a, mx, my, 1e5f, 1e5f, 0f, 0f);
        quadCount++;
    }

    /** Zero-allocation dashed 2D segment for distinguishing retrograde/subordinate/reference curves. */
    public void drawDashedLine(float x0, float y0, float x1, float y1, float thickness,
                               float dashPx, float gapPx, float r, float g, float b, float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        float period = Math.max(2f, dashPx + gapPx);
        float s = 0f;
        while (s < len) {
            float e = Math.min(len, s + dashPx);
            drawLine(x0 + ux * s, y0 + uy * s, x0 + ux * e, y0 + uy * e, thickness, r, g, b, a);
            s += period;
        }
    }

    /** Zero-allocation 2D vector arrow with crisp triangular head wings. */
    public void drawArrow(float x0, float y0, float x1, float y1, float thickness, float headPx,
                          float r, float g, float b, float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.5f) {
            return;
        }
        drawLine(x0, y0, x1, y1, thickness, r, g, b, a);
        float ux = dx / len;
        float uy = dy / len;
        float px = -uy;
        float py = ux;
        float h = Math.min(headPx, len * 0.45f);
        float w = h * 0.48f;
        drawLine(x1, y1, x1 - ux * h + px * w, y1 - uy * h + py * w, thickness, r, g, b, a);
        drawLine(x1, y1, x1 - ux * h - px * w, y1 - uy * h - py * w, thickness, r, g, b, a);
    }

    /** Zero-allocation 2D circle outline built from line segments. */
    public void drawCircleOutline(float cx, float cy, float radius, int segs, float thickness,
                                  float r, float g, float b, float a) {
        if (radius <= 0.5f || segs < 6) {
            return;
        }
        float step = (float) ((2.0 * Math.PI) / segs);
        float prevX = cx + radius;
        float prevY = cy;
        for (int i = 1; i <= segs; i++) {
            float ang = i * step;
            float nx = cx + (float) Math.cos(ang) * radius;
            float ny = cy + (float) Math.sin(ang) * radius;
            drawLine(prevX, prevY, nx, ny, thickness, r, g, b, a);
            prevX = nx;
            prevY = ny;
        }
    }

    /** Compact provenance/scale pill badge for [E] EXACT, [A] APPROX, [F] NR FIT, [V] VISUAL tags. */
    public void drawBadge(float x, float y, String text, float scale, float r, float g, float b) {
        float tw = text.length() * 8f * scale;
        float bw = tw + 10f;
        float bh = 14f * scale + 2f;
        drawGlassPanel(x, y, bw, bh, 4f, r * 0.16f, g * 0.16f, b * 0.16f, 0.88f, r, g, b, 0.72f);
        drawText(text, x + 5f, y + 3.5f * scale, scale, r, g, b, 0.98f);
    }

    public void drawText(CharSequence text, float x, float y, float scale,
                         float r, float g, float b, float a) {
        float advance = 8.0f * scale;
        // Render true JetBrains Mono aspect ratio with clean aerospace HUD tracking inside 8*scale column
        float fontAdvance = 7.20f * scale;
        float cellScreenW = fontAdvance / fontAdvNorm;
        float cellScreenH = cellScreenW * ((float) fontCellH / fontCellW);

        // Align glyph cap-height center to y + 3.6*scale for exact button/slider/header centering
        float capCenterY = y + 3.6f * scale;
        float y0 = capCenterY - (fontCapTopNorm + fontCapHeightNorm * 0.5f) * cellScreenH;
        float y1 = y0 + cellScreenH;
        float xPadOffset = (advance - cellScreenW) * 0.5f;

        float invAtlasW = 1.0f / fontAtlasW;
        float invAtlasH = 1.0f / fontAtlasH;
        float cursorX = x;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            if (quadCount >= MAX_QUADS) {
                break;
            }
            char ch = text.charAt(i);
            int idx;
            if (ch == '°') {
                idx = 95;
            } else if (ch == '\u2022') {
                idx = 96;
            } else {
                idx = (ch >= 32 && ch <= 126) ? ch - 32 : 0;
            }

            if (idx != 0) {
                int col = idx % fontCols;
                int row = idx / fontCols;
                float u0 = (col * fontCellW + 0.5f) * invAtlasW;
                float u1 = ((col + 1) * fontCellW - 0.5f) * invAtlasW;
                float v0 = (row * fontCellH + 0.5f) * invAtlasH;
                float v1 = ((row + 1) * fontCellH - 0.5f) * invAtlasH;

                float x0 = cursorX + xPadOffset;
                float x1 = x0 + cellScreenW;

                putVert(x0, y0, u0, v0, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x0, y1, u0, v1, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y1, u1, v1, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);

                putVert(x0, y0, u0, v0, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y1, u1, v1, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y0, u1, v0, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                quadCount++;
            }
            cursorX += advance;
        }
    }

    private void pushRoundedQuad(float x, float y, float w, float h, float radius, float mode,
                                 float r, float g, float b, float a) {
        if (quadCount >= MAX_QUADS) {
            return;
        }
        float pad = 1.5f;
        float x0 = x - pad;
        float y0 = y - pad;
        float x1 = x + w + pad;
        float y1 = y + h + pad;
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        float hw = w * 0.5f;
        float hh = h * 0.5f;

        putVert(x0, y0, 0f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
        putVert(x0, y1, 0f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);
        putVert(x1, y1, 1f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);

        putVert(x0, y0, 0f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
        putVert(x1, y1, 1f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);
        putVert(x1, y0, 1f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
        quadCount++;
    }

    private boolean closed = false;

    private void putVert(float px, float py, float u, float v,
                         float r, float g, float b, float a,
                         float cx, float cy, float hw, float hh,
                         float radius, float mode) {
        vertexBuffer.put(px).put(py)
                .put(u).put(v)
                .put(r).put(g).put(b).put(a)
                .put(cx).put(cy).put(hw).put(hh)
                .put(radius).put(mode);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        MemoryUtil.memFree(vertexBuffer);
        glDeleteTextures(fontTex);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.close();
    }

    private record MtsdfAtlas(int textureId, int atlasW, int atlasH, int cols, int cellW, int cellH,
                              float pxRange, float capTopNorm, float capHeightNorm, float advNorm) {
    }

    private static MtsdfAtlas loadMtsdfFontAtlas() {
        InputStream raw = UiRenderer.class.getResourceAsStream(MTSDF_RESOURCE);
        if (raw == null) {
            throw new IllegalStateException("Missing MTSDF font atlas resource: " + MTSDF_RESOURCE);
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(raw))) {
            int magic = dis.readInt();
            if (magic != MTSDF_MAGIC) {
                throw new IllegalStateException("Invalid MTSDF font atlas magic: 0x" + Integer.toHexString(magic));
            }
            int atlasW = dis.readUnsignedShort();
            int atlasH = dis.readUnsignedShort();
            int cols = dis.readUnsignedShort();
            dis.readUnsignedShort(); // rows
            int cellW = dis.readUnsignedShort();
            int cellH = dis.readUnsignedShort();
            float pxRange = dis.readFloat();
            float capTopNorm = dis.readFloat();
            float capHeightNorm = dis.readFloat();
            float advNorm = dis.readFloat();

            int expectedBytes = atlasW * atlasH * 4;
            byte[] rgba = new byte[expectedBytes];
            try (InflaterInputStream iis = new InflaterInputStream(dis)) {
                int offset = 0;
                while (offset < expectedBytes) {
                    int n = iis.read(rgba, offset, expectedBytes - offset);
                    if (n < 0) {
                        throw new IOException("Truncated MTSDF atlas payload (" + offset + "/" + expectedBytes + ")");
                    }
                    offset += n;
                }
            }

            ByteBuffer pixels = MemoryUtil.memAlloc(expectedBytes);
            try {
                pixels.put(rgba).flip();

                int tex = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, tex);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, atlasW, atlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glBindTexture(GL_TEXTURE_2D, 0);

                return new MtsdfAtlas(tex, atlasW, atlasH, cols, cellW, cellH,
                        pxRange, capTopNorm, capHeightNorm, advNorm);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MTSDF font atlas: " + MTSDF_RESOURCE, e);
        }
    }
}
