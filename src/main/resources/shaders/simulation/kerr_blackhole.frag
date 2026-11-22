#version 330 core
layout(location = 0) out vec4 FragColor;

uniform vec2 uResolution;
uniform float uTime;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform samplerCube uSkybox;
uniform sampler3D uNoiseVol;
uniform sampler2D uBlackbodyLut;
uniform float uSpinA;
uniform float uHorizonR;
uniform float uIscoR;
uniform float uDiskTempScale;
uniform float uDiskDensity;
uniform float uJetPower;
uniform float uTanHalfFov;
uniform float uCauchyFlash;
uniform float uSpaghettify;
uniform float uSingularityFade;
uniform float uAccretionSurge;
uniform int uDoppler;
uniform int uLensing;
uniform int uShowDisk;
uniform int uShowJets;
uniform int uFreeFall;
uniform int uShowGrid;
uniform int uOrderVis;
uniform int uEhtPolarization;
uniform int uEhtRadio;
#ifdef USE_DOPRI54
uniform float uIntegTol;       // Adaptive DOPRI5(4) local tolerance
#endif
uniform int uDiagMode;         // 0 = Off, 1 = |H| Null-Constraint Error, 2 = Integrator Step Density
uniform vec2 uJitter;
uniform int uFrameIndex;

// Geometric units: G = c = M = 1, r_s = 2M = 2.0, spin angular momentum J = (0, -a, 0)
const float PI = 3.14159265359;
const float M_BH = 1.0;
const float R_DISK_OUT = 24.0;
const int MAX_STEPS = 200;
const float SKY_HIT_DIST = 16384.0; // FP16-safe finite sky sentinel (max binary16 = 65504)

// ============================================================================
// SAFE MATH, CIE 1931 PLANCK BLACKBODY LUT & DIAGNOSTIC COLORMAP
// ============================================================================

vec3 safeNormalize(vec3 v, vec3 fallback) {
    float len2 = dot(v, v);
    return (len2 > 1e-12) ? (v * inversesqrt(len2)) : fallback;
}

float safeAtan2(float y, float x) {
    return (abs(x) + abs(y) > 1e-6) ? atan(y, x) : 0.0;
}

vec3 sanitizeHdr(vec3 c) {
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    c = mix(c, vec3(0.0), bad);
    return clamp(c, vec3(0.0), vec3(48.0));
}

// Hardware lookup into precomputed 512x1 CIE 1931 Planckian RGBA16F LUT (900K..38000K)
// Exact half-texel inset + alpha-channel physical Wien visible-band rolloff for cool plasma
vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    float u = (0.5 + sqrt(norm) * 511.0) * (1.0 / 512.0);
    vec4 bb = texture(uBlackbodyLut, vec2(u, 0.5));
    return bb.rgb * bb.a;
}

// Event Horizon Telescope (EHT 230 GHz VLBI) AFmhot brightness-temperature palette
vec3 sampleEhtRadioPalette(float tbNorm) {
    float t = clamp(tbNorm, 0.0, 1.0);
    vec3 c0 = vec3(0.015, 0.002, 0.000);
    vec3 c1 = vec3(0.420, 0.045, 0.004);
    vec3 c2 = vec3(0.980, 0.300, 0.018);
    vec3 c3 = vec3(1.180, 0.780, 0.140);
    vec3 c4 = vec3(1.450, 1.320, 0.960);
    if (t < 0.28) return mix(c0, c1, t / 0.28);
    if (t < 0.58) return mix(c1, c2, (t - 0.28) / 0.30);
    if (t < 0.84) return mix(c2, c3, (t - 0.58) / 0.26);
    return mix(c3, c4, (t - 0.84) / 0.16);
}

vec3 turboColormap(float t) {
    float x = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.11, 0.08, 0.34);
    vec3 c1 = vec3(0.09, 0.56, 0.78);
    vec3 c2 = vec3(0.55, 0.88, 0.24);
    vec3 c3 = vec3(0.98, 0.55, 0.09);
    vec3 c4 = vec3(0.84, 0.12, 0.14);
    if (x < 0.25) return mix(c0, c1, x * 4.0);
    if (x < 0.50) return mix(c1, c2, (x - 0.25) * 4.0);
    if (x < 0.75) return mix(c2, c3, (x - 0.50) * 4.0);
    return mix(c3, c4, (x - 0.75) * 4.0);
}

// Interleaved Gradient Noise (Jimenez 2014) for volumetric step dithering
float interleavedGradientNoise(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// ============================================================================
// EXACT KERR-SCHILD METRIC, HAMILTONIAN & INTEGRATORS (RK4 + DOPRI5(4))
// ============================================================================

// Exact spheroidal Kerr radius solving r^4 - (R^2 - a^2)r^2 - a^2 y^2 = 0
float kerrRadius(vec3 x, float a2) {
    float R2 = dot(x, x);
    float b = R2 - a2;
    float disc = max(0.0, b * b + 4.0 * a2 * x.y * x.y);
    float r2 = 0.5 * (b + sqrt(disc));
    return sqrt(max(1e-4, r2));
}

void kerrMetricHL(vec3 x, float a, float a2, out float r, out float H, out vec3 lVec) {
    r = kerrRadius(x, a2);
    float invR = 1.0 / r;
    float r2 = r * r;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    H = (M_BH * r2 * r) / D;
    float invS = 1.0 / max(1e-4, r2 + a2);
    lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y * invR,
        (r * x.z - a * x.x) * invS
    );
}

