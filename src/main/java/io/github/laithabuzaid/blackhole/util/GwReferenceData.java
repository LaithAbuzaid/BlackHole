package io.github.laithabuzaid.blackhole.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency loader for open LIGO/GWOSC gravitational-wave reference strain CSV tables.
 *
 * <p>Supports both the bundled public-domain GW150914 reference summary ({@code /data/gw150914_reference.csv})
 * and external user-supplied CSV files via {@code --gw-data <path>} (2-column {@code t, h} or
 * 3-column {@code t, h, f_hz}, ignoring {@code #} comment lines).</p>
 */
public final class GwReferenceData {

    public static final String BUNDLED_GW150914_RESOURCE = "/data/gw150914_reference.csv";

    // Published Abbott et al. (2016) / GWTC-1 GW150914 reference source values
    public static final double GW150914_MASS_RATIO = 30.6 / 35.6;
    public static final double GW150914_REMNANT_MASS_FRAC = 63.1 / 66.2; // ~0.9532
    public static final double GW150914_REMNANT_SPIN = 0.69;
    public static final double GW150914_RADIATED_FRAC = 3.1 / 66.2;      // ~0.0468
    public static final double GW150914_PEAK_FREQ_HZ = 251.0;

    private final String label;
    private final double[] timesSec;
    private final double[] strainNorm;
    private final double[] freqHz;

    private GwReferenceData(String label, double[] timesSec, double[] strainNorm, double[] freqHz) {
        this.label = label;
        this.timesSec = timesSec;
        this.strainNorm = strainNorm;
        this.freqHz = freqHz;
    }

    /** Loads the bundled GW150914 reference waveform table. */
    public static GwReferenceData loadBundledGw150914() {
        try (InputStream in = GwReferenceData.class.getResourceAsStream(BUNDLED_GW150914_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource: " + BUNDLED_GW150914_RESOURCE);
            }
            return parse("GW150914 (H1 Ref)", new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException("Failed loading bundled GW150914 reference", e);
        }
    }

    /** Loads an external GWOSC CSV file from disk. */
    public static GwReferenceData loadFromFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(path.getFileName().toString(), reader);
        }
    }

    private static GwReferenceData parse(String label, BufferedReader reader) throws IOException {
        List<double[]> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("[,\\s]+");
            if (parts.length < 2) {
                continue;
            }
            try {
                double t = Double.parseDouble(parts[0]);
                double h = Double.parseDouble(parts[1]);
                double f = (parts.length >= 3) ? Double.parseDouble(parts[2]) : 0.0;
                if (Double.isFinite(t) && Double.isFinite(h) && Double.isFinite(f)) {
                    rows.add(new double[]{t, h, f});
                }
            } catch (NumberFormatException ignored) {
                // Skip header rows
            }
        }
        if (rows.size() < 2) {
            throw new IOException("GW reference data has fewer than 2 valid rows: " + label);
        }
        int n = rows.size();
        double[] tArr = new double[n];
        double[] hArr = new double[n];
        double[] fArr = new double[n];
        double maxAbsH = 1e-12;
        for (int i = 0; i < n; i++) {
            tArr[i] = rows.get(i)[0];
            hArr[i] = rows.get(i)[1];
            fArr[i] = rows.get(i)[2];
            maxAbsH = Math.max(maxAbsH, Math.abs(hArr[i]));
        }
        // Normalize peak strain to [-1, 1] for direct visual/diagnostic overlay
        for (int i = 0; i < n; i++) {
            hArr[i] /= maxAbsH;
        }
        return new GwReferenceData(label, tArr, hArr, fArr);
    }

    public String label() {
        return label;
    }

    public int sampleCount() {
        return timesSec.length;
    }

    public double timeStartSec() {
        return timesSec[0];
    }

    public double timeEndSec() {
        return timesSec[timesSec.length - 1];
    }

    public double peakFrequencyHz() {
        double maxF = 0.0;
        for (double f : freqHz) {
            if (f > maxF) maxF = f;
        }
        return maxF;
    }

    /** Linearly interpolates normalized strain at relative time {@code tSec} (zero allocation). */
    public float sampleStrainAtNormalized(float u01) {
        int n = strainNorm.length;
        double pos = Math.max(0.0, Math.min(1.0, u01)) * (n - 1);
        int i0 = (int) pos;
        int i1 = Math.min(n - 1, i0 + 1);
        double frac = pos - i0;
        return (float) (strainNorm[i0] * (1.0 - frac) + strainNorm[i1] * frac);
    }

    /** Linearly interpolates reference frequency in Hz at normalized index {@code u01}. */
    public double sampleFrequencyHzAtNormalized(double u01) {
        int n = freqHz.length;
        double pos = Math.max(0.0, Math.min(1.0, u01)) * (n - 1);
        int i0 = (int) pos;
        int i1 = Math.min(n - 1, i0 + 1);
        double frac = pos - i0;
        return freqHz[i0] * (1.0 - frac) + freqHz[i1] * frac;
    }
}
