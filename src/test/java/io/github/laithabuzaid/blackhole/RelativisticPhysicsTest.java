package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.BinaryMergerPhysics;
import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.util.SettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless verification of Kerr-Schild relativistic invariants, UI toast lifecycle,
 * GLSL shader classpath integrity, and JetBrains Mono MTSDF atlas binary structure.
 */
final class RelativisticPhysicsTest {

    private static final String[] SHADER_RESOURCES = {
        "/shaders/common/fullscreen.vert",
        "/shaders/bake/noise_volume.frag",
        "/shaders/bake/skybox_milkyway.frag",
        "/shaders/bake/skybox_nebula.frag",
        "/shaders/simulation/kerr_blackhole.frag",
        "/shaders/simulation/binary_merger.frag",
        "/shaders/simulation/morris_thorne_wormhole.frag",
        "/shaders/simulation/tidal_hydro_splat.comp",
        "/shaders/simulation/tidal_disruption.comp",
        "/shaders/simulation/tidal_disruption.vert",
        "/shaders/simulation/tidal_disruption.frag",
        "/shaders/post/taa_resolve.frag",
        "/shaders/post/bloom_downsample.frag",
        "/shaders/post/bloom_upsample.frag",
        "/shaders/post/anamorphic_blur.frag",
        "/shaders/post/tonemap_composite.frag",
        "/shaders/ui/hud.vert",
        "/shaders/ui/hud.frag"
    };

    @Test
    void schwarzschildLimitMatchesExactAnalyticalRadii() {
        SimulationState state = new SimulationState();
        state.kerrSpinEnabled = false;
        state.spinParam = 0.98f;

        assertEquals(0.0f, state.effectiveSpin(), 1e-6f);
        assertEquals(2.0, state.outerHorizonRadius(), 1e-9);
        assertEquals(0.0, state.innerHorizonRadius(), 1e-9);
        assertEquals(6.0, state.progradeIscoRadius(), 1e-9);
    }

    @Test
    void thorneLimitKerrHorizonsAndBardeenIscoMatchTheory() {
        SimulationState state = new SimulationState();
        state.kerrSpinEnabled = true;
        state.spinParam = 0.998f;

        double rPlus = state.outerHorizonRadius();
        double rMinus = state.innerHorizonRadius();
        double rIsco = state.progradeIscoRadius();

        // Exact Kerr horizons: r_+ + r_- = 2M and r_+ * r_- = a^2
        assertEquals(2.0, rPlus + rMinus, 1e-9);
        assertEquals(0.998 * 0.998, rPlus * rMinus, 1e-6);
        assertTrue(rPlus > 1.06 && rPlus < 1.07, "r_+ for a*=0.998 should be ~1.0632M");
        // Bardeen-Press-Teukolsky prograde ISCO at Thorne limit a*=0.998 is ~1.23697M
        assertEquals(1.23697, rIsco, 1e-4);
        assertTrue(rIsco > rPlus, "Prograde ISCO must lie outside outer event horizon");
    }

    @Test
    void toastLifecycleFadesInHoldsAndExpiresCleanly() {
        SimulationState state = new SimulationState();
        StringBuilder scratch = new StringBuilder(64);

        state.showToast("TEMPORAL AA: ON");
        assertEquals(0.0f, state.readActiveToast(scratch), 1e-5f);

        state.tickUiTimers(0.11);
        float fadeIn = state.readActiveToast(scratch);
        assertTrue(fadeIn > 0.4f && fadeIn < 0.6f);
        assertEquals("TEMPORAL AA: ON", scratch.toString());

        state.tickUiTimers(0.60);
        assertEquals(1.0f, state.readActiveToast(scratch), 1e-5f);

        state.tickUiTimers(2.00);
        assertEquals(0.0f, state.readActiveToast(scratch), 1e-5f);
        assertEquals(0, scratch.length());
    }

    @Test
    void allShaderResourcesExistWithValidGlslVersionHeaders() throws IOException {
        for (String path : SHADER_RESOURCES) {
            try (InputStream is = RelativisticPhysicsTest.class.getResourceAsStream(path)) {
                assertNotNull(is, "Missing shader resource: " + path);
                String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(src.startsWith("#version 330 core") || src.startsWith("#version 430 core"),
                        "Unexpected GLSL version header in " + path);
                assertTrue(src.contains("void main()"), "Missing main() entry point in " + path);
            }
        }
    }

