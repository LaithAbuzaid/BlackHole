package com.mycompany.mavenproject1.core;

/**
 * Smooth inertial camera supporting both oblate spheroidal Kerr-Schild orbits
 * (down to {@code r = 0.25 M} in Free-Fall mode) and signed proper-distance
 * Morris-Thorne wormhole throat traversal ({@code l_cam in [-35, +35]}).
 *
 * <p>All vectors are stored in pre-allocated primitive fields so per-frame
 * updates incur zero heap allocations.</p>
 */
public final class CameraController {

    private static final double MIN_FREEFALL_DIST = 0.25;
    private static final double MIN_STATIC_DIST = 2.45;
    private static final double MAX_DIST = 60.0;
    private static final double MAX_PITCH = 1.48;

    private static final double MIN_WORMHOLE_L = -35.0;
    private static final double MAX_WORMHOLE_L = 35.0;
    private static final double WORMHOLE_KEY_SPEED = 11.0;

    // Black hole orbital state
    private double targetDist = 38.0;
    private double camDist = 38.0;
    private double targetYaw = 0.26;
    private double camYaw = 0.26;
    private double targetPitch = 0.145;
    private double camPitch = 0.145;
    private double targetRoll = -0.17;
    private double camRoll = -0.17;

    // Wormhole signed proper distance l_cam & smooth automated fly-through state
    private double targetWormholeL = 13.5;
    private double wormholeL = 13.5;
    private double targetLookFlip = 0.0; // 0.0 = facing -e_r (toward Universe B), PI = facing +e_r (toward Universe A)
    private double lookFlip = 0.0;
    private boolean autoFlyThrough = false;
    private double flyThroughDir = -1.0;
    private double flyThroughGoalL = -13.5;

    // Pre-allocated camera position, radial axis & orthonormal basis updated in place
    public float posX, posY, posZ;
    public float radialX, radialY, radialZ;
    public float forwardX, forwardY, forwardZ;
    public float rightX, rightY, rightZ;
    public float upX, upY, upZ;

    public CameraController() {
        applyPresetGargantuaEdge();
        snapToTarget();
    }

    public void update(double dt, SimulationState state, boolean keyUpHeld, boolean keyDownHeld) {
        if (!state.paused && state.autoOrbit) {
            targetYaw += dt * 0.14;
            if (!state.wormholeMode) {
                targetPitch = 0.14 + Math.sin(state.simTime * 0.20) * 0.28;
            }
        }

        if (state.wormholeMode) {
            updateWormholeFlight(dt, keyUpHeld, keyDownHeld);
        } else {
            autoFlyThrough = false;
            double minAllowed = state.freeFallMode
                    ? MIN_FREEFALL_DIST
                    : Math.max(MIN_STATIC_DIST, state.outerHorizonRadius() + 0.45);
            if (targetDist < minAllowed) {
                targetDist = minAllowed;
            }
            if (targetDist > MAX_DIST) {
                targetDist = MAX_DIST;
            }
        }

        double lerp = 1.0 - Math.exp(-dt * 11.0);
        camDist += (targetDist - camDist) * lerp;
        camYaw += (targetYaw - camYaw) * lerp;
        camPitch += (targetPitch - camPitch) * lerp;
        camRoll += (targetRoll - camRoll) * lerp;

        double whLerp = 1.0 - Math.exp(-dt * 7.5);
        wormholeL += (targetWormholeL - wormholeL) * whLerp;
        lookFlip += (targetLookFlip - lookFlip) * (1.0 - Math.exp(-dt * 6.0));

        recomputeBasis(state.effectiveSpin(), state.wormholeMode);
    }

    private void updateWormholeFlight(double dt, boolean keyUpHeld, boolean keyDownHeld) {
        if (keyUpHeld || keyDownHeld) {
            autoFlyThrough = false;
            // Up advances along current forward direction (-l if facing -e_r, +l if flipped to +e_r)
            double dirSign = (Math.cos(targetLookFlip) >= 0.0) ? -1.0 : 1.0;
            double step = WORMHOLE_KEY_SPEED * dt;
            if (keyUpHeld) {
                targetWormholeL += dirSign * step;
            }
            if (keyDownHeld) {
                targetWormholeL -= dirSign * step;
            }
        } else if (autoFlyThrough) {
            // Smooth cinematic speed profile: gentle glide through throat l = 0
            double throatSlow = 0.48 + 0.52 * Math.min(1.0, Math.abs(wormholeL) / 6.0);
            targetWormholeL += flyThroughDir * (9.5 * throatSlow) * dt;
            if ((flyThroughDir < 0.0 && targetWormholeL <= flyThroughGoalL)
                    || (flyThroughDir > 0.0 && targetWormholeL >= flyThroughGoalL)) {
                targetWormholeL = flyThroughGoalL;
                autoFlyThrough = false;
            }
        }
        targetWormholeL = Math.max(MIN_WORMHOLE_L, Math.min(MAX_WORMHOLE_L, targetWormholeL));
    }

