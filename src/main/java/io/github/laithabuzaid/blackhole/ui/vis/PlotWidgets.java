package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.ui.UiRenderer;

/**
 * Reusable zero-allocation 2D scientific plotting primitives, colormaps, provenance badges,
 * and ASCII numeric formatters for the BlackHole V3 diagnostic panels.
 */
public final class PlotWidgets {

    public static final int CMAP_TURBO = 0;
    public static final int CMAP_DOPPLER = 1;

    private PlotWidgets() {
    }

    public static void drawPanelHeader(UiRenderer ui, float x, float y, float w,
                                       String title, String tag, float accR, float accG, float accB) {
        ui.drawText(title, x + 8f, y + 6f, 0.86f, accR, accG, accB, 0.98f);
        if (tag != null && !tag.isEmpty()) {
            float tw = tag.length() * 8f * 0.72f + 8f;
            ui.drawGlassPanel(x + w - tw - 6f, y + 4f, tw, 13f, 3f,
                    accR * 0.14f, accG * 0.14f, accB * 0.14f, 0.90f,
                    accR, accG, accB, 0.62f);
            ui.drawText(tag, x + w - tw - 2f, y + 6.5f, 0.72f, accR, accG, accB, 0.96f);
        }
    }

    public static void drawTimeSeriesFloat(UiRenderer ui, float x, float y, float w, float h,
                                           float[] ring, int startIdx, int count, int cap,
                                           float minVal, float maxVal, float thickness, boolean dashed,
                                           float r, float g, float b, float a) {
        if (count < 2) {
            return;
        }
        float span = Math.max(1e-12f, maxVal - minVal);
        float stepX = w / Math.max(1, count - 1);
        for (int i = 0; i < count - 1; i++) {
            if (dashed && (i & 1) == 1) {
                continue;
            }
            int i0 = (startIdx + i) % cap;
            int i1 = (startIdx + i + 1) % cap;
            float n0 = Math.max(0f, Math.min(1f, (ring[i0] - minVal) / span));
            float n1 = Math.max(0f, Math.min(1f, (ring[i1] - minVal) / span));
            float x0 = x + i * stepX;
            float x1 = x + (i + 1) * stepX;
            float y0 = y + h - n0 * h;
            float y1 = y + h - n1 * h;
            ui.drawLine(x0, y0, x1, y1, thickness, r, g, b, a);
        }
    }

    public static void drawTimeSeriesDouble(UiRenderer ui, float x, float y, float w, float h,
                                            double[] ring, int startIdx, int count, int cap,
                                            double minVal, double maxVal, float thickness,
                                            float r, float g, float b, float a) {
        if (count < 2) {
            return;
        }
        double span = Math.max(1e-30, maxVal - minVal);
        float stepX = w / Math.max(1, count - 1);
        for (int i = 0; i < count - 1; i++) {
            int i0 = (startIdx + i) % cap;
            int i1 = (startIdx + i + 1) % cap;
            float n0 = (float) Math.max(0.0, Math.min(1.0, (ring[i0] - minVal) / span));
            float n1 = (float) Math.max(0.0, Math.min(1.0, (ring[i1] - minVal) / span));
            float x0 = x + i * stepX;
            float x1 = x + (i + 1) * stepX;
            float y0 = y + h - n0 * h;
            float y1 = y + h - n1 * h;
            ui.drawLine(x0, y0, x1, y1, thickness, r, g, b, a);
        }
    }

    public static void drawHorizontalBar(UiRenderer ui, float x, float y, float w, float h,
                                         float frac, String label, CharSequence valText,
                                         float r, float g, float b) {
        ui.drawText(label, x, y, 0.78f, 0.84f, 0.90f, 0.96f, 0.94f);
        float barX = x + 50f;
        float barW = Math.max(30f, w - 102f);
        ui.drawProgressBar(barX, y + 1.5f, barW, h, frac, r, g, b);
        ui.drawText(valText, barX + barW + 6f, y, 0.78f, r, g, b, 0.96f);
    }