    @Test
    void mtsdfFontAtlasHeaderAndCompressedPayloadAreValid() throws IOException {
        String path = "/fonts/jetbrains_mono_mtsdf.bin";
        try (InputStream raw = RelativisticPhysicsTest.class.getResourceAsStream(path)) {
            assertNotNull(raw, "Missing MTSDF font atlas: " + path);
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(raw))) {
                int magic = dis.readInt();
                assertEquals(0x4D545344, magic, "Invalid MTSDF magic header");
                int atlasW = dis.readUnsignedShort();
                int atlasH = dis.readUnsignedShort();
                int cols = dis.readUnsignedShort();
                int rows = dis.readUnsignedShort();
                int cellW = dis.readUnsignedShort();
                int cellH = dis.readUnsignedShort();
                float pxRange = dis.readFloat();
                float capTopNorm = dis.readFloat();
                float capHeightNorm = dis.readFloat();
                float advNorm = dis.readFloat();

                assertEquals(cols * cellW, atlasW);
                assertEquals(rows * cellH, atlasH);
                assertTrue(cols * rows >= 97, "Atlas must hold at least 97 glyphs");
                assertTrue(pxRange >= 2.0f && pxRange <= 16.0f);
                assertTrue(capTopNorm > 0.05f && capTopNorm < 0.5f);
                assertTrue(capHeightNorm > 0.3f && capHeightNorm < 0.8f);
                assertTrue(advNorm > 0.4f && advNorm < 0.9f);

                int expectedBytes = atlasW * atlasH * 4;
                try (InflaterInputStream iis = new InflaterInputStream(dis)) {
                    byte[] payload = iis.readAllBytes();
                    assertEquals(expectedBytes, payload.length, "Decompressed MTSDF RGBA8 byte count mismatch");
                }
            }
        }
    }

    @Test
    void binaryMergerPetersMathewsInspiralDecaysMonotonicallyAndFormsPhysicalRemnant() {
        BinaryMergerPhysics binary = new BinaryMergerPhysics();
        binary.reset(1.0f); // Equal-mass binary (q = 1.0, eta = 0.25)

        assertEquals(BinaryMergerPhysics.Phase.INSPIRAL, binary.phase());
        assertEquals(0.5f, binary.mass1, 1e-5f);
        assertEquals(0.5f, binary.mass2, 1e-5f);
        // NR equal-mass non-spinning remnant: M_f ~= 0.9516, a_f ~= 0.6870 (Hofmann-Barausse-Rezzolla 2016)
        assertTrue(binary.remnantMass < 1.0f && binary.remnantMass > 0.94f,
                "Radiated gravitational wave energy must reduce final remnant mass");
        assertEquals(0.9516f, binary.remnantMass, 2e-3f);
        assertTrue(binary.remnantSpin > 0.0f && binary.remnantSpin < 1.0f,
                "Final Kerr remnant spin must satisfy 0 < a_f < 1");
        assertEquals(0.6865f, binary.remnantSpin, 2e-3f);

        float prevSep = binary.currentSeparation;
        float prevFreq = binary.gwFreqHz;
        boolean sawPlunge = false;
        boolean sawMerger = false;
        boolean sawRingdown = false;

        for (int i = 0; i < 3000; i++) {
            binary.step(1.0 / 60.0, 1.0f, 1.0f, false);
            BinaryMergerPhysics.Phase ph = binary.phase();
            if (ph == BinaryMergerPhysics.Phase.INSPIRAL || ph == BinaryMergerPhysics.Phase.PLUNGE) {
                assertTrue(binary.currentSeparation <= prevSep + 1e-5f,
                        "Peters-Mathews separation must decay monotonically during inspiral/plunge");
                assertTrue(binary.gwFreqHz >= prevFreq - 1e-3f,
                        "GW chirp frequency must rise monotonically during inspiral/plunge");
                prevSep = binary.currentSeparation;
                prevFreq = binary.gwFreqHz;
                if (ph == BinaryMergerPhysics.Phase.PLUNGE) {
                    sawPlunge = true;
                }
            } else if (ph == BinaryMergerPhysics.Phase.MERGER) {
                sawMerger = true;
            } else if (ph == BinaryMergerPhysics.Phase.RINGDOWN) {
                sawRingdown = true;
                break;
            }
        }

        assertTrue(sawPlunge, "Binary must pass through PLUNGE phase");
        assertTrue(sawMerger, "Binary must pass through MERGER phase");
        assertTrue(sawRingdown, "Binary must transition cleanly into QNM RINGDOWN");

        // Verify QNM ringdown envelope decays exponentially with finite waveform samples
        float envStart = binary.ringdownEnv;
        for (int i = 0; i < 60; i++) {
            binary.step(1.0 / 60.0, 1.0f, 1.0f, false);
        }
        float envMid = binary.ringdownEnv;
        for (int i = 0; i < 60; i++) {
            binary.step(1.0 / 60.0, 1.0f, 1.0f, false);
        }
        float envLate = binary.ringdownEnv;
        assertTrue(envStart > envMid && envMid > envLate && envLate > 0.0f,
                "QNM ringdown envelope must decay monotonically");

        // Verify rolling 160-sample LIGO strain buffer contains finite, non-zero waveform data
        float[] hist = binary.strainHistory();
        assertEquals(BinaryMergerPhysics.STRAIN_HISTORY_LEN, hist.length);
        float maxAbs = 0.0f;
        for (float h : hist) {
            assertTrue(Float.isFinite(h), "Strain buffer sample must be finite");
            maxAbs = Math.max(maxAbs, Math.abs(h));
        }
        assertTrue(maxAbs > 0.2f, "Rolling LIGO strain buffer should capture active inspiral/merger waveform");
    }

    @Test
    void globalTimelapseBoundsPresetSteppingAndPauseIndependenceWorkCleanly() {
        SimulationState state = new SimulationState();
        assertEquals(1.0f, state.timeScale, 1e-5f);
        assertEquals(SimulationState.HUD_CLEAN, state.hudMode);

        // Bounds clamping
        state.setTimeScale(0.001f);
        assertEquals(SimulationState.MIN_TIME_SCALE, state.timeScale, 1e-5f);
        state.setTimeScale(999.0f);
        assertEquals(SimulationState.MAX_TIME_SCALE, state.timeScale, 1e-5f);

        // Discrete preset stepping up and down
        state.setTimeScale(1.0f);
        state.stepTimeScaleUp();
        assertEquals(2.0f, state.timeScale, 1e-5f);
        state.stepTimeScaleUp();
        assertEquals(5.0f, state.timeScale, 1e-5f);
        state.stepTimeScaleDown();
        assertEquals(2.0f, state.timeScale, 1e-5f);

        // Pause must zero simDt while strictly preserving chosen timeScale
        state.setTimeScale(10.0f);
        assertEquals(0.10, state.effectiveSimDt(0.01), 1e-6);
        state.paused = true;
        assertEquals(0.0, state.effectiveSimDt(0.01), 1e-9);
        assertEquals(10.0f, state.timeScale, 1e-5f, "Pausing must not reset chosen timeScale");

        // 3-state HUD visibility cycle: CLEAN -> COMPACT -> FULL -> CLEAN
        state.cycleHudMode();
        assertEquals(SimulationState.HUD_COMPACT, state.hudMode);
        state.cycleHudMode();
        assertEquals(SimulationState.HUD_FULL, state.hudMode);
        assertTrue(state.showUi);
        state.cycleHudMode();
        assertEquals(SimulationState.HUD_CLEAN, state.hudMode);
        assertFalse(state.showUi);
    }

    @Test
    void tdeArchetypesBetaPeriapsisAndTimelapseSubstepClampingArePhysicallyValid() {
        double horizonR = 1.20;
        assertEquals(4, StellarArchetype.count());

        for (int i = 0; i < StellarArchetype.count(); i++) {
            StellarArchetype arch = StellarArchetype.fromIndex(i);
            assertTrue(arch.photosphereTempK >= 3000f && arch.coreTempK > arch.photosphereTempK,
                    arch.displayName + " core must be hotter than photosphere");
            assertTrue(arch.starRadius > 0.3f && arch.tidalRadius > arch.starRadius,
                    arch.displayName + " tidal radius must exceed stellar radius");

            double rpLowBeta = arch.computePeriapsis(0.75, horizonR);
            double rpMidBeta = arch.computePeriapsis(1.50, horizonR);
            double rpHighBeta = arch.computePeriapsis(3.80, horizonR);

            assertTrue(rpLowBeta > rpMidBeta && rpMidBeta >= rpHighBeta,
                    "Higher penetration factor beta = r_t / r_p must lower periapsis radius");
            assertTrue(rpHighBeta >= horizonR * 1.049,
                    "Periapsis must remain safely outside the outer event horizon r_+");
        }

        // Verify Red Supergiant has larger tidal radius than compact White Dwarf
        assertTrue(StellarArchetype.RED_SUPERGIANT.tidalRadius > StellarArchetype.WHITE_DWARF.tidalRadius);

        // Verify physics-safe compute substep clamping across 0.05x .. 25x timelapse
        assertEquals(0, StellarArchetype.computeSubstepCount(0.0));
        assertEquals(1, StellarArchetype.computeSubstepCount(0.008));
        assertTrue(StellarArchetype.computeSubstepDt(0.008) <= StellarArchetype.MAX_TDE_SUBSTEP_DT + 1e-9);

        // 25x timelapse at 60 FPS (0.417s) executes all 27 substeps without dropping simulation time!
        int steps25x = StellarArchetype.computeSubstepCount(0.417);
        double subDt25x = StellarArchetype.computeSubstepDt(0.417);
        assertEquals(27, steps25x);
        assertEquals(0.417, steps25x * subDt25x, 1e-6, "25x timelapse must preserve 100% of simulated time");
        assertTrue(subDt25x <= StellarArchetype.MAX_TDE_SUBSTEP_DT + 1e-9);

        // Extreme lag spike (1.0s) safely clamps at MAX_TDE_SUBSTEPS_PER_FRAME
        assertEquals(StellarArchetype.MAX_TDE_SUBSTEPS_PER_FRAME, StellarArchetype.computeSubstepCount(1.0));
        assertTrue(StellarArchetype.computeSubstepDt(1.0) <= StellarArchetype.MAX_TDE_SUBSTEP_DT + 1e-9);

        // Verify Binary Merger trajectory invariance between 1x and 25x over identical simulated time (2.0s)
        BinaryMergerPhysics slow = new BinaryMergerPhysics();
        BinaryMergerPhysics fast = new BinaryMergerPhysics();
        slow.reset(0.65f);
        fast.reset(0.65f);
        for (int i = 0; i < 120; i++) {
            slow.step(1.0 / 60.0, 0.65f, 1.0f, false);
        }
        for (int i = 0; i < 12; i++) {
            fast.step(1.0 / 60.0, 0.65f, 10.0f, false);
        }
        assertEquals(slow.currentSeparation, fast.currentSeparation, 1e-3f,
                "Fixed-substepped Binary Merger separation must match between 1x and 10x timelapse");
    }

    @Test
    void settingsStoreRoundTripsAndWindowIconBinaryIsValid(@TempDir Path tempDir) throws IOException {
        Path cfg = tempDir.resolve("settings.properties");
        SimulationState saved = new SimulationState();
        saved.vsync = false;
        saved.audioEnabled = false;
        saved.audioVolume = 0.42f;
        saved.bloomStrength = 0.55f;
        saved.exposure = 1.45f;
        saved.showReticle = true;
        saved.setHudMode(SimulationState.HUD_COMPACT);
        SettingsStore.save(cfg, saved);

        SimulationState loaded = new SimulationState();
        SettingsStore.load(cfg, loaded);
        assertFalse(loaded.vsync);
        assertFalse(loaded.audioEnabled);
        assertEquals(0.42f, loaded.audioVolume, 1e-5f);
        assertEquals(0.55f, loaded.bloomStrength, 1e-5f);
        assertEquals(1.45f, loaded.exposure, 1e-5f);
        assertTrue(loaded.showReticle);
        assertEquals(SimulationState.HUD_COMPACT, loaded.hudMode);

        // Verify EHT & reticle flags clear on resetDefaults()
        loaded.ehtPolarization = true;
        loaded.ehtRadioPalette = true;
        loaded.resetDefaults();
        assertFalse(loaded.ehtPolarization);
        assertFalse(loaded.ehtRadioPalette);
        assertFalse(loaded.showReticle);

        // Verify bundled 64x64 compressed GLFW window icon integrity (zero java.desktop)
        try (InputStream raw = RelativisticPhysicsTest.class.getResourceAsStream("/icons/blackhole_64.bin")) {
            assertNotNull(raw, "Missing bundled window icon resource /icons/blackhole_64.bin");
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(raw))) {
                assertEquals(0x42484943, dis.readInt(), "Invalid BHIC window icon magic");
                int w = dis.readInt();
                int h = dis.readInt();
                assertEquals(64, w);
                assertEquals(64, h);
                try (InflaterInputStream iis = new InflaterInputStream(dis)) {
                    byte[] rgba = iis.readAllBytes();
                    assertEquals(64 * 64 * 4, rgba.length, "Decompressed RGBA icon size mismatch");
                }
            }
        }
    }
}
