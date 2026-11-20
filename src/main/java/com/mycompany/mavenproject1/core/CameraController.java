package com.mycompany.mavenproject1.core;

/**
 * Smooth inertial orbital camera in oblate spheroidal Kerr-Schild coordinates.
 *
 * <p>Uses pre-allocated primitive fields so per-frame updates incur zero heap
 * allocations. Placing the camera via oblate spheroidal coordinates guarantees
 * the exact Kerr radius equals {@code camDist} even when diving inside the
 * event horizon down to {@code r = 0.25 M}, safely avoiding the equatorial ring
 * singularity at {@code r = 0}.</p>
 */
public final class CameraController {

    private static final double MIN_FREEFALL_DIST = 0.25;
    private static final double MIN_STATIC_DIST = 2.45;
    private static final double MAX_DIST = 60.0;
    private static final double MAX_PITCH = 1.48;

    private double targetDist = 32.0;
    private double camDist = 32.0;
    private double targetYaw = 0.26;
    private double camYaw = 0.26;
    private double targetPitch = 0.135;
    private double camPitch = 0.135;
    private double targetRoll = -0.17;
    private double camRoll = -0.17;

    // Pre-allocated camera position & orthonormal basis updated in place each frame
    public float posX, posY, posZ;
    public float forwardX, forwardY, forwardZ;
    public float rightX, rightY, rightZ;
    public float upX, upY, upZ;

    public CameraController() {
        applyPresetGargantuaEdge();
        snapToTarget();
    }

    public void update(double dt, SimulationState state) {
        if (!state.paused && state.autoOrbit) {
            targetYaw += dt * 0.14;
            targetPitch = 0.14 + Math.sin(state.simTime * 0.20) * 0.28;
        }

        double minAllowed = state.freeFallMode
                ? MIN_FREEFALL_DIST
                : Math.max(MIN_STATIC_DIST, state.outerHorizonRadius() + 0.45);
        if (targetDist < minAllowed) {
            targetDist = minAllowed;
        }
        if (targetDist > MAX_DIST) {
            targetDist = MAX_DIST;
        }

        double lerp = 1.0 - Math.exp(-dt * 11.0);
        camDist += (targetDist - camDist) * lerp;
        camYaw += (targetYaw - camYaw) * lerp;
        camPitch += (targetPitch - camPitch) * lerp;
        camRoll += (targetRoll - camRoll) * lerp;

        recomputeBasis(state.effectiveSpin());
    }

    public void orbitBy(double deltaX, double deltaY) {
        targetYaw -= deltaX * 0.0055;
        targetPitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, targetPitch + deltaY * 0.0055));
    }

    public void zoomBy(double scrollY, SimulationState state) {
        double minAllowed = state.freeFallMode
                ? MIN_FREEFALL_DIST
                : Math.max(MIN_STATIC_DIST, state.outerHorizonRadius() + 0.45);
        // Logarithmic zoom feel: fine precision near photon sphere/horizon, fast far out
        double step = Math.max(0.08, targetDist * 0.085);
        targetDist = Math.max(minAllowed, Math.min(MAX_DIST, targetDist - scrollY * step));
    }

    public void toggleCinematicRoll() {
        targetRoll = (Math.abs(targetRoll) > 0.01) ? 0.0 : -0.17;
    }

    /** Preset [1]: Iconic Interstellar / Gargantua oblique edge-on view. */
    public void applyPresetGargantuaEdge() {
        targetDist = 38.0;
        targetYaw = 0.26;
        targetPitch = 0.145;
        targetRoll = -0.17;
    }

    /** Preset [2]: Polar vortex looking down the spin axis and relativistic jet funnel. */
    public void applyPresetPolarVortex() {
        targetDist = 38.0;
        targetYaw = 0.0;
        targetPitch = 1.28;
        targetRoll = 0.0;
    }

    /** Preset [3]: Close grazing view emphasizing D-shaped Kerr shadow & photon ring. */
    public void applyPresetPhotonRing() {
        targetDist = 12.6;
        targetYaw = 0.42;
        targetPitch = 0.08;
        targetRoll = -0.12;
    }

    /** Preset [4]: Horizon dive inside r_+ using horizon-penetrating Free-Fall observer. */
    public void applyPresetHorizonDive(SimulationState state) {
        state.freeFallMode = true;
        targetDist = 0.92;
        targetYaw = 0.35;
        targetPitch = 0.24;
        targetRoll = -0.08;
    }

    public double currentKerrRadius() {
        return camDist;
    }

    private void snapToTarget() {
        camDist = targetDist;
        camYaw = targetYaw;
        camPitch = targetPitch;
        camRoll = targetRoll;
        recomputeBasis(0.98);
    }

    private void recomputeBasis(double spinA) {
        double cosP = Math.cos(camPitch);
        double sinP = Math.sin(camPitch);
        double cosY = Math.cos(camYaw);
        double sinY = Math.sin(camYaw);

        // Oblate spheroidal Kerr-Schild placement: exact Kerr radius == camDist everywhere
        double radialEq = Math.sqrt(camDist * camDist + spinA * spinA);
        double cx = radialEq * cosP * sinY;
        double cy = camDist * sinP;
        double cz = radialEq * cosP * cosY;

        double invFLen = 1.0 / Math.sqrt(cx * cx + cy * cy + cz * cz);
        double fx = -cx * invFLen;
        double fy = -cy * invFLen;
        double fz = -cz * invFLen;

        double rx = fz;
        double rz = -fx;
        double rLen = Math.sqrt(rx * rx + rz * rz);
        if (rLen < 1e-6) {
            rx = 1.0;
            rz = 0.0;
            rLen = 1.0;
        }
        rx /= rLen;
        rz /= rLen;

        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double cosR = Math.cos(camRoll);
        double sinR = Math.sin(camRoll);

        posX = (float) cx;
        posY = (float) cy;
        posZ = (float) cz;

        forwardX = (float) fx;
        forwardY = (float) fy;
        forwardZ = (float) fz;

        rightX = (float) (rx * cosR + ux * sinR);
        rightY = (float) (uy * sinR);
        rightZ = (float) (rz * cosR + uz * sinR);

        upX = (float) (ux * cosR - rx * sinR);
        upY = (float) (uy * cosR);
        upZ = (float) (uz * cosR - rz * sinR);
    }
}
