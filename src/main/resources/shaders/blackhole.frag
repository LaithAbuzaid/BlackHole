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
uniform float uInnerHorizonR;
uniform float uIscoR;
uniform float uDiskTempScale;
uniform float uDiskDensity;
uniform float uJetPower;
uniform float uTanHalfFov;
uniform float uCauchyFlash;
uniform float uSpaghettify;
uniform float uSingularityFade;
uniform int uDoppler;
uniform int uLensing;
uniform int uShowDisk;
uniform int uShowJets;
uniform int uFreeFall;
uniform int uShowGrid;
uniform int uOrderVis;
uniform int uAdaptiveAa;

// Geometric units: G = c = M = 1, r_s = 2M = 2.0
const float PI = 3.14159265359;
const float M_BH = 1.0;
const float R_DISK_OUT = 24.0;
const float R_SHELL = 27.5;
const int MAX_STEPS = 180;

// ============================================================================
// SAFE MATH & PRECOMPUTED 512x1 BLACKBODY LUT
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

// Single-cycle hardware lookup into precomputed 512x1 sqrt-spaced Planckian RGB16F LUT (900K..38000K)
vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    return texture(uBlackbodyLut, vec2(sqrt(norm), 0.5)).rgb;
}

// Interleaved Gradient Noise (Jimenez 2014) for artifact-free volumetric step dithering
float interleavedGradientNoise(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// ============================================================================
// EXACT KERR-SCHILD METRIC & ANALYTIC HAMILTONIAN DERIVATIVES
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
    float r2 = r * r;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    H = (M_BH * r2 * r) / D;
    float invS = 1.0 / max(1e-4, r2 + a2);
    lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y / r,
        (r * x.z - a * x.x) * invS
    );
}

// Exact analytic Hamiltonian derivatives for H_ham = 0.5 * (-pt^2 + |p|^2 - 2 H (-pt + l.p)^2) = 0
// Guaranteed d(H_ham)/d(lambda) == 0 identically (zero radial repulsion cusps).
// Lean signature minimizes GPU register pressure inside the geodesic integration loop.
float hamiltonDeriv(vec3 x, vec3 p, float pt, float a, float a2,
                    out vec3 dx, out vec3 dp) {
    float r = kerrRadius(x, a2);
    float r2 = r * r;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    float invD = 1.0 / D;
    float H = (M_BH * r2 * r) * invD;
    float S = max(1e-4, r2 + a2);
    float invS = 1.0 / S;

    vec3 lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y / r,
        (r * x.z - a * x.x) * invS
    );

    float lp = dot(lVec, p);
    float q = -pt + lp;

    dx = p - (2.0 * H * q) * lVec;

    vec3 gradR = (r * invD) * vec3(r2 * x.x, S * x.y, r2 * x.z);
    vec3 gradH = (r2 * (3.0 * a2 * x.y * x.y - r2 * r2) * gradR
                - vec3(0.0, 2.0 * a2 * r2 * r * x.y, 0.0)) * (invD * invD);

    float xpZp = x.x * p.x + x.z * p.z;
    float zpxXpz = x.z * p.x - x.x * p.z;
    vec3 directLP = vec3(
        (r * p.x - a * p.z) * invS,
        p.y / r,
        (r * p.z + a * p.x) * invS
    );
    float dlpDr = ((a2 - r2) * xpZp - 2.0 * a * r * zpxXpz) * (invS * invS)
                - (x.y * p.y) / r2;
    vec3 gradLP = directLP + dlpDr * gradR;

    dp = (q * q) * gradH + (2.0 * H * q) * gradLP;
    return r;
}