    public void orbitBy(double deltaX, double deltaY) {
        targetYaw -= deltaX * 0.0055;
        targetPitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, targetPitch + deltaY * 0.0055));
    }

    public void zoomBy(double scrollY, SimulationState state) {
        if (state.wormholeMode) {
            autoFlyThrough = false;
            double dirSign = (Math.cos(targetLookFlip) >= 0.0) ? -1.0 : 1.0;
            double step = Math.max(0.45, Math.abs(targetWormholeL) * 0.10 + 0.55);
            targetWormholeL = Math.max(MIN_WORMHOLE_L,
                    Math.min(MAX_WORMHOLE_L, targetWormholeL + dirSign * scrollY * step));
            return;
        }
        double minAllowed = state.freeFallMode
                ? MIN_FREEFALL_DIST
                : Math.max(MIN_STATIC_DIST, state.outerHorizonRadius() + 0.45);
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

    /** Triggers smooth automated throat traversal between Universe A and Universe B. */
    public void triggerWormholeFlyThrough() {
        autoFlyThrough = true;
        if (wormholeL >= 0.0) {
            // In Universe A: face toward throat (-e_r) and fly straight into Universe B
            targetLookFlip = 0.0;
            flyThroughDir = -1.0;
            flyThroughGoalL = -13.5;
        } else {
            // In Universe B: face back toward throat (+e_r) and fly straight into Universe A
            targetLookFlip = Math.PI;
            flyThroughDir = 1.0;
            flyThroughGoalL = 13.5;
        }
    }

    public void applyWormholeUniverseA() {
        autoFlyThrough = false;
        targetWormholeL = 13.5;
        targetLookFlip = 0.0;
        targetYaw = 0.0;
        targetPitch = 0.0;
        targetRoll = 0.0;
    }

    public void applyWormholeThroat() {
        autoFlyThrough = false;
        targetWormholeL = 0.0;
        targetLookFlip = 0.0;
        targetYaw = 0.0;
        targetPitch = 0.0;
        targetRoll = 0.0;
    }

    public void applyWormholeUniverseB() {
        autoFlyThrough = false;
        targetWormholeL = -13.5;
        targetLookFlip = Math.PI; // Look back at the wormhole crystal sphere showing Universe A
        targetYaw = 0.0;
        targetPitch = 0.0;
        targetRoll = 0.0;
    }

    public boolean isFlyingThrough() {
        return autoFlyThrough;
    }

    public double currentKerrRadius() {
        return camDist;
    }

    public double currentWormholeL() {
        return wormholeL;
    }

    public void resetWormholeDefaults() {
        applyWormholeUniverseA();
    }

    private void snapToTarget() {
        camDist = targetDist;
        camYaw = targetYaw;
        camPitch = targetPitch;
        camRoll = targetRoll;
        wormholeL = targetWormholeL;
        lookFlip = targetLookFlip;
        recomputeBasis(0.98, false);
    }

    private void recomputeBasis(double spinA, boolean wormholeMode) {
        double cosP = Math.cos(camPitch);
        double sinP = Math.sin(camPitch);
        double cosY = Math.cos(camYaw);
        double sinY = Math.sin(camYaw);

        // Unit radial axis on celestial sphere
        double erx = cosP * sinY;
        double ery = sinP;
        double erz = cosP * cosY;

        radialX = (float) erx;
        radialY = (float) ery;
        radialZ = (float) erz;

        // Oblate spheroidal Kerr-Schild placement for black hole mode
        double radialEq = Math.sqrt(camDist * camDist + spinA * spinA);
        double cx = radialEq * cosP * sinY;
        double cy = camDist * sinP;
        double cz = radialEq * cosP * cosY;

        double invFLen = 1.0 / Math.sqrt(cx * cx + cy * cy + cz * cz);
        double fx = wormholeMode ? -erx : (-cx * invFLen);
        double fy = wormholeMode ? -ery : (-cy * invFLen);
        double fz = wormholeMode ? -erz : (-cz * invFLen);

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

        // In wormhole mode, smoothly apply lookFlip around local up axis when looking back from Universe B
        if (wormholeMode && Math.abs(lookFlip) > 1e-4) {
            double cF = Math.cos(lookFlip);
            double sF = Math.sin(lookFlip);
            double nfx = fx * cF + rx * sF;
            double nfy = fy * cF;
            double nfz = fz * cF + rz * sF;
            double nrx = rx * cF - fx * sF;
            double nry = -fy * sF;
            double nrz = rz * cF - fz * sF;
            fx = nfx; fy = nfy; fz = nfz;
            rx = nrx; rz = nrz;
            // Re-orthogonalize up = cross(right, forward)
            ux = nry * fz - nrz * fy;
            uy = nrz * fx - nrx * fz;
            uz = nrx * fy - nry * fx;
        }

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
