package io.github.laithabuzaid.blackhole.ui.vis;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.math.KerrMetric;
import io.github.laithabuzaid.blackhole.ui.UiRenderer;

/**
 * Zero-allocation 3D-to-2D projection helpers and exact Kerr-Schild oblate surface
 * wireframes shared across all mode-specific scientific visualizers.
 */
public final class Projection3D {

    private final float[] ptA = new float[2];
    private final float[] ptB = new float[2];
    private final float[] xyzA = new float[3];
    private final float[] xyzB = new float[3];

    public boolean projectPoint(float wx, float wy, float wz,
                                CameraController cam, int w, int h,
                                float aspect, float tanHalfFov, float[] out2) {
        float rx = wx - cam.posX;
        float ry = wy - cam.posY;
        float rz = wz - cam.posZ;
        float z = rx * cam.forwardX + ry * cam.forwardY + rz * cam.forwardZ;
        if (z <= 0.15f) {
            return false;
        }
        float x = rx * cam.rightX + ry * cam.rightY + rz * cam.rightZ;
        float y = rx * cam.upX + ry * cam.upY + rz * cam.upZ;
        float ndcX = x / (z * aspect * tanHalfFov);
        float ndcY = y / (z * tanHalfFov);
        if (ndcX < -1.45f || ndcX > 1.45f || ndcY < -1.45f || ndcY > 1.45f) {
            return false;
        }
        out2[0] = (ndcX * 0.5f + 0.5f) * w;
        out2[1] = (0.5f - ndcY * 0.5f) * h;
        return true;
    }

    public void drawWorldLine(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                              CameraController cam,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float thickness, boolean dashed,
                              float r, float g, float b, float a) {
        if (projectPoint(x0, y0, z0, cam, w, h, aspect, tanHalfFov, ptA)
                && projectPoint(x1, y1, z1, cam, w, h, aspect, tanHalfFov, ptB)) {
            if (dashed) {
                ui.drawDashedLine(ptA[0], ptA[1], ptB[0], ptB[1], thickness, 5f, 4f, r, g, b, a);
            } else {
                ui.drawLine(ptA[0], ptA[1], ptB[0], ptB[1], thickness, r, g, b, a);
            }
        }
    }

    public void drawWorldArrow(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                               CameraController cam,
                               float x0, float y0, float z0,
                               float x1, float y1, float z1,
                               float thickness, float headPx,
                               float r, float g, float b, float a) {
        if (projectPoint(x0, y0, z0, cam, w, h, aspect, tanHalfFov, ptA)
                && projectPoint(x1, y1, z1, cam, w, h, aspect, tanHalfFov, ptB)) {
            ui.drawArrow(ptA[0], ptA[1], ptB[0], ptB[1], thickness, headPx, r, g, b, a);
        }
    }

