package io.github.laithabuzaid.blackhole;

import io.github.laithabuzaid.blackhole.core.SimulationState;
import io.github.laithabuzaid.blackhole.core.StellarArchetype;
import io.github.laithabuzaid.blackhole.math.PlanckBlackbody;
import io.github.laithabuzaid.blackhole.math.TdeInitialConditions;
import io.github.laithabuzaid.blackhole.util.GwReferenceData;
import io.github.laithabuzaid.blackhole.util.PngWriter;
import io.github.laithabuzaid.blackhole.util.SettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated validation for PNG encoding, settings fuzz resilience, deterministic TDE initial
 * conditions, CIE 1931 Planckian blackbody + IEC 61966-2-1 sRGB, GW150914 reference data,
 * and static GLSL shader uniform contracts (zero dead/unreferenced uniforms).
 */
final class UtilityAndShaderContractTest {

    @Test
    void pngWriterEmitsValidCrcVerifiedFlippedRgbPng(@TempDir Path tmp) throws IOException {
        int w = 4, h = 3;
        byte[] bottomUp = new byte[w * h * 3];
        // Bottom row (y=0 in GL -> bottom row y=2 in PNG) = pure red (255, 0, 0)
        // Top row (y=2 in GL -> top row y=0 in PNG) = pure blue (0, 0, 255)
        for (int x = 0; x < w; x++) {
            bottomUp[x * 3] = (byte) 255;
            int topOff = (2 * w + x) * 3;
            bottomUp[topOff + 2] = (byte) 255;
        }
        Path out = tmp.resolve("sub/frame.png");
        PngWriter.writeRgbPng(out, w, h, bottomUp);

        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes.length > 50);
        // Verify 8-byte PNG signature
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        for (int i = 0; i < 8; i++) {
            assertEquals(sig[i], bytes[i], "PNG signature byte " + i);
        }