    public static void drawColorbar(UiRenderer ui, float x, float y, float w, float h,
                                    String title, String minLbl, String midLbl, String maxLbl,
                                    int cmapType) {
        ui.drawGlassPanel(x, y, w, h, 8f, 0.03f, 0.05f, 0.09f, 0.88f, 0.35f, 0.78f, 0.98f, 0.56f);
        ui.drawText(title, x + 10f, y + 8f, 0.84f, 0.90f, 0.95f, 1.00f, 0.96f);

        int segs = 32;
        float barX = x + 10f;
        float barY = y + 23f;
        float barW = w - 20f;
        float segW = barW / segs;
        for (int i = 0; i < segs; i++) {
            float t = i / (float) (segs - 1);
            float r, g, b;
            if (cmapType == CMAP_DOPPLER) {
                if (t < 0.5f) {
                    float u = t * 2f;
                    r = 0.95f + (0.92f - 0.95f) * u; g = 0.22f + (0.94f - 0.22f) * u; b = 0.14f + (0.98f - 0.14f) * u;
                } else {
                    float u = (t - 0.5f) * 2f;
                    r = 0.92f + (0.16f - 0.92f) * u; g = 0.94f + (0.72f - 0.94f) * u; b = 0.98f + (1.00f - 0.98f) * u;
                }
            } else {
                if (t < 0.25f) {
                    float u = t * 4f;
                    r = 0.11f + (0.09f - 0.11f) * u; g = 0.08f + (0.56f - 0.08f) * u; b = 0.34f + (0.78f - 0.34f) * u;
                } else if (t < 0.50f) {
                    float u = (t - 0.25f) * 4f;
                    r = 0.09f + (0.55f - 0.09f) * u; g = 0.56f + (0.88f - 0.56f) * u; b = 0.78f + (0.24f - 0.78f) * u;
                } else if (t < 0.75f) {
                    float u = (t - 0.50f) * 4f;
                    r = 0.55f + (0.98f - 0.55f) * u; g = 0.88f + (0.55f - 0.88f) * u; b = 0.24f + (0.09f - 0.24f) * u;
                } else {
                    float u = (t - 0.75f) * 4f;
                    r = 0.98f + (0.84f - 0.98f) * u; g = 0.55f + (0.12f - 0.55f) * u; b = 0.09f + (0.14f - 0.09f) * u;
                }
            }
            ui.drawRect(barX + i * segW, barY, segW + 0.5f, 10f, 0f, r, g, b, 0.96f);
        }

        ui.drawText(minLbl, barX, barY + 13f, 0.78f, 0.72f, 0.86f, 0.96f, 0.92f);
        float midW = midLbl.length() * 8f * 0.78f;
        ui.drawText(midLbl, barX + (barW - midW) * 0.5f, barY + 13f, 0.78f, 0.84f, 0.94f, 0.76f, 0.92f);
        float maxW = maxLbl.length() * 8f * 0.78f;
        ui.drawText(maxLbl, barX + barW - maxW, barY + 13f, 0.78f, 0.98f, 0.56f, 0.44f, 0.92f);
    }

    public static void appendFixed1(StringBuilder sb, float v) {
        if (!Float.isFinite(v)) { sb.append("NaN"); return; }
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 10f);
        sb.append(s / 10).append('.').append(s % 10);
    }

    public static void appendFixed2(StringBuilder sb, float v) {
        if (!Float.isFinite(v)) { sb.append("NaN"); return; }
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 100f);
        int f = s % 100;
        sb.append(s / 100).append('.');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    public static void appendFixed3(StringBuilder sb, float v) {
        if (!Float.isFinite(v)) { sb.append("NaN"); return; }
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 1000f);
        int f = s % 1000;
        sb.append(s / 1000).append('.');
        if (f < 100) sb.append('0');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    public static void appendFixed4(StringBuilder sb, float v) {
        if (!Float.isFinite(v)) { sb.append("NaN"); return; }
        if (v < 0f) { sb.append('-'); v = -v; }
        int s = Math.round(v * 10000f);
        int f = s % 10000;
        sb.append(s / 10000).append('.');
        if (f < 1000) sb.append('0');
        if (f < 100) sb.append('0');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    public static void appendSigned1(StringBuilder sb, float v) {
        if (v >= 0f) sb.append('+');
        appendFixed1(sb, v);
    }

    public static void appendSigned2(StringBuilder sb, float v) {
        if (v >= 0f) sb.append('+');
        appendFixed2(sb, v);
    }

    public static void appendSigned3(StringBuilder sb, float v) {
        if (v >= 0f) sb.append('+');
        appendFixed3(sb, v);
    }

    public static void appendSci1(StringBuilder sb, double absVal) {
        if (absVal < 1e-30 || !Double.isFinite(absVal)) {
            sb.append("0.0e0");
            return;
        }
        int exp = (int) Math.floor(Math.log10(absVal));
        double mant = absVal / Math.pow(10.0, exp);
        if (mant >= 9.95) {
            mant *= 0.1;
            exp++;
        }
        appendFixed1(sb, (float) mant);
        sb.append('e').append(exp);
    }

    public static void appendSignedSci1(StringBuilder sb, double val) {
        if (val < 0.0) {
            sb.append('-');
            val = -val;
        } else {
            sb.append('+');
        }
        appendSci1(sb, val);
    }
}