    /** Draws a circle of Euclidean radius {@code radius} in an arbitrary orthonormal frame (e1, e3). */
    public void drawBasisRing(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                              CameraController cam,
                              float cx, float cy, float cz,
                              float e1x, float e1y, float e1z,
                              float e3x, float e3y, float e3z,
                              float radius, int segs, float thickness, boolean dashed,
                              float r, float g, float b, float a) {
        for (int i = 0; i < segs; i++) {
            if (dashed && (i & 1) == 1) {
                continue;
            }
            double phi0 = (2.0 * Math.PI * i) / segs;
            double phi1 = (2.0 * Math.PI * (i + 1)) / segs;
            float c0 = (float) Math.cos(phi0), s0 = (float) Math.sin(phi0);
            float c1 = (float) Math.cos(phi1), s1 = (float) Math.sin(phi1);
            float x0 = cx + radius * (c0 * e1x + s0 * e3x);
            float y0 = cy + radius * (c0 * e1y + s0 * e3y);
            float z0 = cz + radius * (c0 * e1z + s0 * e3z);
            float x1 = cx + radius * (c1 * e1x + s1 * e3x);
            float y1 = cy + radius * (c1 * e1y + s1 * e3y);
            float z1 = cz + radius * (c1 * e1z + s1 * e3z);
            drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, x0, y0, z0, x1, y1, z1, thickness, false, r, g, b, a);
        }
    }

    /**
     * Draws an exact Kerr-Schild equatorial ring at Kerr radial coordinate {@code rKerr}:
     * Cartesian equatorial radius is {@code rho_KS = sqrt(rKerr^2 + aPhys^2)}.
     */
    public void drawKerrEquatorialRing(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                       CameraController cam,
                                       float cx, float cy, float cz,
                                       float e1x, float e1y, float e1z,
                                       float e3x, float e3y, float e3z,
                                       double rKerr, double aPhys, int segs, float thickness, boolean dashed,
                                       float r, float g, float b, float a) {
        float rhoKs = (float) Math.sqrt(rKerr * rKerr + aPhys * aPhys);
        drawBasisRing(ui, w, h, aspect, tanHalfFov, cam, cx, cy, cz, e1x, e1y, e1z, e3x, e3y, e3z,
                rhoKs, segs, thickness, dashed, r, g, b, a);
    }

    /**
     * Draws an exact Kerr-Schild constant-r oblate spheroid meridian at azimuth {@code phiAz}
     * in basis {@code (e1, lHat, e3)} centered at {@code (cx, cy, cz)}.
     */
    public void drawKerrConstRMeridian(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                       CameraController cam,
                                       float cx, float cy, float cz,
                                       float e1x, float e1y, float e1z,
                                       float lHx, float lHy, float lHz,
                                       float e3x, float e3y, float e3z,
                                       double rKerr, double aPhys, double phiAz,
                                       int segs, float thickness, boolean dashed,
                                       float r, float g, float b, float a) {
        for (int i = 0; i < segs; i++) {
            if (dashed && (i & 1) == 1) {
                continue;
            }
            double th0 = (2.0 * Math.PI * i) / segs;
            double th1 = (2.0 * Math.PI * (i + 1)) / segs;
            KerrMetric.oblateToCartesian(rKerr, th0, phiAz, aPhys, xyzA);
            KerrMetric.oblateToCartesian(rKerr, th1, phiAz, aPhys, xyzB);
            float x0 = cx + xyzA[0] * e1x + xyzA[1] * lHx + xyzA[2] * e3x;
            float y0 = cy + xyzA[0] * e1y + xyzA[1] * lHy + xyzA[2] * e3y;
            float z0 = cz + xyzA[0] * e1z + xyzA[1] * lHz + xyzA[2] * e3z;
            float x1 = cx + xyzB[0] * e1x + xyzB[1] * lHx + xyzB[2] * e3x;
            float y1 = cy + xyzB[0] * e1y + xyzB[1] * lHy + xyzB[2] * e3y;
            float z1 = cz + xyzB[0] * e1z + xyzB[1] * lHz + xyzB[2] * e3z;
            drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, x0, y0, z0, x1, y1, z1, thickness, false, r, g, b, a);
        }
    }

    /**
     * Draws an exact Kerr-Schild ergosphere meridian {@code r_ergo(theta) = 1 + sqrt(1 - a^2 cos^2(theta))}
     * mapped into Cartesian Kerr-Schild coordinates via {@link KerrMetric#oblateToCartesian}.
     */
    public void drawKerrErgosphereMeridian(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                           CameraController cam, double a, double phiAz,
                                           int segs, float thickness,
                                           float r, float g, float b, float aCol) {
        for (int i = 0; i < segs; i++) {
            double th0 = (2.0 * Math.PI * i) / segs;
            double th1 = (2.0 * Math.PI * (i + 1)) / segs;
            double r0 = KerrMetric.ergosphereRadius(a, Math.cos(th0));
            double r1 = KerrMetric.ergosphereRadius(a, Math.cos(th1));
            KerrMetric.oblateToCartesian(r0, th0, phiAz, a, xyzA);
            KerrMetric.oblateToCartesian(r1, th1, phiAz, a, xyzB);
            drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,
                    xyzA[0], xyzA[1], xyzA[2], xyzB[0], xyzB[1], xyzB[2],
                    thickness, (i & 1) == 1, r, g, b, aCol);
        }
    }

    /** Draws a wireframe cube covering {@code [-halfExtent, +halfExtent]^3} (for 64^3 TDE Eulerian grid). */
    public void drawBoxWireframe(UiRenderer ui, int w, int h, float aspect, float tanHalfFov,
                                 CameraController cam, float e, float thickness,
                                 float r, float g, float b, float a) {
        // Bottom y = -e
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e, -e, -e,  e, -e, -e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e, -e, -e,  e, -e,  e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e, -e,  e, -e, -e,  e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e, -e,  e, -e, -e, -e, thickness, true, r, g, b, a);
        // Top y = +e
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e,  e, -e,  e,  e, -e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e,  e, -e,  e,  e,  e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e,  e,  e, -e,  e,  e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e,  e,  e, -e,  e, -e, thickness, true, r, g, b, a);
        // Verticals
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e, -e, -e, -e,  e, -e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e, -e, -e,  e,  e, -e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam,  e, -e,  e,  e,  e,  e, thickness, true, r, g, b, a);
        drawWorldLine(ui, w, h, aspect, tanHalfFov, cam, -e, -e,  e, -e,  e,  e, thickness, true, r, g, b, a);
    }
}
