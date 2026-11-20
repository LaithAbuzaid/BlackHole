package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for Kerr-Schild oblate spheroidal camera geometry, orthonormal basis
 * stability, Doran horizon-penetrating free-fall plunge, and Morris-Thorne wormhole flight.
 */
final class CameraControllerTest {

    private static void assertOrthonormalBasis(CameraController cam) {
        float fLen = (float) Math.sqrt(cam.forwardX * cam.forwardX + cam.forwardY * cam.forwardY + cam.forwardZ * cam.forwardZ);
        float rLen = (float) Math.sqrt(cam.rightX * cam.rightX + cam.rightY * cam.rightY + cam.rightZ * cam.rightZ);
        float uLen = (float) Math.sqrt(cam.upX * cam.upX + cam.upY * cam.upY + cam.upZ * cam.upZ);

        assertEquals(1.0f, fLen, 1e-4f, "forward must be unit length");
        assertEquals(1.0f, rLen, 1e-4f, "right must be unit length");
        assertEquals(1.0f, uLen, 1e-4f, "up must be unit length");

        float fr = cam.forwardX * cam.rightX + cam.forwardY * cam.rightY + cam.forwardZ * cam.rightZ;
        float fu = cam.forwardX * cam.upX + cam.forwardY * cam.upY + cam.forwardZ * cam.upZ;
        float ru = cam.rightX * cam.upX + cam.rightY * cam.upY + cam.rightZ * cam.upZ;

        assertEquals(0.0f, fr, 1e-4f, "forward and right must be orthogonal");
        assertEquals(0.0f, fu, 1e-4f, "forward and up must be orthogonal");
        assertEquals(0.0f, ru, 1e-4f, "right and up must be orthogonal");
    }

    @Test
    void oblateSpheroidalPlacementObeysExactKerrRadiusIdentity() {
        SimulationState state = new SimulationState();
        state.spinParam = 0.98f;
        CameraController cam = new CameraController();
        cam.setShowcasePose(12.6, 0.42, 0.25, -0.12, 0.0, 0.0, state);

        assertOrthonormalBasis(cam);

        double r = cam.currentKerrRadius();
        double a = state.effectiveSpin();
        double lhs = (cam.posX * cam.posX + cam.posZ * cam.posZ) / (r * r + a * a)
                   + (cam.posY * cam.posY) / (r * r);
        assertEquals(1.0, lhs, 1e-4, "Camera Cartesian position must lie on exact Kerr spheroid r=const");
    }

    @Test
    void doranPlungeCrossesOuterHorizonAndReachesCauchyClimax() {
        SimulationState state = new SimulationState();
        state.spinParam = 0.98f;
        state.attractTourActive = false;

        CameraController cam = new CameraController();
        cam.setShowcasePose(8.0, 0.3, 0.15, 0.0, 0.0, 0.0, state);
        cam.startPlunge(state);

        assertTrue(state.plungeActive);
        assertFalse(state.plungeHorizonCrossed);

        boolean sawUnitInfallSpeed = false;
        for (int step = 0; step < 600 && !state.plungeEnded; step++) {
            cam.update(1.0 / 60.0, state, false, false, 0.0f, false);
            assertOrthonormalBasis(cam);
            if (state.plungeVelocityC >= 0.99f) {
                sawUnitInfallSpeed = true;
            }
        }

        assertTrue(state.plungeHorizonCrossed, "Plunge must cross outer event horizon r_+");
        assertTrue(sawUnitInfallSpeed, "Doran infall speed must reach v/c >= 1.0 at r_+");
        assertTrue(state.plungeEnded, "Plunge must halt cleanly at Kerr Cauchy horizon");
        assertEquals(SimulationState.ZONE_CAUCHY_HORIZON, state.plungeZone);
        assertTrue(cam.currentKerrRadius() < state.outerHorizonRadius());
    }

    @Test
    void wormholeFlyThroughTraversesThroatToUniverseBAndStaysBounded() {
        SimulationState state = new SimulationState();
        state.wormholeMode = true;
        state.attractTourActive = false;

        CameraController cam = new CameraController();
        cam.setShowcaseWormholePose(13.5, 0.0, 0.0, 0.0);
        cam.triggerWormholeFlyThrough();

        for (int step = 0; step < 400; step++) {
            cam.update(1.0 / 60.0, state, false, false, 0.0f, false);
            assertOrthonormalBasis(cam);
            assertTrue(Math.abs(cam.currentWormholeL()) <= 35.001, "Wormhole l must stay within [-35, +35]");
        }

        assertFalse(cam.isFlyingThrough(), "Fly-through should complete within 400 frames");
        assertTrue(cam.currentWormholeL() < -12.0, "Camera should arrive in Universe B (negative l)");
    }

    @Test
    void doranPlungeSubsteppingMatchesBetween1xAnd10xTimelapse() {
        SimulationState slowState = new SimulationState();
        slowState.spinParam = 0.96f;
        slowState.attractTourActive = false;
        slowState.setTimeScale(1.0f);

        SimulationState fastState = new SimulationState();
        fastState.spinParam = 0.96f;
        fastState.attractTourActive = false;
        fastState.setTimeScale(10.0f);

        CameraController slowCam = new CameraController();
        slowCam.setShowcasePose(12.0, 0.3, 0.15, 0.0, 0.0, 0.0, slowState);
        slowCam.startPlunge(slowState);

        CameraController fastCam = new CameraController();
        fastCam.setShowcasePose(12.0, 0.3, 0.15, 0.0, 0.0, 0.0, fastState);
        fastCam.startPlunge(fastState);

        // Advance identical 1.0s of simulated plunge time: 60 frames @ 1x vs 6 frames @ 10x
        for (int i = 0; i < 60; i++) {
            slowCam.update(1.0 / 60.0, slowState, false, false, 0.0f, false);
        }
        for (int i = 0; i < 6; i++) {
            fastCam.update(1.0 / 60.0, fastState, false, false, 0.0f, false);
        }

        assertEquals(slowCam.currentKerrRadius(), fastCam.currentKerrRadius(), 0.08,
                "Fixed-substepped Doran plunge radius must stay consistent under 10x timelapse");
        assertOrthonormalBasis(fastCam);
    }
}