// Exact null Hamiltonian H_ham = 0.5 * g^{mu nu} p_mu p_nu (identically 0 along exact null geodesics)
float kerrHamiltonian(vec3 x, vec3 p, float pt, float a, float a2) {
    float r, H;
    vec3 lVec;
    kerrMetricHL(x, a, a2, r, H, lVec);
    float q = -pt + dot(lVec, p);
    return 0.5 * (-pt * pt + dot(p, p) - 2.0 * H * q * q);
}

// Exact analytic Hamiltonian derivatives: dx/dlambda = +dH/dp, dp/dlambda = -dH/dx
float hamiltonDeriv(vec3 x, vec3 p, float pt, float a, float a2,
                    out vec3 dx, out vec3 dp) {
    float r = kerrRadius(x, a2);
    float invR = 1.0 / r;
    float r2 = r * r;
    float invR2 = invR * invR;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    float invD = 1.0 / D;
    float H = (M_BH * r2 * r) * invD;
    float S = max(1e-4, r2 + a2);
    float invS = 1.0 / S;

    vec3 lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y * invR,
        (r * x.z - a * x.x) * invS
    );

    float lp = dot(lVec, p);
    float q = -pt + lp;
    float twoHq = 2.0 * H * q;

    dx = p - twoHq * lVec;

    vec3 gradR = (r * invD) * vec3(r2 * x.x, S * x.y, r2 * x.z);
    vec3 gradH = (r2 * (3.0 * a2 * x.y * x.y - r2 * r2) * gradR
                - vec3(0.0, 2.0 * a2 * r2 * r * x.y, 0.0)) * (invD * invD);

    float xpZp = x.x * p.x + x.z * p.z;
    float zpxXpz = x.z * p.x - x.x * p.z;
    vec3 directLP = vec3(
        (r * p.x - a * p.z) * invS,
        p.y * invR,
        (r * p.z + a * p.x) * invS
    );
    float dlpDr = ((a2 - r2) * xpZp - 2.0 * a * r * zpxXpz) * (invS * invS)
                - (x.y * p.y) * invR2;
    vec3 gradLP = directLP + dlpDr * gradR;

    dp = (q * q) * gradH + twoHq * gradLP;
    return r;
}

#ifndef USE_DOPRI54
// VGPR-optimized full 4th-order RK4 (accumulates incrementally, saving 12 VGPRs vs naive RK4
// and compiled separately from DOPRI5(4) so RK4 achieves full GPU warp occupancy)
float geodesicStepRk4(inout vec3 x, inout vec3 p, float h, float pt, float a, float a2,
                      inout vec3 dxNow, inout vec3 dpNow) {
    vec3 accX = dxNow;
    vec3 accP = dpNow;
    vec3 kx, kp;

    hamiltonDeriv(x + (0.5 * h) * dxNow, p + (0.5 * h) * dpNow, pt, a, a2, kx, kp);
    accX += 2.0 * kx;
    accP += 2.0 * kp;

    hamiltonDeriv(x + (0.5 * h) * kx, p + (0.5 * h) * kp, pt, a, a2, kx, kp);
    accX += 2.0 * kx;
    accP += 2.0 * kp;

    hamiltonDeriv(x + h * kx, p + h * kp, pt, a, a2, kx, kp);
    accX += kx;
    accP += kp;

    float h6 = h * (1.0 / 6.0);
    x += h6 * accX;
    p += h6 * accP;
    return hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
}
#else
// Embedded 7-stage FSAL Dormand-Prince 5(4) adaptive step with error control
float geodesicStepDopri54(inout vec3 x, inout vec3 p, inout float h, float hMin, float hMax,
                          float tol, float pt, float a, float a2,
                          inout vec3 dxNow, inout vec3 dpNow, out float hUsed) {
    vec3 k1x = dxNow;
    vec3 k1p = dpNow;
    float rOut = kerrRadius(x, a2);
    hUsed = h;

    for (int attempt = 0; attempt < 3; attempt++) {
        vec3 k2x, k2p, k3x, k3p, k4x, k4p, k5x, k5p, k6x, k6p, k7x, k7p;
        hamiltonDeriv(x + h * 0.2 * k1x,
                      p + h * 0.2 * k1p, pt, a, a2, k2x, k2p);
        hamiltonDeriv(x + h * (0.075 * k1x + 0.225 * k2x),
                      p + h * (0.075 * k1p + 0.225 * k2p), pt, a, a2, k3x, k3p);
        hamiltonDeriv(x + h * (0.97777778 * k1x - 3.73333333 * k2x + 3.55555556 * k3x),
                      p + h * (0.97777778 * k1p - 3.73333333 * k2p + 3.55555556 * k3p), pt, a, a2, k4x, k4p);
        hamiltonDeriv(x + h * (2.95259869 * k1x - 11.59579332 * k2x + 9.82289285 * k3x - 0.29080933 * k4x),
                      p + h * (2.95259869 * k1p - 11.59579332 * k2p + 9.82289285 * k3p - 0.29080933 * k4p), pt, a, a2, k5x, k5p);
        hamiltonDeriv(x + h * (2.84627525 * k1x - 10.75757576 * k2x + 8.90642272 * k3x + 0.27840909 * k4x - 0.27353130 * k5x),
                      p + h * (2.84627525 * k1p - 10.75757576 * k2p + 8.90642272 * k3p + 0.27840909 * k4p - 0.27353130 * k5p), pt, a, a2, k6x, k6p);

        vec3 xNext = x + h * (0.09114583 * k1x + 0.44923630 * k3x + 0.65104167 * k4x - 0.32237618 * k5x + 0.13095238 * k6x);
        vec3 pNext = p + h * (0.09114583 * k1p + 0.44923630 * k3p + 0.65104167 * k4p - 0.32237618 * k5p + 0.13095238 * k6p);

        rOut = hamiltonDeriv(xNext, pNext, pt, a, a2, k7x, k7p);

        vec3 errX = h * (0.00123264 * k1x - 0.00425277 * k3x + 0.03697917 * k4x - 0.05086379 * k5x + 0.04190476 * k6x - 0.025 * k7x);
        vec3 errP = h * (0.00123264 * k1p - 0.00425277 * k3p + 0.03697917 * k4p - 0.05086379 * k5p + 0.04190476 * k6p - 0.025 * k7p);

        float scaleX = tol * (1.0 + max(length(x), length(xNext)));
        float scaleP = tol * (1.0 + max(length(p), length(pNext)));
        float errRatio = max(length(errX) / max(1e-7, scaleX), length(errP) / max(1e-7, scaleP));

        if (errRatio <= 1.0 || h <= hMin * 1.05 || attempt == 2) {
            x = xNext;
            p = pNext;
            dxNow = k7x; // Exact FSAL reuse
            dpNow = k7p;
            hUsed = h;
            float grow = clamp(0.9 * pow(max(errRatio, 1e-4), -0.2), 0.35, 2.2);
            h = clamp(h * grow, hMin, hMax);
            return rOut;
        }
        float shrink = clamp(0.85 * pow(errRatio, -0.25), 0.25, 0.7);
        h = max(hMin, h * shrink);
    }
    return rOut;
}
#endif

