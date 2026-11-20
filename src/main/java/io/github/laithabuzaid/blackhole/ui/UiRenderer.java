package io.github.laithabuzaid.blackhole.ui;

import io.github.laithabuzaid.blackhole.graphics.ShaderProgram;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Single-batch OpenGL 3.3 SDF rounded-glass and crisp bitmap font renderer.
 *
 * <p>Covers the complete ASCII printable range (32..126) plus the degree symbol
 * ({@code °}) and renders {@link CharSequence} directly so callers can draw
 * dynamic HUD strings with zero heap allocations per frame.</p>
 */
public final class UiRenderer implements AutoCloseable {

    private static final int MAX_QUADS = 6144;
    private static final int FLOATS_PER_VERT = 14; // pos(2) + uv(2) + color(4) + rect(4) + params(2)

    private final ShaderProgram shader;
    private final int uResolutionLoc;
    private final int uFontTexLoc;
    private final int vao;
    private final int vbo;
    private final int fontTex;
    private final FloatBuffer vertexBuffer;
    private int quadCount = 0;

    public UiRenderer() {
        this.shader = ShaderProgram.fromResources("/shaders/ui/hud.vert", "/shaders/ui/hud.frag");
        this.uResolutionLoc = shader.uniformLocation("uResolution");
        this.uFontTexLoc = shader.uniformLocation("uFontTex");

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
        this.fontTex = createBitmapFontTexture();
        ShaderProgram.checkGlError("UiRenderer.init");
    }

