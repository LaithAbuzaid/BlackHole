package com.mycompany.mavenproject1.core;

/**
 * Smooth inertial camera supporting:
 * <ul>
 *   <li>Oblate spheroidal Kerr-Schild orbits (down to {@code r = 0.25 M} in Free-Fall mode)</li>
 *   <li>Exact Doran / Kerr-Schild horizon-penetrating Free-Fall Plunge Simulator ({@code [P]})
 *       with full 360-degree independent free-look rotation across {@code r_+} and into the interior</li>
 *   <li>Signed proper-distance Morris-Thorne wormhole throat traversal ({@code l_cam in [-35, +35]})</li>
 * </ul>
 *
 * <p>All basis vectors are stored in pre-allocated primitive fields so per-frame
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

    // 360-degree free-look orientation active during Plunge mode
    private double targetFreeLookYaw = 0.0;
    private double freeLookYaw = 0.0;
    private double targetFreeLookPitch = 0.0;
    private double freeLookPitch = 0.0;

    // Saved pre-plunge orbit for clean abort/replay
    private double savedDist = 38.0;
    private double savedYaw = 0.26;
    private double savedPitch = 0.145;
    private double savedRoll = -0.17;

    // Wormhole signed proper distance l_cam & smooth automated fly-through state
    private double targetWormholeL = 13.5;
    private double wormholeL = 13.5;
    private double targetLookFlip = 0.0;
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
        if (!state.paused && state.autoOrbit && !state.plungeActive) {
            targetYaw += dt * 0.14;
            if (!state.wormholeMode) {
                targetPitch = 0.14 + Math.sin(state.simTime * 0.20) * 0.28;
            }
        }

        if (state.wormholeMode) {
            updateWormholeFlight(dt, keyUpHeld, keyDownHeld);
        } else if (state.plungeActive) {
            updatePlunge(dt, state);
        } else {
            autoFlyThrough = false;
            // Smoothly relax free-look back to 0 when returning to orbital mode
            double relax = 1.0 - Math.exp(-dt * 8.0);
            targetFreeLookYaw = 0.0;
            targetFreeLookPitch = 0.0;
            freeLookYaw += (0.0 - freeLookYaw) * relax;
            freeLookPitch += (0.0 - freeLookPitch) * relax;

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
        if (!state.plungeActive) {
            camDist += (targetDist - camDist) * lerp;
            camYaw += (targetYaw - camYaw) * lerp;
            camPitch += (targetPitch - camPitch) * lerp;
            camRoll += (targetRoll - camRoll) * lerp;
        } else {
            freeLookYaw += (targetFreeLookYaw - freeLookYaw) * lerp;
            freeLookPitch += (targetFreeLookPitch - freeLookPitch) * lerp;
            camRoll += (0.0 - camRoll) * lerp;
        }

        double whLerp = 1.0 - Math.exp(-dt * 7.5);
        wormholeL += (targetWormholeL - wormholeL) * whLerp;
        lookFlip += (targetLookFlip - lookFlip) * (1.0 - Math.exp(-dt * 6.0));

        recomputeBasis(state.effectiveSpin(), state.wormholeMode, state.plungeActive);
    }

    /** Starts or replays a physically accurate Doran / Kerr-Schild free-fall plunge. */
    public void startPlunge(SimulationState state) {
        state.wormholeMode = false;
        state.autoOrbit = false;
        state.freeFallMode = true;
        state.paused = false;

        // If replaying from an already-deep/ended plunge, launch cleanly from an outer grazing approach
        if (state.plungeEnded || camDist < state.outerHorizonRadius() + 2.0) {
            camDist = Math.max(16.0, Math.min(savedDist, 28.0));
            targetDist = camDist;
            camYaw = savedYaw;
            targetYaw = savedYaw;
            camPitch = Math.max(0.10, Math.min(0.45, Math.abs(savedPitch)));
            targetPitch = camPitch;
        } else {
            savedDist = targetDist;
            savedYaw = targetYaw;
            savedPitch = targetPitch;
            savedRoll = targetRoll;
        }

        state.clearPlungeTelemetry();
        state.plungeActive = true;
        targetFreeLookYaw = 0.0;
        freeLookYaw = 0.0;
        targetFreeLookPitch = 0.0;
        freeLookPitch = 0.0;

        // Seed immediate velocity/tidal readout on frame 0
        double a = state.effectiveSpin();
        double r = camDist;
        state.plungeVelocityC = (float) Math.sqrt((2.0 * r) / (r * r + a * a));
        state.plungeTidalGrad = (float) (2.0 / Math.max(1e-3, r * r * r));
        classifyPlungeZone(r, a, state);
    }

    /** Aborts an active plunge and smoothly returns the camera to its pre-plunge exterior orbit. */
    public void abortPlunge(SimulationState state) {
        state.clearPlungeTelemetry();
        state.freeFallMode = false;
        targetDist = Math.max(6.5, savedDist);
        targetYaw = savedYaw;
        targetPitch = savedPitch;
        targetRoll = savedRoll;
        targetFreeLookYaw = 0.0;
        targetFreeLookPitch = 0.0;
    }

    /**
     * Exact Doran / Kerr-Schild proper-time free-fall integrator from rest at infinity (L_z = 0).
     * Reaches exact v/c = 1.000 at r_+ = M + sqrt(M^2 - a^2) and exceeds c inside r_+.
     */
    private void updatePlunge(double dt, SimulationState state) {
        double a = state.effectiveSpin();
        double rPlus = state.outerHorizonRadius();
        double rMinus = state.innerHorizonRadius();
        boolean isKerr = (a > 0.05);

        if (state.paused || state.plungeEnded) {
            return;
        }

        // Scale proper time so exterior approach is brisk (~2.7s from 12.6M, ~8.4s from 38M)
        // while interior crossing & climax last ~2.7s for full 360-degree observation
        double r = camDist;
        double paceFactor;
        if (r > 12.0) {
            paceFactor = 16.0;
        } else if (r > rPlus) {
            paceFactor = 7.5;
        } else {
            paceFactor = isKerr ? 0.14 : 0.48;
        }
        double dTau = dt * state.plungeSpeed * paceFactor;

        // Midpoint integration of exact Doran infall speed beta_in = sqrt(2 M r / (r^2 + a^2))
        double beta0 = Math.sqrt(Math.max(0.0, (2.0 * r) / Math.max(1e-4, r * r + a * a)));
        double rMid = Math.max(0.08, r - 0.5 * beta0 * dTau);
        double betaMid = Math.sqrt(Math.max(0.0, (2.0 * rMid) / Math.max(1e-4, rMid * rMid + a * a)));
        double rNext = r - betaMid * dTau;

        // Prograde ZAMO frame-dragging spiral azimuth during Kerr plunge
        if (a > 0.001) {
            double sMid = rMid * rMid + a * a;
            double omegaZamo = (2.0 * a * rMid) / Math.max(0.25, sMid * sMid);
            camYaw += omegaZamo * dTau * 1.45;
            targetYaw = camYaw;
        }

        // Proper time vs. distant Boyer-Lindquist coordinate time (diverging at r_+)
        state.plungeProperTime += dTau;
        if (rMid > rPlus * 1.003 && !state.plungeHorizonCrossed) {
            double delta = Math.max(1e-4, rMid * rMid - 2.0 * rMid + a * a);
            state.plungeCoordTime += dTau * ((rMid * rMid + a * a) / delta);
        } else {
            state.plungeHorizonCrossed = true;
        }

        // Interior Climax Handling
        if (isKerr) {
            double cauchyStop = rMinus + 0.032;
            if (rNext <= cauchyStop) {
                rNext = cauchyStop;
                state.plungeEnded = true;
            }
            double interiorSpan = Math.max(0.10, (rPlus - rMinus) * 0.78);
            double distCauchy = Math.max(0.0, rNext - cauchyStop);
            if (rNext < rPlus) {
                float flash = (float) Math.exp(-Math.pow(distCauchy / (interiorSpan * 0.26), 1.55));
                state.cauchyFlashIntensity = Math.min(1.0f, flash);
            } else {
                state.cauchyFlashIntensity = 0.0f;
            }
            state.spaghettification = 0.0f;
            state.singularityFade = state.plungeEnded ? 0.65f : 0.0f;
        } else {
            double singStop = 0.12;
            if (rNext <= singStop) {
                rNext = singStop;
                state.plungeEnded = true;
            }
            state.cauchyFlashIntensity = 0.0f;
            if (rNext < 1.50) {
                state.spaghettification = (float) Math.min(1.0, Math.pow(Math.max(0.0, (1.50 - rNext) / 1.32), 1.65));
            } else {
                state.spaghettification = 0.0f;
            }
            if (rNext < 0.42) {
                state.singularityFade = (float) Math.min(1.0, Math.max(0.0, (0.42 - rNext) / 0.30));
            } else {
                state.singularityFade = 0.0f;
            }
        }

        camDist = rNext;
        targetDist = rNext;
        state.plungeVelocityC = (float) Math.sqrt(Math.max(0.0, (2.0 * rNext) / Math.max(1e-4, rNext * rNext + a * a)));
        state.plungeTidalGrad = (float) (2.0 / Math.max(1e-3, rNext * rNext * rNext));
        classifyPlungeZone(rNext, a, state);
    }

    private static void classifyPlungeZone(double r, double a, SimulationState state) {
        double rPlus = state.outerHorizonRadius();
        double rMinus = state.innerHorizonRadius();
        double rPhoton = 1.5 + 1.5 * Math.sqrt(Math.max(0.0, 1.0 - 0.65 * a)); // Approx photon shell boundary
        if (a > 0.05 && r <= rMinus + 0.14) {
            state.plungeZone = SimulationState.ZONE_CAUCHY_HORIZON;
        } else if (a <= 0.05 && r <= 0.36) {
            state.plungeZone = SimulationState.ZONE_SINGULARITY;
        } else if (r < rPlus * 0.94) {
            state.plungeZone = SimulationState.ZONE_INTERIOR;
        } else if (r <= rPlus * 1.06) {
            state.plungeZone = SimulationState.ZONE_EVENT_HORIZON;
        } else if (r <= rPhoton) {
            state.plungeZone = SimulationState.ZONE_PHOTON_SPHERE;
        } else {
            state.plungeZone = SimulationState.ZONE_EXTERIOR;
        }
    }

    private void updateWormholeFlight(double dt, boolean keyUpHeld, boolean keyDownHeld) {
        if (keyUpHeld || keyDownHeld) {
            autoFlyThrough = false;
            double dirSign = (Math.cos(targetLookFlip) >= 0.0) ? -1.0 : 1.0;
            double step = WORMHOLE_KEY_SPEED * dt;
            if (keyUpHeld) {
                targetWormholeL += dirSign * step;
            }
            if (keyDownHeld) {
                targetWormholeL -= dirSign * step;
            }
        } else if (autoFlyThrough) {
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

    /** Mouse drag handler: 360° free-look during Plunge mode, orbital camera otherwise. */
    public void orbitBy(double deltaX, double deltaY, SimulationState state) {
        if (state.plungeActive) {
            // Full 360-degree yaw + ±85° pitch free-look inside/outside horizon
            targetFreeLookYaw += deltaX * 0.0055;
            targetFreeLookPitch = Math.max(-1.48, Math.min(1.48, targetFreeLookPitch - deltaY * 0.0055));
            return;
        }
        targetYaw -= deltaX * 0.0055;
        targetPitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, targetPitch + deltaY * 0.0055));
    }

    public void zoomBy(double scrollY, SimulationState state) {
        if (state.plungeActive) {
            // Scroll adjusts plunge speed smoothly during dive
            state.plungeSpeed = (float) Math.max(SimulationState.MIN_PLUNGE_SPEED,
                    Math.min(SimulationState.MAX_PLUNGE_SPEED, state.plungeSpeed + scrollY * 0.12));
            return;
        }
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
        state.clearPlungeTelemetry();
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
            targetLookFlip = 0.0;
            flyThroughDir = -1.0;
            flyThroughGoalL = -13.5;
        } else {
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
        targetLookFlip = Math.PI;
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
        recomputeBasis(0.98, false, false);
    }

    private void recomputeBasis(double spinA, boolean wormholeMode, boolean plungeActive) {
        double cosP = Math.cos(camPitch);
        double sinP = Math.sin(camPitch);
        double cosY = Math.cos(camYaw);
        double sinY = Math.sin(camYaw);

        double erx = cosP * sinY;
        double ery = sinP;
        double erz = cosP * cosY;

        radialX = (float) erx;
        radialY = (float) ery;
        radialZ = (float) erz;

        // Oblate spheroidal Kerr-Schild placement: exact Kerr radius == camDist everywhere
        double radialEq = Math.sqrt(camDist * camDist + spinA * spinA);
        double cx = radialEq * cosP * sinY;
        double cy = camDist * sinP;
        double cz = radialEq * cosP * cosY;

        double invFLen = 1.0 / Math.max(1e-6, Math.sqrt(cx * cx + cy * cy + cz * cz));
        double fx = wormholeMode ? -erx : (-cx * invFLen);
        double fy = wormholeMode ? -ery : (-cy * invFLen);
        double fz = wormholeMode ? -erz : (-cz * invFLen);

        double rx = fz;
        double ry = 0.0;
        double rz = -fx;
        double rLen = Math.sqrt(rx * rx + rz * rz);
        if (rLen < 1e-6) {
            rx = 1.0;
            rz = 0.0;
            rLen = 1.0;
        }
        rx /= rLen;
        rz /= rLen;

        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        if (wormholeMode && Math.abs(lookFlip) > 1e-4) {
            double cF = Math.cos(lookFlip);
            double sF = Math.sin(lookFlip);
            double nfx = fx * cF + rx * sF;
            double nfy = fy * cF + ry * sF;
            double nfz = fz * cF + rz * sF;
            double nrx = rx * cF - fx * sF;
            double nry = ry * cF - fy * sF;
            double nrz = rz * cF - fz * sF;
            fx = nfx; fy = nfy; fz = nfz;
            rx = nrx; ry = nry; rz = nrz;
            ux = ry * fz - rz * fy;
            uy = rz * fx - rx * fz;
            uz = rx * fy - ry * fx;
        } else if (plungeActive || Math.abs(freeLookYaw) > 1e-4 || Math.abs(freeLookPitch) > 1e-4) {
            // Apply full 360° yaw around local up, then pitch around rotated right
            double cyL = Math.cos(freeLookYaw);
            double syL = Math.sin(freeLookYaw);
            double yfx = fx * cyL + rx * syL;
            double yfy = fy * cyL + ry * syL;
            double yfz = fz * cyL + rz * syL;
            double yrx = rx * cyL - fx * syL;
            double yry = ry * cyL - fy * syL;
            double yrz = rz * cyL - fz * syL;

            double cpL = Math.cos(freeLookPitch);
            double spL = Math.sin(freeLookPitch);
            fx = yfx * cpL + ux * spL;
            fy = yfy * cpL + uy * spL;
            fz = yfz * cpL + uz * spL;
            double nux = ux * cpL - yfx * spL;
            double nuy = uy * cpL - yfy * spL;
            double nuz = uz * cpL - yfz * spL;

            rx = yrx; ry = yry; rz = yrz;
            ux = nux; uy = nuy; uz = nuz;
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
        rightY = (float) (ry * cosR + uy * sinR);
        rightZ = (float) (rz * cosR + uz * sinR);

        upX = (float) (ux * cosR - rx * sinR);
        upY = (float) (uy * cosR - ry * sinR);
        upZ = (float) (uz * cosR - rz * sinR);
    }
}