// Newton-Raphson Cubic Hermite equatorial plane (y = 0) intersection
vec3 intersectEquatorialHermite(vec3 x0, vec3 dx0, vec3 x1, vec3 dx1, float h, out float tHit) {
    float y0 = x0.y;
    float y1 = x1.y;
    float m0 = dx0.y * h;
    float m1 = dx1.y * h;

    float t = clamp(abs(y0) / (abs(y0) + abs(y1) + 1e-8), 0.0, 1.0);
    for (int iter = 0; iter < 3; iter++) {
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
        float h10 = t3 - 2.0 * t2 + t;
        float h01 = -2.0 * t3 + 3.0 * t2;
        float h11 = t3 - t2;

        float f = h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
        float df = (6.0 * t2 - 6.0 * t) * y0
                 + (3.0 * t2 - 4.0 * t + 1.0) * m0
                 + (-6.0 * t2 + 6.0 * t) * y1
                 + (3.0 * t2 - 2.0 * t) * m1;
        if (abs(df) > 1e-7) {
            t = clamp(t - f / df, 0.0, 1.0);
        }
    }

    tHit = t;
    float t2 = t * t;
    float t3 = t2 * t;
    vec3 hit = (2.0 * t3 - 3.0 * t2 + 1.0) * x0
             + (t3 - 2.0 * t2 + t) * (dx0 * h)
             + (-2.0 * t3 + 3.0 * t2) * x1
             + (t3 - t2) * (dx1 * h);
    hit.y = 0.0;
    return hit;
}

// ============================================================================
// SKYBOX SAMPLING WITH FREE-FALL RELATIVISTIC SHIFT & OPTIONAL CELESTIAL GRID
// ============================================================================

vec3 sampleSkyWithGrid(vec3 dir, vec3 ddx, vec3 ddy, float skyShiftG) {
    vec3 sky = textureGrad(uSkybox, dir, ddx, ddy).rgb;

    // Physically motivated blackbody chromatic shift + intensity beaming for plunging Doran observer
    if (uFreeFall == 1 && abs(skyShiftG - 1.0) > 0.02) {
        vec3 shiftedChroma = sampleBlackbody(6500.0 * skyShiftG);
        vec3 baseChroma = max(vec3(0.05), sampleBlackbody(6500.0));
        vec3 chromaRatio = clamp(shiftedChroma / baseChroma, vec3(0.35), vec3(2.2));
        float intensityScale = clamp(pow(skyShiftG, 1.65), 0.22, 3.6);
        sky *= chromaRatio * intensityScale;
    }

    if (uShowGrid == 1) {
        float lon = safeAtan2(dir.z, dir.x);
        float lat = asin(clamp(dir.y, -1.0, 1.0));

        float gridSpacing = PI / 12.0;
        float cosLat = max(0.06, cos(lat));
        float dLon = abs(fract(lon / gridSpacing + 0.5) - 0.5) * gridSpacing * cosLat;
        float dLat = abs(fract(lat / gridSpacing + 0.5) - 0.5) * gridSpacing;

        float line = 1.0 - smoothstep(0.0022, 0.0062, min(dLon, dLat));
        float equator = 1.0 - smoothstep(0.0035, 0.0095, abs(lat));
        float prime = 1.0 - smoothstep(0.0035, 0.0095, abs(lon) * cosLat);

        sky += vec3(0.18, 0.56, 0.98) * line * 0.45;
        sky += vec3(1.00, 0.42, 0.16) * max(equator, prime) * 0.85;
    }
    return sky;
}

// ============================================================================
// EXACT RELATIVISTIC DOPPLER FACTOR FOR DISK & PLUNGE STREAMS
// ============================================================================

float computeDiskDopplerG(vec3 hitP, vec3 pCov, float pt, float obsFreq,
                          float r, float H, vec3 lVec, bool inPlunge) {
    if (uDoppler == 0) return 1.0;

    float rEff = max(uIscoR, r);
    float omegaK = 1.0 / (rEff * sqrt(rEff) + uSpinA);
    vec3 vOrb = omegaK * vec3(-hitP.z, 0.0, hitP.x);

    if (inPlunge) {
        float plungeFrac = clamp((uIscoR - r) / max(0.05, uIscoR - uHorizonR), 0.0, 1.0);
        float rho = max(1e-4, length(hitP.xz));
        vec3 radIn = vec3(-hitP.x / rho, 0.0, -hitP.z / rho);
        float vInMag = 0.52 * sqrt(plungeFrac);
        vOrb = vOrb * (1.0 + 0.25 * plungeFrac) + radIn * vInMag;
    }

    float lv = dot(lVec, vOrb);
    float normSq = 1.0 - dot(vOrb, vOrb) - 2.0 * H * (1.0 + lv) * (1.0 + lv);
    float ut = inversesqrt(max(0.04, normSq));

    float emitFreq = ut * (pt + dot(pCov, vOrb));
    return clamp(obsFreq / max(0.08, emitFreq), 0.14, 3.10);
}