        // Parse & CRC-verify IHDR, IDAT, IEND chunks
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes, 8, bytes.length - 8))) {
            boolean sawIhdr = false, sawIdat = false, sawIend = false;
            byte[] idatBytes = null;
            while (dis.available() > 0) {
                int len = dis.readInt();
                byte[] type = dis.readNBytes(4);
                byte[] payload = dis.readNBytes(len);
                int crc = dis.readInt();

                CRC32 calc = new CRC32();
                calc.update(type);
                calc.update(payload);
                assertEquals((int) calc.getValue(), crc, "Chunk CRC mismatch");

                String tag = new String(type, StandardCharsets.US_ASCII);
                switch (tag) {
                    case "IHDR" -> {
                        sawIhdr = true;
                        assertEquals(13, len);
                        int iw = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                        int ih = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                                | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
                        assertEquals(w, iw);
                        assertEquals(h, ih);
                        assertEquals(8, payload[8]);
                        assertEquals(2, payload[9]); // Truecolor RGB
                    }
                    case "IDAT" -> {
                        sawIdat = true;
                        idatBytes = payload;
                    }
                    case "IEND" -> sawIend = true;
                    default -> {
                    }
                }
            }
            assertTrue(sawIhdr && sawIdat && sawIend, "PNG must contain IHDR, IDAT, IEND");
            // Inflate IDAT and verify vertical flip: PNG row 0 must be blue, row 2 must be red
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(idatBytes))) {
                byte[] raw = iis.readAllBytes();
                int rowBytes = 1 + w * 3;
                assertEquals(h * rowBytes, raw.length);
                assertEquals(0, raw[0]); // filter byte None
                assertEquals(0, raw[1] & 0xFF);
                assertEquals(0, raw[2] & 0xFF);
                assertEquals(255, raw[3] & 0xFF); // top row blue

                int botRow = 2 * rowBytes;
                assertEquals(255, raw[botRow + 1] & 0xFF); // bottom row red
                assertEquals(0, raw[botRow + 3] & 0xFF);
            }
        }

        // Invalid short buffer must throw
        assertThrows(IllegalArgumentException.class,
                () -> PngWriter.writeRgbPng(tmp.resolve("bad.png"), 4, 4, new byte[10]));
    }

    @Test
    void settingsStoreResistsCorruptNanAndOutOfRangeValues(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("corrupt.properties");
        Files.writeString(cfg, """
                vsync=maybe
                audioEnabled=true
                audioVolume=NaN
                bloomStrength=9999.0
                exposure=-50.0
                anamorphicStrength=Infinity
                integratorMode=42
                hudMode=-99
                tdeHydroEnabled=false
                """);
        SimulationState state = new SimulationState();
        float defaultVol = state.audioVolume;
        SettingsStore.load(cfg, state);

        assertEquals(defaultVol, state.audioVolume, 1e-6f, "NaN volume must fall back to default");
        assertEquals(SimulationState.MAX_BLOOM, state.bloomStrength, 1e-6f, "Out-of-range bloom must clamp");
        assertEquals(SimulationState.MIN_EXPOSURE, state.exposure, 1e-6f, "Negative exposure must clamp");
        assertTrue(Float.isFinite(state.anamorphicStrength), "Infinity must fall back to finite value");
        assertEquals(SimulationState.INTEGRATOR_RK4, state.integratorMode, "Invalid integrator must default to RK4");
        assertEquals(SimulationState.HUD_CLEAN, state.hudMode, "Negative HUD mode must clamp to HUD_CLEAN");
        assertFalse(state.tdeHydroEnabled);
    }

    @Test
    void tdeInitialConditionsAreDeterministicAndWhiteDwarfCoreIsNonHollow() {
        StellarArchetype wd = StellarArchetype.fromIndex(3); // White Dwarf
        assertTrue(wd.densityExponent > 1.0f / 3.0f,
                "White Dwarf density exponent must exceed 1/3 to avoid hollow core");

        float[] runA = new float[1024 * 8];
        float[] runB = new float[1024 * 8];
        TdeInitialConditions.generateStar(wd, 1.8f, 22.0f, 0.95f, 0, 1024, 42L, FloatBuffer.wrap(runA));
        TdeInitialConditions.generateStar(wd, 1.8f, 22.0f, 0.95f, 0, 1024, 42L, FloatBuffer.wrap(runB));
        assertArrayEquals(runA, runB, "Same seed must produce bit-identical TDE star particles");

        float[] runC = new float[1024 * 8];
        TdeInitialConditions.generateStar(wd, 1.8f, 22.0f, 0.95f, 0, 1024, 99L, FloatBuffer.wrap(runC));
        boolean differs = false;
        for (int i = 0; i < runA.length; i++) {
            if (runA[i] != runC[i]) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "Distinct seeds must produce distinct particle realizations");
    }

    @Test
    void planckBlackbodyAndSrgbTransferArePhysicallyConsistent() {
        // Exact IEC 61966-2-1 sRGB OETF/EOTF round-trip across [0..1]
        for (int i = 0; i <= 100; i++) {
            double lin = i / 100.0;
            double enc = PlanckBlackbody.linearToSrgb(lin);
            double dec = PlanckBlackbody.srgbToLinear(enc);
            assertEquals(lin, dec, 1e-6, "sRGB EOTF(OETF(x)) round-trip at x=" + lin);
        }

        // Cool blackbody (2000K) is red-dominated; hot blackbody (25000K) is blue-dominated
        double[] cool = new double[4];
        double[] solar = new double[4];
        double[] hot = new double[4];
        PlanckBlackbody.blackbodyLinearRgb(2000.0, cool);
        PlanckBlackbody.blackbodyLinearRgb(5778.0, solar);
        PlanckBlackbody.blackbodyLinearRgb(25000.0, hot);
        assertTrue(cool[0] > cool[1] && cool[1] > cool[2], "2000K must be red-dominated");
        assertTrue(hot[2] > hot[1] && hot[1] > hot[0], "25000K must be blue-dominated");
        assertTrue(solar[0] > 0.7 && solar[1] > 0.7 && solar[2] > 0.6, "5778K solar white must be balanced");
    }

    @Test
    void bundledGw150914ReferenceDatasetLoadsWithPeakMergerStrain() {
        GwReferenceData gw = GwReferenceData.loadBundledGw150914();
        assertTrue(gw.sampleCount() >= 20, "Bundled GW150914 dataset should have >= 20 samples");
        assertEquals(251.0, gw.peakFrequencyHz(), 35.0, "GW150914 peak frequency should be ~251 Hz");
        float maxStrain = 0.0f;
        int n = gw.sampleCount();
        for (int i = 0; i < n; i++) {
            maxStrain = Math.max(maxStrain, Math.abs(gw.sampleStrainAtNormalized(i / (float) (n - 1))));
        }
        assertEquals(1.0f, maxStrain, 1e-5f, "Normalized strain should reach unit peak");
    }

    @Test
    void allDeclaredShaderUniformsAreActivelyReferencedInGlsl() throws IOException {
        List<String> shaders = List.of(
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
        );
        Pattern declPattern = Pattern.compile("^\\s*uniform\\s+[A-Za-z0-9_]+\\s+([A-Za-z0-9_]+)\\s*(\\[[^\\]]*\\])?\\s*;",
                Pattern.MULTILINE);

        for (String res : shaders) {
            String src;
            try (InputStream is = UtilityAndShaderContractTest.class.getResourceAsStream(res)) {
                assertNotNull(is, "Missing shader resource: " + res);
                src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            // Strip single-line comments so commented-out mentions don't count as live usage
            String noComments = src.replaceAll("//.*", "");
            Matcher m = declPattern.matcher(noComments);
            while (m.find()) {
                String name = m.group(1);
                String afterDecl = noComments.substring(m.end());
                Pattern usePattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
                assertTrue(usePattern.matcher(afterDecl).find(),
                        "Dead uniform '" + name + "' declared in " + res + " is never referenced in shader body");
            }
        }
    }
}
