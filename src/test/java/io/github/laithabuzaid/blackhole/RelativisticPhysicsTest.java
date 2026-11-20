package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.SimulationState;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        "/shaders/simulation/morris_thorne_wormhole.frag",
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
}