// ============================================================================
// PRIMARY GEODESIC RAY TRACER
// ============================================================================

struct RayResult {
    vec3 accumColor;
    float transmittance;
    vec3 exitDir;
    float skyShiftG;
    float rayPt;
    float hitDist;
    float maxHamErr;
    int stepsUsed;
    bool escaped;
};

RayResult traceGeodesic(vec2 pixelCoord, float a, float a2,
                        float rCam, float Hcam, vec3 lCam, float ign) {
    vec2 ndc = (pixelCoord / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 vLocal = safeNormalize(uCamForward
                              + ndc.x * aspect * uTanHalfFov * uCamRight
                              + ndc.y * uTanHalfFov * uCamUp, uCamForward);

    // Schwarzschild interior anisotropic tidal optical stretching (spaghettification as r -> 0)
    if (uSpaghettify > 0.001) {
        vec3 eRad = safeNormalize(uCamPos, vec3(0.0, 0.0, 1.0));
        float radComp = dot(vLocal, eRad);
        vec3 vRad = radComp * eRad;
        vec3 vTrans = vLocal - vRad;
        float stretch = 1.0 + 3.4 * uSpaghettify;
        float squeeze = 1.0 - 0.62 * uSpaghettify;
        vLocal = safeNormalize(vRad * stretch + vTrans * squeeze, vLocal);
    }

    float rPlus = uHorizonR;
    float iscoR = uIscoR;
    float rEscape = max(42.0, rCam + 8.0);

    vec3 p;
    float pt;
    float obsFreq = 1.0;

    if (uLensing == 0) {
        p = vLocal;
        pt = 1.0;
    } else if (uFreeFall == 1 || (2.0 * Hcam >= 0.92)) {
        // Exact E = 1, L_y = 0, Q = 0 Doran zero-angular-momentum free-fall observer tetrad
        // Constructed via exact Kerr-Schild metric Gram-Schmidt: g_{mu nu} u^mu u^nu = -1, H_ham == 0
        float r2 = rCam * rCam;
        float r2a2 = r2 + a2;
        float cosT = uCamPos.y / max(1e-4, rCam);
        float sigma = max(1e-4, r2 + a2 * cosT * cosT);
        float betaD = sqrt(max(0.0, (2.0 * rCam) / r2a2));
        float onePlusB = 1.0 + betaD;

        float ur = -(r2a2 / sigma) * betaD;
        float u0 = 1.0 + (2.0 * rCam) / (sigma * onePlusB);
        float uphi = -(a * betaD) / (sigma * onePlusB);
        vec3 uSp = ur * lCam + vec3(-uCamPos.z * uphi, 0.0, uCamPos.x * uphi);

        // Exact identity l_alpha u^alpha = 1 / (1 + betaD) -> covariant u_i = u^i + 2H (l.u) l_i
        vec3 uCovSp = uSp + (2.0 * Hcam / onePlusB) * lCam;

        // Project d^mu = (0, vLocal) orthogonal to u^mu in Kerr-Schild metric
        float alphaU = dot(uCovSp, vLocal);
        float w0 = alphaU * u0;
        vec3 wSp = vLocal + alphaU * uSp;
        float lw = w0 + dot(lCam, wSp);
        float wNormSq = max(1e-6, -w0 * w0 + dot(wSp, wSp) + 2.0 * Hcam * lw * lw);
        float invW = inversesqrt(wNormSq);
        float e0 = w0 * invW;
        vec3 eSp = wSp * invW;

        // Backward-traced null contravariant momentum p^mu = -u^mu + e^mu (exact g_{mu nu} p^mu p^nu = 0)
        float p0Con = -u0 + e0;
        vec3 pSpCon = -uSp + eSp;
        float lpCon = p0Con + dot(lCam, pSpCon);
        pt = -p0Con + 2.0 * Hcam * lpCon;
        p = pSpCon + 2.0 * Hcam * lpCon * lCam;
        obsFreq = 1.0;
    } else {
        // Exact Static Shell Observer local orthonormal tetrad outside r_+ & ergosphere
        float alpha = sqrt(max(0.04, 1.0 - 2.0 * Hcam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        pt = 1.0;
        p = dPerp / alpha + ((dl - 2.0 * Hcam) / (alpha * alpha)) * lCam;
        obsFreq = 1.0 / alpha;
    }

    bool startedInside = (rCam <= rPlus);

    RayResult res;
    res.accumColor = vec3(0.0);
    res.transmittance = 1.0;
    res.exitDir = vLocal;
    res.skyShiftG = (uFreeFall == 1) ? clamp(1.0 / max(0.14, pt), 0.38, 3.80) : 1.0;
    res.rayPt = pt;
    res.hitDist = SKY_HIT_DIST;
    res.maxHamErr = 0.0;
    res.stepsUsed = 0;
    res.escaped = false;

    // Inside r_+, backward-traced rays with pt <= 0 can never escape to exterior infinity
    if (uLensing == 1 && startedInside && pt <= 0.0) {
        res.escaped = false;
        res.hitDist = max(1.0, rCam);
        return res;
    }

    vec3 x = uCamPos;
    bool captured = false;
    float totalDeflection = 0.0;
    int diskCrossings = 0;
    float firstDiskDist = -1.0;

    float r = rCam;
    vec3 dxNow = vLocal;
    vec3 dpNow = vec3(0.0);
    if (uLensing == 1) {
        r = hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
    }

    bool jetsActive = (uShowJets == 1 && uJetPower > 0.005);
#ifdef USE_DOPRI54
    float dopriH = clamp(0.12 * max(0.25, abs(r - rPlus)), 0.02, 1.5);
    float integTol = clamp(uIntegTol, 1e-5, 5e-3);
#endif

    for (int step = 0; step < MAX_STEPS; step++) {
        float speed = length(dxNow);
        float pNormSq = dot(p, p);
        float radialOut = dot(x, dxNow);

        if (r > rEscape && radialOut > 0.0) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }
        if (res.transmittance < 0.006) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }

        if (uLensing == 1) {
            if (r < 0.08 || pNormSq > 7225.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.012 && radialOut < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        res.stepsUsed = step + 1;

        // C^1-smooth adaptive step envelope (zero weak-field cutoff seam, zero jet clipping)
        float distHorizon = abs(r - rPlus);
        float tStrong = smoothstep(3.0, 9.5, r);
        float fieldScale = mix(0.105 * distHorizon + 0.026, 0.24 * distHorizon + 0.08, tStrong);
        float tOuter = smoothstep(R_DISK_OUT - 1.5, R_DISK_OUT + 4.0, r);
        float outerScale = (radialOut > 0.0) ? clamp(0.55 * r, 2.4, 18.0) : clamp(0.36 * distHorizon, 1.2, 8.0);
        float absY = abs(x.y);
        float tDiskY = smoothstep(0.6, 2.6, absY);
        float diskGuide = mix(0.54 + absY * 0.65, 5.2, tDiskY);
        float dsCoord = mix(min(fieldScale, diskGuide), outerScale, tOuter);
        if (r < rPlus) {
            dsCoord = clamp(0.070 * r, 0.014, 0.11);
        } else {
            dsCoord = clamp(dsCoord, 0.024, 18.0);
            if (startedInside && r < 7.5 && absY < 1.35) {
                dsCoord = min(dsCoord, 0.085 + 0.20 * absY);
            }
        }

        if (jetsActive && absY > rPlus * 2.0 && absY < 40.0) {
            float rhoAxis = length(x.xz);
            float jetMargin = smoothstep(1.65, 0.85, rhoAxis);
            if (jetMargin > 0.001) {
                dsCoord = mix(dsCoord, min(dsCoord, 0.26 + rhoAxis * 0.26), jetMargin);
            }
        }

        if (step == 0) {
            dsCoord *= (0.65 + 0.35 * ign);
        } else if (startedInside && absY < 0.85) {
            dsCoord *= (0.86 + 0.28 * fract(ign + float(step) * 0.618034));
        }

        vec3 prevX = x;
        vec3 prevP = p;
        vec3 prevDx = dxNow;

        float hStep;
        if (uLensing == 1) {
            float pNorm = sqrt(pNormSq);
            float hMaxLocal = dsCoord / max(1.0, max(speed, pNorm * 0.45));
#ifdef USE_DOPRI54
            float hMinLocal = max(0.004, hMaxLocal * 0.08);
            dopriH = clamp(dopriH, hMinLocal, hMaxLocal);
            r = geodesicStepDopri54(x, p, dopriH, hMinLocal, hMaxLocal, integTol, pt, a, a2, dxNow, dpNow, hStep);
#else
            hStep = hMaxLocal;
            r = geodesicStepRk4(x, p, hStep, pt, a, a2, dxNow, dpNow);
#endif
            if (uDiagMode == 1) {
                res.maxHamErr = max(res.maxHamErr, abs(kerrHamiltonian(x, p, pt, a, a2)));
            }
        } else {
            hStep = dsCoord;
            x += dxNow * hStep;
            r = kerrRadius(x, a2);
        }

        if (uOrderVis == 1) {
            float lenPrev = max(1e-5, length(prevX));
            float lenCur = max(1e-5, length(x));
            totalDeflection += length(cross(prevX, x)) / (lenPrev * lenCur);
        }

        if (uLensing == 1) {
            if (r < 0.08 || dot(p, p) > 7225.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.012 && dot(x, dxNow) < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        // --------------------------------------------------------------------
        // VOLUMETRIC ACCRETION DISK (BEER-LAMBERT + NOVIKOV-THORNE + EVPA B-FIELD)
        // --------------------------------------------------------------------
        if (uShowDisk == 1 && r > rPlus * 1.03 && r <= R_DISK_OUT + 1.5) {
            bool crossed = (prevX.y > 0.0 && x.y <= 0.0) || (prevX.y < 0.0 && x.y >= 0.0);
            if (crossed || abs(x.y) < 0.56) {
                float drIsco = max(0.0, r - iscoR);
                float sqrtDr = sqrt(drIsco);
                float slabHalfH = 0.042 * sqrtDr * sqrt(sqrtDr) + 0.026;
                bool inSlab = (!crossed) && (abs(x.y) < slabHalfH) && (r <= R_DISK_OUT);

                if (crossed || inSlab) {
                    vec3 hitP;
                    vec3 hitPCov;
                    vec3 hitDir;
                    float dsOpt;
                    if (crossed) {
                        float tHit;
                        hitP = intersectEquatorialHermite(prevX, prevDx, x, dxNow, hStep, tHit);
                        hitPCov = mix(prevP, p, tHit);
                        hitDir = safeNormalize(mix(prevDx, dxNow, tHit), dxNow);
                        // Angle-consistent optical path length across midplane (eliminates double-count banding)
                        float segLen = length(x - prevX);
                        float midThickness = min(segLen, (2.0 * slabHalfH) / max(0.24, abs(hitDir.y)));
                        dsOpt = clamp(midThickness * 1.45, 0.22, 1.35);
                        diskCrossings++;
                    } else {
                        hitP = x;
                        hitPCov = p;
                        hitDir = safeNormalize(dxNow, vLocal);
                        float segLen = length(x - prevX);
                        float vProf = exp(-(hitP.y * hitP.y) / max(1e-5, slabHalfH * slabHalfH * 0.38));
                        dsOpt = min(0.45, segLen) * vProf;
                    }

                    float hitR, hitH;
                    vec3 hitL;
                    kerrMetricHL(hitP, a, a2, hitR, hitH, hitL);

                    if (hitR > rPlus * 1.04 && hitR <= R_DISK_OUT) {
                        float phi = safeAtan2(hitP.z, hitP.x);
                        bool inPlunge = (hitR < iscoR);

                        float uNorm = clamp((hitR - iscoR) / max(0.1, R_DISK_OUT - iscoR), 0.0, 1.0);
                        float density = 0.0;
                        float structure = 0.5;
                        float dustLaneFactor = 0.0;

                        if (inPlunge) {
                            float plungeU = clamp((hitR - rPlus * 1.04) / max(0.05, iscoR - rPlus * 1.04), 0.0, 1.0);
                            float spiral = phi - uTime * 2.2 + 5.2 * log(max(0.06, plungeU));
                            vec3 volCoord = vec3(cos(spiral) * 0.28, hitP.y * 0.65, sin(spiral) * 0.28);
                            float stream = textureLod(uNoiseVol, volCoord, 0.5).r;
                            float filament = pow(max(0.0, sin(spiral * 2.0 + stream * 5.2)), 2.8);
                            float fadeHorizon = smoothstep(0.0, 0.42, plungeU);
                            density = filament * fadeHorizon * 0.42 * uDiskDensity;
                            structure = 0.45 + 0.55 * filament;
                        } else {
                            float omegaVis = 2.05 * pow(max(1.0, iscoR) / hitR, 1.38);
                            float logSpiral = phi - uTime * omegaVis + 6.4 * log(hitR / max(1.0, iscoR));
                            float mipLod = clamp(float(max(0, diskCrossings - 1)) * 0.75 + uNorm * 0.45, 0.0, 2.2);

                            float rWrap = 0.22 + uNorm * 0.62;
                            vec3 volCoord = vec3(
                                cos(logSpiral) * rWrap + uNorm * 0.45,
                                hitP.y * 0.50 - uTime * 0.016,
                                sin(logSpiral) * rWrap + uNorm * 0.85
                            );
                            float turbMacro = textureLod(uNoiseVol, volCoord * 0.72, mipLod).r;
                            float turbMid = textureLod(uNoiseVol, volCoord * 1.75 + vec3(0.31, -0.17, 0.23), mipLod).r;

                            float broadBands = 0.5 + 0.5 * sin(uNorm * 26.0 + turbMacro * 6.8);
                            float shearStream = 0.5 + 0.5 * cos(logSpiral * 2.0 + uNorm * 42.0 - turbMid * 7.2);
                            float ridgeFilament = 1.0 - abs(turbMid * 2.0 - 1.0);
                            float plasmaKnots = smoothstep(0.30, 0.84, turbMacro * 0.52 + turbMid * 0.32 + ridgeFilament * 0.16);

                            structure = 0.36 * broadBands + 0.34 * shearStream + 0.30 * plasmaKnots;

                            if (uNorm > 0.24) {
                                float dustNoise = textureLod(uNoiseVol, volCoord * 1.25 + vec3(0.17, 0.62, -0.33), mipLod).r;
                                dustLaneFactor = smoothstep(0.24, 0.84, uNorm) * smoothstep(0.42, 0.74, dustNoise);
                            }

                            float innerRamp = smoothstep(0.0, 0.036, uNorm);
                            float outerFade = pow(max(0.0, 1.0 - uNorm), 1.55);
                            float iscoPeak = exp(-pow((uNorm - 0.024) / 0.048, 2.0)) * 0.82;

                            density = max(0.0, (structure * 1.05 + iscoPeak) * innerRamp * outerFade) * uDiskDensity;
                        }

                        if (density > 0.0025) {
                            float gFactor = computeDiskDopplerG(hitP, hitPCov, pt, obsFreq, hitR, hitH, hitL, inPlunge);

                            vec3 emitCol;
                            float intensityBoost;
                            if (uOrderVis == 1) {
                                if (diskCrossings <= 1 && totalDeflection < PI * 0.95) {
                                    emitCol = vec3(1.00, 0.56, 0.14);
                                } else if (diskCrossings == 2 || totalDeflection < PI * 1.95) {
                                    emitCol = vec3(0.14, 0.88, 1.00);
                                } else {
                                    emitCol = vec3(1.00, 0.18, 0.76);
                                }
                                intensityBoost = 1.0;
                            } else {
                                // Novikov-Thorne thin-disk zero-torque profile T(r) ~ r^(-3/4) * (1 - sqrt(r_ISCO/r))^(1/4)
                                float effR = max(iscoR * 1.02, hitR);
                                float ntFactor = inPlunge ? 0.62 : pow(max(0.18, 1.0 - sqrt(iscoR / effR)), 0.25);
                                float baseTemp = (9800.0 * uDiskTempScale)
                                               * pow(max(1.0, iscoR) / effR, 0.75)
                                               * (0.72 + 0.48 * ntFactor)
                                               * (0.62 + 0.58 * structure);
                                baseTemp *= (1.0 - 0.52 * dustLaneFactor);

                                emitCol = sampleBlackbody(baseTemp * gFactor);

                                // Relativistic specific intensity I_obs = g^3 * I_emit with smooth HDR shoulder
                                float g3 = gFactor * gFactor * gFactor;
                                intensityBoost = g3 / (1.0 + 0.055 * g3);

                                if (uEhtRadio == 1) {
                                    float tb = clamp(pow(density * intensityBoost * 0.58, 0.55), 0.0, 1.0);
                                    emitCol = sampleEhtRadioPalette(tb) * (0.65 + 1.55 * tb);
                                    intensityBoost = 1.0 + 0.38 * tb;
                                }

                                if (uEhtPolarization == 1) {
                                    // Co-moving MAD/SANE toroidal+poloidal B-field projected orthogonal to null ray k_hat
                                    // (optically thin synchrotron EVPA visualization; Faraday rotation not modeled)
                                    vec3 rHat = safeNormalize(vec3(hitP.x, 0.0, hitP.z), vec3(1.0, 0.0, 0.0));
                                    vec3 phiHat = vec3(-rHat.z, 0.0, rHat.x);
                                    float pitchAngle = atan(1.0, 1.55 + 2.10 * a);
                                    vec3 bField = cos(pitchAngle) * phiHat + sin(pitchAngle) * rHat;
                                    // Synchrotron linear polarization is perpendicular to both k_hat and B (e_pol ~ k x B)
                                    vec3 bPerp = bField - dot(bField, hitDir) * hitDir;
                                    float sinPitchRay = clamp(length(bPerp), 0.0, 1.0); // sin(theta_B) emission factor

                                    float pitchWind = 1.65 + 2.15 * a;
                                    float logR = log(max(1.0, hitR / max(1.0, iscoR)));
                                    float spiralCoord = phi - pitchWind * logR - uTime * 0.24;
                                    float warp = 0.22 * sin(2.0 * phi - 3.0 * logR + uTime * 0.18)
                                               + 0.10 * cos(5.0 * phi + 2.0 * logR);
                                    float c1 = 0.5 + 0.5 * cos(22.0 * spiralCoord + warp);
                                    float c1Sq = c1 * c1;
                                    float f1 = c1Sq * c1Sq * c1; // c1^5 without pow()
                                    float c2 = 0.5 + 0.5 * cos(44.0 * spiralCoord - warp * 1.3);
                                    float c2Sq = c2 * c2;
                                    float f2 = c2Sq * c2Sq * c2Sq * c2; // c2^7 without pow()
                                    float dash = 0.62 + 0.38 * smoothstep(-0.35, 0.65, sin(18.0 * logR + phi * 3.0 - uTime * 0.55));
                                    float env = smoothstep(0.0, 0.05, uNorm) * smoothstep(0.96, 0.18, uNorm);
                                    float streamSignal = clamp((f1 * 0.72 + f2 * 0.42) * dash, 0.0, 1.0) * env * (0.35 + 0.65 * sinPitchRay);
                                    emitCol *= mix(0.76, 1.24, streamSignal);
                                    vec3 polCol = (uEhtRadio == 1)
                                            ? vec3(1.18, 0.84, 0.42)
                                            : vec3(0.32, 0.78, 1.25);
                                    emitCol += polCol * (streamSignal * (0.28 + 0.26 * clamp(gFactor, 0.4, 2.0)));
                                }
                            }

                            float emitSuppression = 1.0 - 0.74 * dustLaneFactor;
                            float surgeBoost = 1.0;
                            if (uAccretionSurge > 0.002) {
                                float innerZone = exp(-max(0.0, hitR - iscoR) * 0.42);
                                surgeBoost = 1.0 + uAccretionSurge * 0.58 * innerZone;
                                emitCol = mix(emitCol, vec3(1.15, 0.96, 0.84) * max(emitCol.r, max(emitCol.g, emitCol.b)),
                                        clamp(uAccretionSurge * 0.30 * innerZone, 0.0, 0.35));
                            }

                            // Energy-conserving Beer-Lambert homogeneous slab radiative transfer:
                            // dI = (j / sigma_t) * (1 - exp(-sigma_t * ds))
                            float sigmaT = max(1e-4, density * (0.72 + 1.30 * uNorm + 1.55 * dustLaneFactor));
                            float tau = sigmaT * dsOpt;
                            float stepTrans = exp(-tau);
                            float alphaStep = 1.0 - stepTrans;
                            float sourceGain = (0.98 * intensityBoost * surgeBoost * emitSuppression)
                                             / (0.72 + 1.30 * uNorm + 1.55 * dustLaneFactor);

                            res.accumColor += res.transmittance * (sourceGain * alphaStep) * emitCol;
                            res.transmittance *= stepTrans;
                            if (firstDiskDist < 0.0 && alphaStep > 0.06) {
                                firstDiskDist = length(hitP - uCamPos);
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // COLLIMATED BIPOLAR RELATIVISTIC SYNCHROTRON JETS (+/- Y AXIS)
        // --------------------------------------------------------------------
        if (jetsActive) {
            float absYJ = abs(x.y);
            if (absYJ > rPlus * 2.2 && absYJ < 40.0) {
                float rFunnel = (0.18 + 0.08 * a) * pow(1.0 + absYJ * 0.14, 0.52);
                float rho = length(x.xz);
                if (rho < rFunnel * 2.20) {
                    float jetSign = (x.y >= 0.0) ? 1.0 : -1.0;
                    float uJet = rho / max(0.05, rFunnel);

                    float spine = exp(-uJet * uJet * 4.8) * 1.15;
                    float sheath = exp(-pow((uJet - 0.82) / 0.38, 2.0)) * 0.34;
                    float radialProfile = (spine + sheath) * smoothstep(2.20, 1.25, uJet);

                    float baseLaunch = smoothstep(rPlus * 2.20, rPlus * 6.40, absYJ);
                    float topFade = exp(-absYJ * 0.015) * smoothstep(40.0, 22.0, absYJ);

                    float phiJet = safeAtan2(x.z, x.x);
                    float helixPhase = phiJet * 2.0 - jetSign * (absYJ * (0.48 + 0.55 * a) - uTime * 4.2);
                    vec3 jetNoiseCoord = vec3(
                        x.x * 0.18,
                        absYJ * 0.048 - uTime * 0.68,
                        x.z * 0.18
                    );
                    float jTurb = textureLod(uNoiseVol, jetNoiseCoord, 0.4).r;
                    float filament = 0.64 + 0.36 * pow(0.5 + 0.5 * sin(helixPhase + jTurb * 4.4), 1.5);
                    float knots = 0.70 + 0.30 * jTurb;

                    float jetDensity = radialProfile * baseLaunch * topFade * filament * knots * uJetPower;
                    if (jetDensity > 0.003) {
                        float gJet = 1.0;
                        if (uDoppler == 1) {
                            float betaJet = 0.66;
                            vec3 vJet = vec3(0.0, jetSign * betaJet, 0.0);
                            float rJ, Hj;
                            vec3 lJ;
                            kerrMetricHL(x, a, a2, rJ, Hj, lJ);
                            float lvJ = dot(lJ, vJet);
                            float normJ = 1.0 - betaJet * betaJet - 2.0 * Hj * (1.0 + lvJ) * (1.0 + lvJ);
                            float utJ = inversesqrt(max(0.05, normJ));
                            float emitFreqJ = utJ * (pt + dot(p, vJet));
                            gJet = clamp(obsFreq / max(0.08, emitFreqJ), 0.35, 2.50);
                        }

                        float synchTemp = (18000.0 + 9000.0 * jTurb) * clamp(gJet, 0.65, 1.70);
                        vec3 synchCol = mix(sampleBlackbody(synchTemp), vec3(0.36, 0.64, 1.55), 0.48);

                        float g3 = gJet * gJet * gJet;
                        float jetBoost = g3 / (1.0 + 0.28 * g3);

                        float segLenJet = length(x - prevX);
                        float jetTau = jetDensity * segLenJet * 0.010;
                        float jetTrans = exp(-jetTau);
                        float jetEmit = jetDensity * jetBoost * segLenJet * 0.24;

                        res.accumColor += res.transmittance * jetEmit * synchCol;
                        res.transmittance *= jetTrans;
                        if (firstDiskDist < 0.0 && jetEmit > 0.025) {
                            firstDiskDist = length(x - uCamPos);
                        }
                    }
                }
            }
        }
    }

    if (captured) {
        res.escaped = false;
    }

    if (firstDiskDist > 0.0) {
        res.hitDist = firstDiskDist;
    } else if (!res.escaped) {
        res.hitDist = max(1.0, rCam);
    } else {
        res.hitDist = SKY_HIT_DIST;
    }
    return res;
}

vec3 applyInteriorClimax(vec3 col, float rayPt) {
    if (uCauchyFlash > 0.001) {
        float sharpRing = exp(-pow(rayPt / (0.014 + 0.022 * uCauchyFlash), 2.0));
        float coronaHalo = exp(-pow(rayPt / (0.052 + 0.055 * uCauchyFlash), 2.0));
        vec3 coreCol = vec3(0.82, 0.94, 1.70);
        vec3 haloCol = vec3(0.18, 0.54, 1.48);
        col += coreCol * sharpRing * uCauchyFlash * 4.2
             + haloCol * coronaHalo * uCauchyFlash * 0.72
             + haloCol * (uCauchyFlash * uCauchyFlash * 0.035);
    }

    if (uSingularityFade > 0.001 && uCauchyFlash < 0.05) {
        col *= (1.0 - clamp(uSingularityFade, 0.0, 1.0));
    }

    return sanitizeHdr(col);
}

void main() {
    float a = clamp(uSpinA, 0.0, 0.998);
    float a2 = a * a;

    float rCam, Hcam;
    vec3 lCam;
    kerrMetricHL(uCamPos, a, a2, rCam, Hcam, lCam);

    float frameShift = float(uFrameIndex & 63) * 5.588238;
    float ign = interleavedGradientNoise(gl_FragCoord.xy + vec2(frameShift));
    RayResult primary = traceGeodesic(gl_FragCoord.xy + uJitter, a, a2, rCam, Hcam, lCam, ign);

    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);

    float maxGrad = 0.14;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0;
    if (uDiagMode == 1) {
        // Real per-ray maximum null-constraint error log10(|H_ham|) mapped over [1e-7 .. 1e-2]
        float logErr = log(max(1e-7, primary.maxHamErr)) / log(10.0);
        c0 = turboColormap((logErr + 7.0) / 5.0);
    } else if (uDiagMode == 2) {
        // Real per-ray accepted integrator step density
        c0 = turboColormap(float(primary.stepsUsed) / float(MAX_STEPS));
    } else {
        c0 = primary.accumColor;
        if (primary.escaped && primary.transmittance > 0.005) {
            c0 += primary.transmittance * sampleSkyWithGrid(primary.exitDir, ddx, ddy, primary.skyShiftG);
        }
        c0 = applyInteriorClimax(c0, primary.rayPt);
    }

    FragColor = vec4(sanitizeHdr(c0), primary.hitDist);
}