    public void begin(int width, int height) {
        vertexBuffer.clear();
        quadCount = 0;
        shader.use();
        glUniform2f(uResolutionLoc, width, height);
        glUniform1i(uFontTexLoc, 0);
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

        putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
        putVert(x0 - nx, y0 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
        putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);

        putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
        putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
        putVert(x1 + nx, y1 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
        quadCount++;
    }

    public void drawText(CharSequence text, float x, float y, float scale,
                         float r, float g, float b, float a) {
        float cursorX = x;
        float glyphW = 8f * scale;
        float glyphH = 8f * scale;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            if (quadCount >= MAX_QUADS) {
                break;
            }
            char ch = text.charAt(i);
            int idx = (ch == '°') ? 95 : (ch >= 32 && ch <= 126 ? ch - 32 : 0);

            if (idx != 0) {
                float u0 = idx / 96.0f;
                float u1 = (idx + 1) / 96.0f;
                float x0 = cursorX;
                float y0 = y;
                float x1 = cursorX + glyphW;
                float y1 = y + glyphH;

                putVert(x0, y0, u0, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x0, y1, u0, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y1, u1, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);

                putVert(x0, y0, u0, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y1, u1, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                putVert(x1, y0, u1, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                quadCount++;
            }
            cursorX += glyphW;
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
        MemoryUtil.memFree(vertexBuffer);
        glDeleteTextures(fontTex);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.close();
    }

    /** Complete 8x8 bitmap font atlas covering all printable ASCII 32..126 + degree symbol (°). */
    private static int createBitmapFontTexture() {
        String[] g = new String[96];
        // Space (32)
        g[' ' - 32] = "......../......../......../......../......../......../......../........";

        // Punctuation & Symbols (33..47)
        g['!' - 32] = "...##.../..####../..####../...##.../...##.../......../...##.../........";
        g['"' - 32] = ".##..##./.##..##./.##..##./......../......../......../......../........";
        g['#' - 32] = ".##..##./.##..##./########/.##..##./########/.##..##./.##..##./........";
        g['$' - 32] = "...##.../.#######/##.##.../.######./...##.##/#######./...##.../........";
        g['%' - 32] = "##...##./##..##../....##../...##.../..##..../.##..##./##...##./........";
        g['&' - 32] = "..####../.##..##./..####../.###.##./##..###./##...##./.#####.#/........";
        g['\'' - 32] = "...##.../...##.../..##..../......../......../......../......../........";
        g['(' - 32] = "....##../...##.../..##..../..##..../..##..../...##.../....##../........";
        g[')' - 32] = "..##..../...##.../....##../....##../....##../...##.../..##..../........";
        g['*' - 32] = "......../..#..#../...##.../########/...##.../..#..#../......../........";
        g['+' - 32] = "......../...##.../..######/..######/...##.../......../......../........";
        g[',' - 32] = "......../......../......../......../......../...##.../..##..../........";
        g['-' - 32] = "......../......../......../.######./.######./......../......../........";
        g['.' - 32] = "......../......../......../......../......../..###.../..###.../........";
        g['/' - 32] = "......##/.....##./....##../...##.../..##..../.##...../##....../........";

        // Digits (48..57)
        g['0' - 32] = ".######./##...###/##..####/##.##.##/####..##/###...##/.######./........";
        g['1' - 32] = "...##.../..###.../.####.../...##.../...##.../...##.../.######./........";
        g['2' - 32] = ".######./##....##/.....##./..####../.##...../##....../########/........";
        g['3' - 32] = ".######./##....##/.....##./..#####./.....##./##....##/.######./........";
        g['4' - 32] = "....###./...####./..##.##./.##..##./########/.....##./.....##./........";
        g['5' - 32] = "########/##....../#######./.....##./.....##./##....##/.######./........";
        g['6' - 32] = "..#####./.##...../##....../#######./##....##/##....##/.######./........";
        g['7' - 32] = "########/##....##/....##../...##.../..##..../..##..../..##..../........";
        g['8' - 32] = ".######./##....##/##....##/.######./##....##/##....##/.######./........";
        g['9' - 32] = ".######./##....##/##....##/.#######/.....##./....##../.#####../........";

        // Symbols (58..64)
        g[':' - 32] = "......../..###.../..###.../......../..###.../..###.../......../........";
        g[';' - 32] = "......../..###.../..###.../......../..###.../...##.../..##..../........";
        g['<' - 32] = ".....##./...##.../.##...../##....../.##...../...##.../.....##./........";
        g['=' - 32] = "......../......../.######./......../.######./......../......../........";
        g['>' - 32] = ".##...../...##.../.....##./......##/.....##./...##.../.##...../........";
        g['?' - 32] = ".######./##....##/.....##./...###../...##.../......../...##.../........";
        g['@' - 32] = ".######./##....##/##.####./##.##.##/##.####./##....../.######./........";

        // Uppercase (65..90)
        g['A' - 32] = "..####../.##..##./##....##/########/##....##/##....##/##....##/........";
        g['B' - 32] = "#######./##....##/##....##/#######./##....##/##....##/#######./........";
        g['C' - 32] = ".######./##....##/##....../##....../##....../##....##/.######./........";
        g['D' - 32] = "######../##...##./##....##/##....##/##....##/##...##./######../........";
        g['E' - 32] = "########/##....../##....../######../##....../##....../########/........";
        g['F' - 32] = "########/##....../##....../######../##....../##....../##....../........";
        g['G' - 32] = ".######./##....##/##....../##..####/##....##/##....##/.######./........";
        g['H' - 32] = "##....##/##....##/##....##/########/##....##/##....##/##....##/........";
        g['I' - 32] = ".######./...##.../...##.../...##.../...##.../...##.../.######./........";
        g['J' - 32] = "..######/.....##./.....##./.....##./##...##./##...##./.#####../........";
        g['K' - 32] = "##...##./##..##../##.##.../####..../##.##.../##..##../##...##./........";
        g['L' - 32] = "##....../##....../##....../##....../##....../##....../########/........";
        g['M' - 32] = "##....##/###..###/########/##.##.##/##....##/##....##/##....##/........";
        g['N' - 32] = "##....##/###...##/####..##/##.##.##/##..####/##...###/##....##/........";
        g['O' - 32] = ".######./##....##/##....##/##....##/##....##/##....##/.######./........";
        g['P' - 32] = "#######./##....##/##....##/#######./##....../##....../##....../........";
        g['Q' - 32] = ".######./##....##/##....##/##....##/##.##.##/##..###./.#####.#/........";
        g['R' - 32] = "#######./##....##/##....##/#######./##..##../##...##./##....##/........";
        g['S' - 32] = ".######./##....##/##....../.######./.....##./##....##/.######./........";
        g['T' - 32] = "########/...##.../...##.../...##.../...##.../...##.../...##.../........";
        g['U' - 32] = "##....##/##....##/##....##/##....##/##....##/##....##/.######./........";
        g['V' - 32] = "##....##/##....##/##....##/##....##/.##..##./..####../...##.../........";
        g['W' - 32] = "##....##/##....##/##....##/##.##.##/########/###..###/##....##/........";
        g['X' - 32] = "##....##/.##..##./..####../...##.../..####../.##..##./##....##/........";
        g['Y' - 32] = "##....##/.##..##./..####../...##.../...##.../...##.../...##.../........";
        g['Z' - 32] = "########/.....##./....##../...##.../..##..../.##...../########/........";

        // Brackets & Misc (91..96)
        g['[' - 32] = "..####../..##..../..##..../..##..../..##..../..##..../..####../........";
        g['\\' - 32] = "##....../.##...../..##..../...##.../....##../.....##./......##/........";
        g[']' - 32] = "..####../....##../....##../....##../....##../....##../..####../........";
        g['^' - 32] = "...##.../..####../.##..##./......../......../......../......../........";
        g['_' - 32] = "......../......../......../......../......../......../########/........";
        g['`' - 32] = "..##..../...##.../....##../......../......../......../......../........";

        // Lowercase (97..122) — complete 26-letter set
        g['a' - 32] = "......../......../.######./.....##./.#######/##....##/.#######/........";
        g['b' - 32] = "##....../##....../#######./##....##/##....##/##....##/#######./........";
        g['c' - 32] = "......../......../.######./##....../##....../##....##/.######./........";
        g['d' - 32] = ".....##./.....##./.#######/##....##/##....##/##....##/.#######/........";
        g['e' - 32] = "......../......../.######./##....##/########/##....../.######./........";
        g['f' - 32] = "...####./..##..../.######./..##..../..##..../..##..../..##..../........";
        g['g' - 32] = "......../......../.#######/##....##/.#######/.....##./.######./........";
        g['h' - 32] = "##....../##....../#######./##....##/##....##/##....##/##....##/........";
        g['i' - 32] = "...##.../......../..###.../...##.../...##.../...##.../.######./........";
        g['j' - 32] = "....##../......../...###../....##../....##../##..##../.####.../........";
        g['k' - 32] = "##....../##....../##..##../##.##.../####..../##.##.../##..##../........";
        g['l' - 32] = "..###.../...##.../...##.../...##.../...##.../...##.../.######./........";
        g['m' - 32] = "......../......../###.###./##.##.##/##.##.##/##....##/##....##/........";
        g['n' - 32] = "......../......../#######./##....##/##....##/##....##/##....##/........";
        g['o' - 32] = "......../......../.######./##....##/##....##/##....##/.######./........";
        g['p' - 32] = "......../......../#######./##....##/#######./##....../##....../........";
        g['q' - 32] = "......../......../.#######/##....##/.#######/.....##./.....##./........";
        g['r' - 32] = "......../......../##.###../###..##./##....../##....../##....../........";
        g['s' - 32] = "......../......../.######./##....../.######./.....##./.######./........";
        g['t' - 32] = "..##..../..##..../.######./..##..../..##..../..##..##/..####../........";
        g['u' - 32] = "......../......../##....##/##....##/##....##/##...###/.#####.#/........";
        g['v' - 32] = "......../......../##....##/##....##/.##..##./..####../...##.../........";
        g['w' - 32] = "......../......../##....##/##.##.##/##.##.##/########/.##..##./........";
        g['x' - 32] = "......../......../##....##/.##..##./..####../.##..##./##....##/........";
        g['y' - 32] = "......../......../##....##/##....##/.#######/.....##./.######./........";
        g['z' - 32] = "......../......../########/....##../..##..../.##...../########/........";

        // Braces & Tilde (123..126) + Degree Symbol (95)
        g['{' - 32] = "....###./...##.../...##.../..##..../...##.../...##.../....###./........";
        g['|' - 32] = "...##.../...##.../...##.../...##.../...##.../...##.../...##.../........";
        g['}' - 32] = ".###..../...##.../...##.../....##../...##.../...##.../.###..../........";
        g['~' - 32] = "......../.###..##/##..###./......../......../......../......../........";
        g[95]       = "..###.../##...##./##...##./..###.../......../......../......../........";

        int atlasW = 96 * 8;
        int atlasH = 8;
        ByteBuffer pixels = MemoryUtil.memAlloc(atlasW * atlasH);

        for (int idx = 0; idx < 96; idx++) {
            String pattern = g[idx];
            String[] rows = pattern.split("/");
            for (int y = 0; y < 8; y++) {
                String row = y < rows.length ? rows[y] : "........";
                for (int x = 0; x < 8; x++) {
                    boolean on = x < row.length() && row.charAt(x) == '#';
                    pixels.put(y * atlasW + idx * 8 + x, (byte) (on ? 255 : 0));
                }
            }
        }

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, atlasW, atlasH, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        MemoryUtil.memFree(pixels);
        return tex;
    }
}
