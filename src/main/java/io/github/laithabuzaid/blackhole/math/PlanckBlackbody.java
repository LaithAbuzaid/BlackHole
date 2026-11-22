package io.github.laithabuzaid.blackhole.math;

/**
 * Physically based CIE 1931 2-degree spectral Planckian blackbody integrator and exact
 * IEC 61966-2-1 sRGB transfer functions.
 *
 * <p>Replaces the empirical Tanner Helland color-temperature fit with numerical integration of
 * Planck's spectral radiance {@code B_lambda(T) = (2 h c^2 / lambda^5) / (exp(h c / (lambda k_B T)) - 1)}
 * across {@code 380..780 nm} against the CIE 1931 2-degree color-matching functions
 * (Wyman, Sloan &amp; Shirley 2013 analytic piecewise-Gaussian fit, max error {@code < 0.002}),
 * followed by linear Rec.709/sRGB matrix transformation and gamut-preserving normalization.</p>
 */
public final class PlanckBlackbody {

    private static final double HC_OVER_KB_NM_K = 1.438776877e7; // h * c / k_B in nm * K

    private PlanckBlackbody() {
    }

    /** Piecewise Gaussian fit to CIE 1931 2-degree x-bar(lambda) (Wyman, Sloan &amp; Shirley 2013). */
    public static double cieXBar(double lambdaNm) {
        double t1 = (lambdaNm - 442.0) * (lambdaNm < 442.0 ? 0.0624 : 0.0374);
        double t2 = (lambdaNm - 599.8) * (lambdaNm < 599.8 ? 0.0264 : 0.0323);
        double t3 = (lambdaNm - 501.1) * (lambdaNm < 501.1 ? 0.0490 : 0.0382);
        return 0.362 * Math.exp(-0.5 * t1 * t1)
                + 1.056 * Math.exp(-0.5 * t2 * t2)
                - 0.065 * Math.exp(-0.5 * t3 * t3);
    }

    /** Piecewise Gaussian fit to CIE 1931 2-degree y-bar(lambda). */
    public static double cieYBar(double lambdaNm) {
        double t1 = (lambdaNm - 568.8) * (lambdaNm < 568.8 ? 0.0213 : 0.0247);
        double t2 = (lambdaNm - 530.9) * (lambdaNm < 530.9 ? 0.0613 : 0.0322);
        return 0.821 * Math.exp(-0.5 * t1 * t1)
                + 0.286 * Math.exp(-0.5 * t2 * t2);
    }

    /** Piecewise Gaussian fit to CIE 1931 2-degree z-bar(lambda). */
    public static double cieZBar(double lambdaNm) {
        double t1 = (lambdaNm - 437.0) * (lambdaNm < 437.0 ? 0.0845 : 0.0278);
        double t2 = (lambdaNm - 459.0) * (lambdaNm < 459.0 ? 0.0385 : 0.0725);
        return 1.217 * Math.exp(-0.5 * t1 * t1)
                + 0.681 * Math.exp(-0.5 * t2 * t2);
    }

    /**
     * Evaluates normalized linear Rec.709/sRGB Planckian chromaticity {@code [r, g, b]}
     * (peak channel normalized to 1.0, strictly non-negative) and stores relative visible-band
     * luminance attenuation factor for cool sub-visible temperatures in {@code outRgba[3]} if length >= 4.
     */
    public static void blackbodyLinearRgb(double tempK, double[] outRgb) {
        double t = Math.max(400.0, Math.min(200_000.0, tempK));
        double xSum = 0.0, ySum = 0.0, zSum = 0.0;
        // Shift exponent by minimum exponent across the visible band to prevent underflow at low T
        double minExp = HC_OVER_KB_NM_K / (780.0 * t);

        int steps = 80; // 5 nm steps over 380..780 nm (Simpson / trapezoidal integration)
        double dLambda = 400.0 / steps;
        for (int i = 0; i <= steps; i++) {
            double lambda = 380.0 + i * dLambda;
            double w = (i == 0 || i == steps) ? 0.5 : 1.0;
            double u = lambda / 550.0;
            double invU5 = 1.0 / (u * u * u * u * u);
            double exponent = HC_OVER_KB_NM_K / (lambda * t);
            double radiance;
            if (exponent > 50.0) {
                // Wien regime: shift by minExp so ratios stay finite even at 500 K
                radiance = invU5 * Math.exp(-(exponent - minExp));
            } else {
                double shifted = Math.exp(-minExp) / Math.expm1(exponent);
                radiance = invU5 * shifted;
            }
            double contrib = w * radiance;
            xSum += contrib * cieXBar(lambda);
            ySum += contrib * cieYBar(lambda);
            zSum += contrib * cieZBar(lambda);
        }

        // Standard CIE XYZ -> Linear Rec.709/sRGB (D65) matrix
        double r =  3.2404542 * xSum - 1.5371385 * ySum - 0.4985314 * zSum;
        double g = -0.9692660 * xSum + 1.8760108 * ySum + 0.0415560 * zSum;
        double b =  0.0556434 * xSum - 0.2040259 * ySum + 1.0572252 * zSum;

        // Soft desaturation clamp for out-of-sRGB-gamut deep-red / extreme-blue tails
        r = Math.max(0.0, r);
        g = Math.max(0.0, g);
        b = Math.max(0.0, b);
        double maxC = Math.max(r, Math.max(g, b));
        if (maxC > 1e-20) {
            r /= maxC;
            g /= maxC;
            b /= maxC;
        } else {
            r = 1.0;
            g = 0.0;
            b = 0.0;
        }

        outRgb[0] = r;
        outRgb[1] = g;
        outRgb[2] = b;
        if (outRgb.length >= 4) {
            // Smooth physical Wien visible-band roll-off below ~1600 K so heavily redshifted plasma dims into IR
            double visFactor = 1.0 / (1.0 + Math.exp((1350.0 - t) / 260.0));
            outRgb[3] = Math.max(0.02, Math.min(1.0, visFactor));
        }
    }

    /**
     * Builds a 1D {@code GL_RGBA16F}-ready linear RGB + Wien IR-rolloff LUT across
     * {@code T(u) = minTempK + u^2 * (maxTempK - minTempK)} (quadratic spacing concentrates precision
     * in the 900 K .. 12,000 K band).
     */
    public static float[] buildLutRgba(int width, double minTempK, double maxTempK) {
        float[] lut = new float[width * 4];
        double[] rgba = new double[4];
        double span = maxTempK - minTempK;
        for (int i = 0; i < width; i++) {
            double u = i / (double) (width - 1);
            double tempK = minTempK + u * u * span;
            blackbodyLinearRgb(tempK, rgba);
            int idx = i * 4;
            lut[idx]     = (float) rgba[0];
            lut[idx + 1] = (float) rgba[1];
            lut[idx + 2] = (float) rgba[2];
            lut[idx + 3] = (float) rgba[3];
        }
        return lut;
    }

    /** Exact IEC 61966-2-1 linear-to-sRGB transfer function (OETF). */
    public static double linearToSrgb(double linear) {
        double c = Math.max(0.0, Math.min(1.0, linear));
        return (c <= 0.0031308) ? (12.92 * c) : (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }

    /** Exact IEC 61966-2-1 sRGB-to-linear transfer function (EOTF). */
    public static double srgbToLinear(double srgb) {
        double c = Math.max(0.0, Math.min(1.0, srgb));
        return (c <= 0.04045) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