// FSAL-style RK4 in strong field (r <= 5.5M), RK2 midpoint in weak field (r > 5.5M)
float geodesicStep(inout vec3 x, inout vec3 p, float h, float pt, float a, float a2, float rNow,
                   inout vec3 dxNow, inout vec3 dpNow) {
    vec3 k1x = dxNow;
    vec3 k1p = dpNow;
    vec3 k2x, k2p;
    hamiltonDeriv(x + (0.5 * h) * k1x, p + (0.5 * h) * k1p, pt, a, a2, k2x, k2p);

    if (rNow > 5.5) {
        x += h * k2x;
        p += h * k2p;
        return hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
    } else {
        vec3 k3x, k3p, k4x, k4p;
        hamiltonDeriv(x + (0.5 * h) * k2x, p + (0.5 * h) * k2p, pt, a, a2, k3x, k3p);
        hamiltonDeriv(x + h * k3x, p + h * k3p, pt, a, a2, k4x, k4p);

        float h6 = h * (1.0 / 6.0);
        x += h6 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        p += h6 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
        return hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
    }
}

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
// SKYBOX SAMPLING WITH FREE-FALL RELATIVISTIC BLUESHIFT & OPTIONAL GRID
// ============================================================================

vec3 sampleSkyWithGrid(vec3 dir, vec3 ddx, vec3 ddy, float skyShiftG) {
    vec3 sky = textureGrad(uSkybox, dir, ddx, ddy).rgb;

    // Relativistic headlight frequency shift for plunging Doran free-fall observer
    if (uFreeFall == 1 && abs(skyShiftG - 1.0) > 0.02) {
        vec3 blueTint = vec3(0.62, 0.86, 1.48);
        vec3 redTint = vec3(1.32, 0.72, 0.44);
        vec3 shiftTint = (skyShiftG >= 1.0)
                ? mix(vec3(1.0), blueTint, clamp((skyShiftG - 1.0) * 0.55, 0.0, 1.0))
                : mix(vec3(1.0), redTint, clamp((1.0 - skyShiftG) * 1.15, 0.0, 1.0));
        float intensityScale = clamp(pow(skyShiftG, 1.65), 0.25, 3.6);
        sky *= shiftTint * intensityScale;
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
// PRIMARY & SUB-PIXEL GEODESIC RAY TRACER
// ============================================================================

struct RayResult {
    vec3 accumColor;
    float transmittance;
    vec3 exitDir;
    float skyShiftG;
    float rayPt;
    bool escaped;
    bool photonRingZone;
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
    float rEscape = max(44.0, rCam + 10.0);

    vec3 p;
    float pt;
    float obsFreq = 1.0;

    if (uLensing == 0) {
        p = vLocal;
        pt = 1.0;
    } else if (uFreeFall == 1 || (2.0 * Hcam >= 0.92)) {
        // Exact Doran / Kerr-Schild horizon-penetrating Free-Fall Observer tetrad
        float s2H = sqrt(max(0.0, 2.0 * Hcam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        pt = 1.0 + dl * s2H;
        p = dPerp + (dl + (s2H + 2.0 * Hcam * dl) / (1.0 + s2H)) * lCam;
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
    res.escaped = false;
    res.photonRingZone = false;

    // Inside r_+, backward-traced rays with pt <= 0 can never escape to exterior infinity
    if (uLensing == 1 && startedInside && pt <= 0.0) {
        res.escaped = false;
        return res;
    }

    // Analytic weak-field empty-space shortcut for far-field corner rays outside gravitational shell
    vec3 x = uCamPos;
    float bProj = dot(-x, vLocal);
    float bSq = max(0.0, dot(x, x) - bProj * bProj);
    if (uLensing == 1 && rCam > R_SHELL + 1.0 && (bProj <= 0.0 || bSq > 32.0 * 32.0)) {
        float b = sqrt(max(1.0, bSq));
        vec3 bVec = x + vLocal * max(0.0, bProj);
        float defl = (bProj > 0.0)
                ? (4.0 / b + 11.78097 / (b * b))
                : (2.0 * max(0.0, 1.0 - dot(safeNormalize(x, vec3(0, 0, 1)), vLocal)) / max(1.0, rCam));
        res.exitDir = safeNormalize(vLocal - safeNormalize(bVec, vec3(0.0, 1.0, 0.0)) * defl, vLocal);
        res.escaped = true;
        return res;
    }

    bool captured = false;
    float totalDeflection = 0.0;
    int diskCrossings = 0;

    float r = rCam;
    vec3 dxNow = vLocal;
    vec3 dpNow = vec3(0.0);
    if (uLensing == 1) {
        r = hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);

        // Single analytic leapfrog advance across empty exterior space to R_SHELL (27.5 M)
        if (rCam > R_SHELL + 1.5 && bProj > 0.0 && bSq < R_SHELL * R_SHELL) {
            float tSkip = max(0.0, bProj - sqrt(max(0.0, R_SHELL * R_SHELL - bSq))) * 0.92;
            if (tSkip > 1.0) {
                float speed0 = max(1.0, length(dxNow));
                float hSkip = tSkip / speed0;
                r = geodesicStep(x, p, hSkip, pt, a, a2, r, dxNow, dpNow);
            }
        }
    }

    bool jetsActive = (uShowJets == 1 && uJetPower > 0.005);

    for (int step = 0; step < MAX_STEPS; step++) {
        float speed = length(dxNow);
        float pNorm = length(p);
        float radialOut = dot(x, dxNow);

        if (r > rEscape && radialOut > 0.0) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }
        if (res.transmittance < 0.008) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }

        if (uLensing == 1) {
            // True interior cutoff (r < 0.08M) with zero artificial walls at r_+ for plunging observers
            if (r < 0.08 || pNorm > 85.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.014 && radialOut < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        // High-efficiency adaptive step sizing
        float distHorizon = abs(r - rPlus);
        float dsCoord;
        if (r < rPlus) {
            dsCoord = clamp(0.075 * r, 0.015, 0.12);
        } else if (r > R_DISK_OUT + 1.5) {
            dsCoord = (radialOut > 0.0)
                    ? clamp(0.36 * r, 1.60, 8.50)
                    : clamp(0.26 * distHorizon, 0.80, 4.80);
        } else {
            float absY = abs(x.y);
            float diskGuide = (absY > 2.0) ? 3.50 : (0.58 + absY * 0.72);
            float fieldScale = (r > 5.5) ? (0.165 * distHorizon + 0.042) : (0.118 * distHorizon + 0.032);
            dsCoord = clamp(min(fieldScale, diskGuide), 0.030, 3.20);
        }

        if (jetsActive) {
            float absY = abs(x.y);
            if (absY > rPlus * 1.2 && absY < 34.0) {
                float rhoAxis = length(x.xz);
                if (rhoAxis < 3.4) {
                    dsCoord = min(dsCoord, 0.36 + rhoAxis * 0.25);
                }
            }
        }

        // Per-pixel IGN dithering eliminates volumetric slab/jet and grazing-angle banding
        if (step == 0) {
            dsCoord *= (0.60 + 0.40 * ign);
        } else if (startedInside && abs(x.y) < 0.85) {
            dsCoord *= (0.84 + 0.32 * fract(ign + float(step) * 0.618034));
        }

        vec3 prevX = x;
        vec3 prevP = p;
        vec3 prevDx = dxNow;

        float hStep;
        if (uLensing == 1) {
            hStep = dsCoord / max(1.0, max(speed, pNorm * 0.45));
            r = geodesicStep(x, p, hStep, pt, a, a2, r, dxNow, dpNow);
        } else {
            hStep = dsCoord;
            x += dxNow * hStep;
            r = kerrRadius(x, a2);
        }

        float segLen = length(x - prevX);
        float lenPrev = max(1e-5, length(prevX));
        float lenCur = max(1e-5, length(x));
        totalDeflection += length(cross(prevX, x)) / (lenPrev * lenCur);

        if (uLensing == 1) {
            if (r < 0.08 || length(p) > 85.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.014 && dot(x, dxNow) < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        // --------------------------------------------------------------------
        // VOLUMETRIC ACCRETION DISK, ABSORBING DUST LANES & PLUNGE STREAMS
        // --------------------------------------------------------------------
        if (uShowDisk == 1 && r > rPlus * 1.03 && r <= R_DISK_OUT + 1.5) {
            bool crossed = (prevX.y > 0.0 && x.y <= 0.0) || (prevX.y < 0.0 && x.y >= 0.0);
            if (crossed || abs(x.y) < 0.52) {
                float slabHalfH = 0.040 * pow(max(0.0, r - iscoR), 0.76) + 0.024;
                bool inSlab = (!crossed) && (abs(x.y) < slabHalfH) && (r <= R_DISK_OUT);

                if (crossed || inSlab) {
                    vec3 hitP;
                    vec3 hitPCov;
                    float stepWeight;
                    if (crossed) {
                        float tHit;
                        hitP = intersectEquatorialHermite(prevX, prevDx, x, dxNow, hStep, tHit);
                        hitPCov = mix(prevP, p, tHit);
                        stepWeight = 0.68;
                        diskCrossings++;
                    } else {
                        hitP = x;
                        hitPCov = p;
                        float vProf = exp(-(hitP.y * hitP.y) / max(1e-5, slabHalfH * slabHalfH * 0.36));
                        stepWeight = min(0.28, segLen * 0.58) * vProf;
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
                            float stream = texture(uNoiseVol, volCoord).r;
                            float filament = pow(max(0.0, sin(spiral * 3.0 + stream * 5.8)), 3.5);
                            float fadeHorizon = smoothstep(0.0, 0.38, plungeU);
                            density = filament * fadeHorizon * 0.48 * uDiskDensity;
                            structure = 0.45 + 0.55 * filament;
                        } else {
                            float omegaVis = 2.05 * pow(max(1.0, iscoR) / hitR, 1.38);
                            float logSpiral = phi - uTime * omegaVis + 7.8 * log(hitR / max(1.0, iscoR));

                            vec3 volCoord = vec3(
                                cos(logSpiral) * (0.24 + uNorm * 0.68),
                                hitP.y * 0.55 - uTime * 0.018,
                                sin(logSpiral) * (0.24 + uNorm * 0.68) + uNorm * 0.85
                            );
                            float turb = texture(uNoiseVol, volCoord).r;
                            float turbFine = texture(uNoiseVol, volCoord * 2.35 + vec3(0.41, -0.19, 0.27)).r;

                            float mainWaves = 0.5 + 0.5 * sin(uNorm * 68.0 + turb * 7.8);
                            float fineThreads = 0.5 + 0.5 * cos(uNorm * 165.0 - turbFine * 10.5 + logSpiral * 2.0);
                            structure = 0.56 * mainWaves + 0.44 * fineThreads;

                            if (uNorm > 0.22) {
                                float dustNoise = texture(uNoiseVol, volCoord * 1.35 + vec3(0.17, 0.62, -0.33)).r;
                                dustLaneFactor = smoothstep(0.22, 0.80, uNorm) * smoothstep(0.38, 0.70, dustNoise);
                            }

                            float innerRamp = smoothstep(0.0, 0.032, uNorm);
                            float outerFade = pow(max(0.0, 1.0 - uNorm), 1.45);
                            float iscoPeak = exp(-pow((uNorm - 0.028) / 0.055, 2.0)) * 0.68;

                            density = max(0.0, (structure * 1.08 + iscoPeak) * innerRamp * outerFade) * uDiskDensity;
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
                                float effR = max(iscoR * 0.95, hitR);
                                float baseTemp = (11800.0 * uDiskTempScale)
                                               * pow(max(1.0, iscoR) / effR, 0.72)
                                               * (0.64 + 0.56 * structure);
                                baseTemp *= (1.0 - 0.55 * dustLaneFactor);

                                emitCol = sampleBlackbody(baseTemp * gFactor);

                                float g4 = gFactor * gFactor * gFactor * gFactor;
                                intensityBoost = g4 / (1.0 + 0.34 * g4);
                            }

                            float emitSuppression = 1.0 - 0.76 * dustLaneFactor;
                            float emission = density * intensityBoost * emitSuppression * stepWeight * 0.92;
                            float opacity = clamp(density * stepWeight * (0.58 + 1.40 * uNorm + 1.55 * dustLaneFactor), 0.0, 0.96);

                            res.accumColor += res.transmittance * emission * emitCol;
                            res.transmittance *= (1.0 - opacity);
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // COLLIMATED BIPOLAR RELATIVISTIC SYNCHROTRON JETS (+/- Y AXIS)
        // --------------------------------------------------------------------
        if (jetsActive) {
            float absY = abs(x.y);
            if (absY > rPlus * 1.45 && absY < 34.0) {
                float rFunnel = (0.28 + 0.15 * a) * pow(1.0 + absY * 0.22, 0.52);
                float rho = length(x.xz);
                if (rho < rFunnel * 2.15) {
                    float jetSign = (x.y >= 0.0) ? 1.0 : -1.0;
                    float uJet = rho / max(0.05, rFunnel);

                    float spine = exp(-uJet * uJet * 6.0) * 1.05;
                    float sheath = exp(-pow((uJet - 0.76) / 0.28, 2.0)) * 0.70;
                    float radialProfile = spine + sheath;

                    float baseLaunch = smoothstep(rPlus * 1.45, rPlus * 3.10, absY);
                    float topFade = exp(-absY * 0.034);

                    float phiJet = safeAtan2(x.z, x.x);
                    float helixPhase = phiJet * 2.0 - jetSign * (absY * (1.05 + 1.15 * a) - uTime * 5.2);
                    vec3 jetNoiseCoord = vec3(
                        x.x * 0.20,
                        absY * 0.085 - uTime * 0.85,
                        x.z * 0.20
                    );
                    float jTurb = texture(uNoiseVol, jetNoiseCoord).r;
                    float jFine = texture(uNoiseVol, jetNoiseCoord * 2.2 + vec3(0.31, -0.42, 0.18)).r;
                    float filament = 0.52 + 0.48 * pow(0.5 + 0.5 * sin(helixPhase + jTurb * 6.2), 1.8);
                    float knots = 0.58 + 0.42 * jFine;

                    float jetDensity = radialProfile * baseLaunch * topFade * filament * knots * uJetPower;
                    if (jetDensity > 0.004) {
                        float gJet = 1.0;
                        if (uDoppler == 1) {
                            float betaJet = 0.78;
                            vec3 vJet = vec3(0.0, jetSign * betaJet, 0.0);
                            float rJ, Hj;
                            vec3 lJ;
                            kerrMetricHL(x, a, a2, rJ, Hj, lJ);
                            float lvJ = dot(lJ, vJet);
                            float normJ = 1.0 - betaJet * betaJet - 2.0 * Hj * (1.0 + lvJ) * (1.0 + lvJ);
                            float utJ = inversesqrt(max(0.05, normJ));
                            float emitFreqJ = utJ * (pt + dot(p, vJet));
                            gJet = clamp(obsFreq / max(0.08, emitFreqJ), 0.18, 3.20);
                        }

                        float synchTemp = (18000.0 + 10000.0 * jFine) * clamp(gJet, 0.55, 1.85);
                        vec3 synchCol = mix(sampleBlackbody(synchTemp), vec3(0.36, 0.70, 1.58), 0.42);

                        float g3 = gJet * gJet * gJet;
                        float jetBoost = g3 / (1.0 + 0.22 * g3);

                        float jetEmit = jetDensity * jetBoost * segLen * 0.34;
                        float jetAlpha = clamp(jetDensity * segLen * 0.020, 0.0, 0.24);

                        res.accumColor += res.transmittance * jetEmit * synchCol;
                        res.transmittance *= (1.0 - jetAlpha);
                    }
                }
            }
        }
    }

    if (captured) {
        res.escaped = false;
    }

    res.photonRingZone = (diskCrossings >= 3) || (res.escaped && totalDeflection > 2.35);
    return res;
}

vec3 applyInteriorClimax(vec3 col, float rayPt) {
    // Kerr Inner Cauchy Horizon (r_-) Mass-Inflation Blueshift Flash:
    // Infinitely blueshifted external radiation piles up along the horizon caustic boundary (rayPt ~ 0)
    // as an intense electric blue-white celestial ring rather than washing out the viewport to flat white.
    if (uCauchyFlash > 0.001) {
        float sharpRing = exp(-pow(rayPt / (0.045 + 0.055 * uCauchyFlash), 2.0));
        float coronaHalo = exp(-pow(rayPt / (0.18 + 0.16 * uCauchyFlash), 2.0));
        vec3 coreCol = vec3(0.88, 0.96, 1.65);
        vec3 haloCol = vec3(0.24, 0.62, 1.55);
        col += coreCol * sharpRing * uCauchyFlash * 5.8
             + haloCol * coronaHalo * uCauchyFlash * 1.35
             + haloCol * (uCauchyFlash * uCauchyFlash * 0.14);
    }

    // Schwarzschild Singularity Impact Fade (r -> 0)
    if (uSingularityFade > 0.001 && uCauchyFlash < 0.05) {
        col *= (1.0 - clamp(uSingularityFade, 0.0, 1.0));
    }

    return sanitizeHdr(col);
}

void main() {
    float a = clamp(uSpinA, 0.0, 0.998);
    float a2 = a * a;

    // Hoisted camera metric evaluation: computed ONCE per pixel instead of 4x during AA
    float rCam, Hcam;
    vec3 lCam;
    kerrMetricHL(uCamPos, a, a2, rCam, Hcam, lCam);

    float ign = interleavedGradientNoise(gl_FragCoord.xy);
    RayResult primary = traceGeodesic(gl_FragCoord.xy, a, a2, rCam, Hcam, lCam, ign);

    // Hardware 2x2 quad derivatives evaluated unconditionally in uniform control flow
    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);
    float dirGrad = length(rawDdx) + length(rawDdy);

    float maxGrad = 0.16;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0 = primary.accumColor;
    if (primary.escaped && primary.transmittance > 0.005) {
        c0 += primary.transmittance * sampleSkyWithGrid(primary.exitDir, ddx, ddy, primary.skyShiftG);
    }
    c0 = applyInteriorClimax(c0, primary.rayPt);

    // Edge-aware adaptive AA mask in perceptual tone-mapped luma space:
    // preserves 4x AA on shadow silhouette, photon ring, Einstein ring, stars & crisp edges
    // while avoiding redundant 4x oversampling on smooth HDR mid-disk regions.
    float escMask = primary.escaped ? 1.0 : 0.0;
    bool shadowEdge = fwidth(escMask) > 0.01;
    float luma0 = dot(c0, vec3(0.2126, 0.7152, 0.0722));
    float percLuma = luma0 / (1.0 + luma0);
    float edgeContrast = fwidth(percLuma);

    bool needsAa = shadowEdge
                || primary.photonRingZone
                || (primary.escaped && dirGrad > 0.080)
                || (edgeContrast > 0.055);

    if (uAdaptiveAa == 0 || !needsAa) {
        FragColor = vec4(c0, 1.0);
        return;
    }

    // Edge-aware Adaptive 4x Stratified AA using R2 quasi-random sub-pixel offsets
    vec2 r2Offsets[3] = vec2[](
        vec2( 0.254878,  0.069840),
        vec2(-0.490245, -0.360319),
        vec2(-0.235367,  0.209521)
    );

    vec3 sum = c0;
    vec3 subDdx = ddx * 0.5;
    vec3 subDdy = ddy * 0.5;

    for (int s = 0; s < 3; s++) {
        float subIgn = fract(ign + 0.3183098 * float(s + 1));
        RayResult sub = traceGeodesic(gl_FragCoord.xy + r2Offsets[s], a, a2, rCam, Hcam, lCam, subIgn);
        vec3 cs = sub.accumColor;
        if (sub.escaped && sub.transmittance > 0.005) {
            cs += sub.transmittance * sampleSkyWithGrid(sub.exitDir, subDdx, subDdy, sub.skyShiftG);
        }
        sum += applyInteriorClimax(cs, sub.rayPt);
    }

    FragColor = vec4(sanitizeHdr(sum * 0.25), 1.0);
}
