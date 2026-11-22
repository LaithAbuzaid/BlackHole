package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.CameraController;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scientific validation of Binary Black Hole Merger PN/QNM physics, C^1 phase continuity,
 * energy/angular-momentum tracking, GW150914 reference comparison, and Schwarzschild plunge limits.
 */
class BinaryMergerValidationTest {

    @Test
    void postNewtonianOrbitalFrequencyHasCorrectNegative1PnSignAndSubluminalSpeed() {
        double eta = 0.25;
        for (double a : new double[]{9.0, 6.0, 4.0, 2.5}) {
            double omegaNewt = Math.pow(a, -1.5);
            double omegaPn = BinaryMergerPhysics.computeOrbitalOmega(a, eta);
            assertTrue(omegaPn < omegaNewt,
                    "1PN harmonic correction -(3-eta)/(2a) must reduce Omega below Newtonian at a=" + a);
            assertTrue(a * omegaPn < 0.75, "Relative orbital speed a*Omega must stay strictly subluminal at a=" + a);
        }
    }

    @Test
    void binaryMergerPreservesCenterOfMassContinuityLiveQnmAndEnergyBudget() {
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        float q = 0.81f; // GW150914-like
        bin.reset(q);

        double dt = 1.0 / 120.0;
        float prevAmp = bin.strainAmplitude;
        float prevFreq = bin.gwFreqHz;
        float prevPulse = bin.mergerPulse;
        float prevRad = bin.radiatedEnergy;
        boolean sawMerger = false;
        boolean sawRingdown = false;
        double maxAmpJump = 0.0;
        double maxFreqJump = 0.0;
        double maxPulseJump = 0.0;

        for (int i = 0; i < 6500; i++) {
            bin.step(dt, q, 1.0f, false);

            // Center of mass must stay at origin
            double comX = bin.mass1 * bin.pos1X + bin.mass2 * bin.pos2X;
            double comZ = bin.mass1 * bin.pos1Z + bin.mass2 * bin.pos2Z;
            assertEquals(0.0, comX, 1e-5, "Center-of-mass X drifted");
            assertEquals(0.0, comZ, 1e-5, "Center-of-mass Z drifted");

            double comVx = bin.mass1 * bin.vel1X + bin.mass2 * bin.vel2X;
            double comVz = bin.mass1 * bin.vel1Z + bin.mass2 * bin.vel2Z;
            assertEquals(0.0, comVx, 1e-4, "Linear momentum X not zero");
            assertEquals(0.0, comVz, 1e-4, "Linear momentum Z not zero");

            // Check C^0/C^1 continuity across every step including phase transitions
            maxAmpJump = Math.max(maxAmpJump, Math.abs(bin.strainAmplitude - prevAmp));
            maxFreqJump = Math.max(maxFreqJump, Math.abs(bin.gwFreqHz - prevFreq));
            maxPulseJump = Math.max(maxPulseJump, Math.abs(bin.mergerPulse - prevPulse));

            assertTrue(bin.radiatedEnergy >= prevRad - 1e-5f,
                    "Radiated GW energy must increase monotonically");

            if (bin.phase() == BinaryMergerPhysics.Phase.MERGER) {
                sawMerger = true;
            }
            if (bin.phase() == BinaryMergerPhysics.Phase.RINGDOWN) {
                sawRingdown = true;
                // Ringdown frequency must be driven directly by Berti-Cardoso-Will (2006) qnmFreqGeom
                assertEquals(bin.qnmFreqGeom(), bin.gwFreqGeom, 1e-3,
                        "Ringdown must use exact BCW (2,2,0) QNM frequency");
            }

            prevAmp = bin.strainAmplitude;
            prevFreq = bin.gwFreqHz;
            prevPulse = bin.mergerPulse;
            prevRad = bin.radiatedEnergy;
            if (bin.isMergerFinished()) break;
        }

        assertTrue(sawMerger && sawRingdown, "Binary must progress through MERGER and RINGDOWN");
        assertTrue(maxAmpJump < 0.04, "Strain amplitude must transition smoothly (no +11% jump), maxJump=" + maxAmpJump);
        assertTrue(maxFreqJump < 12.0, "GW frequency must transition smoothly, maxJump=" + maxFreqJump);
        assertTrue(maxPulseJump < 0.08, "Merger pulse must transition smoothly (no 8.4x jump), maxJump=" + maxPulseJump);
        assertEquals(1.0 - bin.remnantMass, bin.radiatedEnergy, 1e-3,
                "Final radiated energy must equal 1 - M_f");
    }

    @Test
    void gw150914ReferenceDatasetLoadsAndMatchesPublishedRemnantParameters() {
        GwReferenceData ref = GwReferenceData.loadBundledGw150914();
        assertTrue(ref.sampleCount() >= 20);
        assertEquals(251.0, ref.peakFrequencyHz(), 1.0);
        assertTrue(Math.abs(ref.sampleStrainAtNormalized(0.75f)) <= 1.0f);

        // Compare BinaryMergerPhysics with GW150914 mass ratio against Abbott et al. (2016) / GWTC-1
        BinaryMergerPhysics bin = new BinaryMergerPhysics();
        bin.reset((float) GwReferenceData.GW150914_MASS_RATIO);

        assertEquals(GwReferenceData.GW150914_REMNANT_MASS_FRAC, bin.remnantMass, 0.008,
                "Remnant mass fraction M_f/M must match GW150914 (~0.953)");
        assertEquals(GwReferenceData.GW150914_REMNANT_SPIN, bin.remnantSpin, 0.015,
                "Remnant spin a_f must match GW150914 (~0.69)");
        assertEquals(GwReferenceData.GW150914_RADIATED_FRAC, 1.0 - bin.remnantMass, 0.008,
                "Radiated energy fraction must match GW150914 (~4.7%)");

        // Verify test-particle limit (eta -> 0): radiated fraction approaches exact ISCO binding (1 - sqrt(8/9))*eta
        bin.reset(SimulationState.MIN_MASS_RATIO);
        double etaSmall = bin.symMassRatio;
        double expectedSmall = (1.0 - Math.sqrt(8.0 / 9.0)) * etaSmall + 0.545 * etaSmall * etaSmall;
        assertEquals(expectedSmall, 1.0 - bin.remnantMass, 1e-4);
    }

    @Test
    void schwarzschildHorizonPlungeReachesSingularityWithSpaghettificationAndZeroCauchyFlash() {
        SimulationState state = new SimulationState();
        state.kerrSpinEnabled = false; // Exact Schwarzschild a* = 0
        state.spinParam = 0.0f;
        CameraController cam = new CameraController();
        cam.setShowcasePose(9.0, 0.0, 0.12, 0.0, 0.0, 0.0, state);

        cam.startPlunge(state);
        for (int i = 0; i < 600 && !state.plungeEnded; i++) {
            cam.update(1.0 / 60.0, state, false, false);
        }

        assertTrue(state.plungeHorizonCrossed, "Schwarzschild plunge must cross r_s = 2M");
        assertTrue(state.plungeEnded, "Schwarzschild plunge must reach interior singularity cutoff");
        assertEquals(SimulationState.ZONE_SINGULARITY, state.plungeZone);
        assertEquals(0.0f, state.cauchyFlashIntensity, 1e-6f, "Schwarzschild has no Cauchy horizon flash");
        assertTrue(state.spaghettification > 0.85f, "Schwarzschild plunge must engage optical spaghettification");
        assertTrue(state.singularityFade > 0.85f, "Schwarzschild plunge must fade to singularity");
    }
}
